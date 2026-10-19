const runtime = (window as any).__PAYMENT_PORTAL_CONFIG__ || {};
const oidcBaseUrl = runtime.oidcBaseUrl || `${window.location.protocol}//${window.location.hostname}:8180`;

export const environment = {
  oidc: {
    authorizationEndpoint: `${oidcBaseUrl}/realms/payment-gateway/protocol/openid-connect/auth`,
    tokenEndpoint: `${oidcBaseUrl}/realms/payment-gateway/protocol/openid-connect/token`,
    endSessionEndpoint: `${oidcBaseUrl}/realms/payment-gateway/protocol/openid-connect/logout`,
    clientId: 'payment-merchant-portal',
    scope: 'openid profile email',
    redirectUri: `${window.location.origin}/auth/callback`,
    postLogoutRedirectUri: `${window.location.origin}/`
  }
};
