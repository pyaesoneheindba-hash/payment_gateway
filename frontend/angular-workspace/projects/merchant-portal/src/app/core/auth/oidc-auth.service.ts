import {Injectable} from '@angular/core';
import {environment} from '../../../environments/environment';

interface OidcTokenResponse {
  access_token?: string;
  refresh_token?: string;
  id_token?: string;
  expires_in?: number;
  refresh_expires_in?: number;
  token_type?: string;
  scope?: string;
  session_state?: string;
}

interface JwtPayload {
  exp?: number;
  nonce?: string;
}

/**
 * OIDC Authorization Code + PKCE session manager for the browser portals.
 *
 * Security note: bearer tokens are deliberately kept in memory only. The only values
 * persisted in sessionStorage are short-lived PKCE/state/nonce correlation values and
 * the safe return URL used across the Keycloak redirect. A full page reload therefore
 * performs a new Authorization Code flow; an existing Keycloak SSO session makes that
 * redirect non-interactive.
 */
@Injectable({providedIn: 'root'})
export class OidcAuthService {
  private readonly verifierKey = 'portal_pkce_verifier';
  private readonly stateKey = 'portal_oidc_state';
  private readonly nonceKey = 'portal_oidc_nonce';
  private readonly returnUrlKey = 'portal_oidc_return_url';

  private accessToken?: string;
  private refreshToken?: string;
  private idToken?: string;
  private accessExpiresAt?: number;
  private refreshExpiresAt?: number;

  private refreshPromise?: Promise<string | null>;
  private refreshTimer?: number;
  private loginRedirectInProgress = false;

  constructor() {
    // Remove bearer tokens written by older portal builds. Keycloak recommends keeping
    // browser access/refresh tokens in memory rather than persistent web storage.
    for (const key of [
      'portal_access_token', 'portal_refresh_token', 'portal_id_token',
      'portal_access_expires_at', 'portal_refresh_expires_at'
    ]) sessionStorage.removeItem(key);
  }

  /** Return the current access token only while it is still valid. */
  token(): string | null {
    if (!this.accessToken || this.isExpired(this.accessExpiresAt)) return null;
    return this.accessToken;
  }

  /** Obtain an access token with enough remaining lifetime for an API/SSE request. */
  async validToken(minValiditySeconds = 30): Promise<string | null> {
    if (this.accessToken && !this.isExpiringWithin(this.accessExpiresAt, minValiditySeconds)) {
      return this.accessToken;
    }

    try {
      const refreshed = await this.refreshAccessToken(true);
      if (refreshed) return refreshed;
    } catch {
      // Do not throw away a still-valid access token because Keycloak had a temporary
      // network/server error while proactively refreshing it.
      if (this.accessToken && !this.isExpired(this.accessExpiresAt)) return this.accessToken;
      throw new Error('OIDC token refresh temporarily failed');
    }

    if (this.accessToken && !this.isExpired(this.accessExpiresAt)) return this.accessToken;
    return null;
  }

  async ensureAuthenticated(): Promise<boolean> {
    try {
      const token = await this.validToken();
      if (token) return true;
    } catch {
      // Avoid a redirect loop during a transient Keycloak outage. Navigation is cancelled;
      // the next attempt can retry authentication/refresh.
      return false;
    }
    await this.login();
    return false;
  }

  isAuthenticated(): boolean {
    return !!this.token() || this.hasUsableRefreshToken();
  }

  async login(returnUrl?: string): Promise<void> {
    if (this.loginRedirectInProgress) return;
    this.loginRedirectInProgress = true;

    const verifier = this.randomBase64Url(64);
    const state = this.randomBase64Url(32);
    const nonce = this.randomBase64Url(32);
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
    const challenge = this.bytesToBase64Url(new Uint8Array(digest));

    sessionStorage.setItem(this.verifierKey, verifier);
    sessionStorage.setItem(this.stateKey, state);
    sessionStorage.setItem(this.nonceKey, nonce);
    sessionStorage.setItem(this.returnUrlKey, this.safeReturnUrl(returnUrl ?? this.currentReturnUrl()));

    const p = new URLSearchParams({
      response_type: 'code',
      response_mode: 'query',
      client_id: environment.oidc.clientId,
      redirect_uri: environment.oidc.redirectUri,
      scope: environment.oidc.scope,
      state,
      nonce,
      code_challenge: challenge,
      code_challenge_method: 'S256'
    });
    window.location.assign(`${environment.oidc.authorizationEndpoint}?${p}`);
  }

  async handleCallback(): Promise<void> {
    const query = new URLSearchParams(window.location.search);
    const error = query.get('error');
    if (error) throw new Error(`OIDC authorization failed: ${error}`);

    const code = query.get('code');
    const state = query.get('state');
    const expectedState = sessionStorage.getItem(this.stateKey);
    const expectedNonce = sessionStorage.getItem(this.nonceKey);
    const verifier = sessionStorage.getItem(this.verifierKey);
    if (!code || !state || !expectedState || state !== expectedState || !expectedNonce || !verifier) {
      throw new Error('OIDC callback state/PKCE validation failed');
    }

    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      client_id: environment.oidc.clientId,
      redirect_uri: environment.oidc.redirectUri,
      code_verifier: verifier
    });

    const response = await fetch(environment.oidc.tokenEndpoint, {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body,
      cache: 'no-store'
    });
    if (!response.ok) throw new Error(`OIDC token exchange failed (${response.status})`);

    const token = await response.json() as OidcTokenResponse;
    if (!token.access_token || !token.id_token) {
      throw new Error('OIDC token response did not contain the required access_token/id_token');
    }

    const idPayload = this.parseJwt(token.id_token);
    if (!idPayload || idPayload.nonce !== expectedNonce) {
      throw new Error('OIDC ID token nonce validation failed');
    }

    this.storeTokenResponse(token, true);
    this.clearAuthorizationCorrelation();
    this.loginRedirectInProgress = false;
  }

  /**
   * Refresh the user's token. Concurrent HTTP/SSE callers share a single refresh request,
   * which is required when Keycloak refresh-token rotation is enabled.
   */
  async refreshAccessToken(force = false): Promise<string | null> {
    if (!force && this.accessToken && !this.isExpiringWithin(this.accessExpiresAt, 30)) {
      return this.accessToken;
    }
    if (this.refreshPromise) return this.refreshPromise;

    this.refreshPromise = this.doRefresh().finally(() => {
      this.refreshPromise = undefined;
    });
    return this.refreshPromise;
  }

  consumeReturnUrl(fallback: string): string {
    const stored = sessionStorage.getItem(this.returnUrlKey);
    sessionStorage.removeItem(this.returnUrlKey);
    return stored ? this.safeReturnUrl(stored) : fallback;
  }

  async reauthenticate(): Promise<void> {
    this.clearTokens();
    await this.login();
  }

  logout(): void {
    const idToken = this.idToken;
    this.clear();
    const p = new URLSearchParams({
      post_logout_redirect_uri: environment.oidc.postLogoutRedirectUri,
      client_id: environment.oidc.clientId
    });
    if (idToken) p.set('id_token_hint', idToken);
    window.location.assign(`${environment.oidc.endSessionEndpoint}?${p}`);
  }

  clear(): void {
    this.clearTokens();
    this.clearAuthorizationCorrelation();
    sessionStorage.removeItem(this.returnUrlKey);
    this.loginRedirectInProgress = false;
  }

  private async doRefresh(): Promise<string | null> {
    if (!this.refreshToken || !this.hasUsableRefreshToken()) {
      this.clearTokens();
      return null;
    }

    const body = new URLSearchParams({
      grant_type: 'refresh_token',
      client_id: environment.oidc.clientId,
      refresh_token: this.refreshToken
    });

    let response: Response;
    try {
      response = await fetch(environment.oidc.tokenEndpoint, {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body,
        cache: 'no-store'
      });
    } catch (error) {
      throw new Error(`OIDC refresh request failed: ${error instanceof Error ? error.message : 'network error'}`);
    }

    if (!response.ok) {
      // invalid_grant/invalid_client means this browser session cannot be renewed. 5xx is
      // treated as transient so a short Keycloak outage does not destroy the session.
      if (response.status === 400 || response.status === 401) {
        this.clearTokens();
        return null;
      }
      throw new Error(`OIDC refresh failed (${response.status})`);
    }

    const token = await response.json() as OidcTokenResponse;
    if (!token.access_token) {
      this.clearTokens();
      return null;
    }
    this.storeTokenResponse(token, false);
    return token.access_token;
  }

  private storeTokenResponse(token: OidcTokenResponse, initialExchange: boolean): void {
    if (!token.access_token) throw new Error('Missing access token');
    this.accessToken = token.access_token;
    this.accessExpiresAt = this.expiryFromResponseOrJwt(token.access_token, token.expires_in);

    if (token.id_token) this.idToken = token.id_token;

    // Keycloak can rotate refresh tokens. Always retain the newest value. If rotation is
    // suppressed and a refresh response omits refresh_token, preserve the existing token.
    if (token.refresh_token) {
      this.refreshToken = token.refresh_token;
      this.refreshExpiresAt = this.expiryFromResponseOrJwt(token.refresh_token, token.refresh_expires_in);
    } else if (initialExchange) {
      this.refreshToken = undefined;
      this.refreshExpiresAt = undefined;
    }

    this.scheduleProactiveRefresh(token.expires_in, this.accessExpiresAt);
  }

  private scheduleProactiveRefresh(expiresIn?: number, expiresAt?: number): void {
    if (this.refreshTimer !== undefined) window.clearTimeout(this.refreshTimer);
    this.refreshTimer = undefined;
    if (!this.hasUsableRefreshToken()) return;

    const now = Date.now();
    const lifetimeMs = expiresIn && expiresIn > 0
      ? expiresIn * 1000
      : (expiresAt ? Math.max(expiresAt - now, 0) : 0);
    if (!lifetimeMs) return;

    // Refresh roughly 20% before expiry (maximum one minute early). The interceptor also
    // refreshes before requests, so timer throttling in background tabs is harmless.
    const leadMs = Math.min(60_000, Math.max(5_000, Math.floor(lifetimeMs * 0.20)));
    const delayMs = Math.max(5_000, lifetimeMs - leadMs);
    this.refreshTimer = window.setTimeout(() => {
      void this.refreshAccessToken(true)
        .then(token => {
          if (!token && !this.loginRedirectInProgress) void this.login();
        })
        .catch(() => {
          // Preserve the session on transient failures and retry once shortly. Normal API
          // traffic will independently attempt refresh through validToken().
          if (this.hasUsableRefreshToken()) {
            this.refreshTimer = window.setTimeout(() => void this.refreshAccessToken(true).catch(() => undefined), 15_000);
          }
        });
    }, delayMs);
  }

  private clearTokens(): void {
    if (this.refreshTimer !== undefined) window.clearTimeout(this.refreshTimer);
    this.refreshTimer = undefined;
    this.accessToken = undefined;
    this.refreshToken = undefined;
    this.idToken = undefined;
    this.accessExpiresAt = undefined;
    this.refreshExpiresAt = undefined;
  }

  private clearAuthorizationCorrelation(): void {
    sessionStorage.removeItem(this.verifierKey);
    sessionStorage.removeItem(this.stateKey);
    sessionStorage.removeItem(this.nonceKey);
  }

  private hasUsableRefreshToken(): boolean {
    return !!this.refreshToken && !this.isExpired(this.refreshExpiresAt);
  }

  private expiryFromResponseOrJwt(token: string, expiresIn?: number): number | undefined {
    if (typeof expiresIn === 'number' && Number.isFinite(expiresIn) && expiresIn > 0) {
      return Date.now() + expiresIn * 1000;
    }
    const payload = this.parseJwt(token);
    return payload?.exp ? Number(payload.exp) * 1000 : undefined;
  }

  private isExpiringWithin(expiry: number | undefined, seconds: number): boolean {
    if (!expiry) return true;
    return Date.now() + seconds * 1000 >= expiry;
  }

  private isExpired(expiry: number | undefined): boolean {
    if (!expiry) return true;
    return Date.now() >= expiry;
  }

  private parseJwt(token: string): JwtPayload | undefined {
    try {
      const parts = token.split('.');
      if (parts.length < 2) return undefined;
      return JSON.parse(this.decodeBase64Url(parts[1])) as JwtPayload;
    } catch {
      return undefined;
    }
  }

  private currentReturnUrl(): string {
    const path = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    return path.startsWith('/auth/callback') ? '/dashboard' : path;
  }

  private safeReturnUrl(value: string): string {
    return value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/auth/callback') ? value : '/dashboard';
  }

  private randomBase64Url(bytes: number): string {
    const data = new Uint8Array(bytes);
    crypto.getRandomValues(data);
    return this.bytesToBase64Url(data);
  }

  private bytesToBase64Url(data: Uint8Array): string {
    let binary = '';
    data.forEach(b => binary += String.fromCharCode(b));
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
  }

  private decodeBase64Url(value: string): string {
    const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4);
    return atob(padded);
  }
}
