import {Injectable} from '@angular/core';
import {BehaviorSubject, Subject} from 'rxjs';
import {OidcAuthService} from '../auth/oidc-auth.service';

export interface PortalLiveEvent {
  eventId?: string;
  type: string;
  merchantId?: string;
  paymentId?: string;
  occurredAt?: string;
  data?: Record<string, unknown>;
}

export type LiveConnectionState = 'connecting' | 'live' | 'reconnecting' | 'offline';

/**
 * Authenticated SSE client implemented with fetch streaming because native EventSource
 * cannot attach the portal's Authorization bearer token.
 *
 * The stream is reference-counted so only routes that consume live payment events keep
 * the long-lived HTTP request open. This is especially important for local HTTP/1.1
 * development where an idle SSE connection can consume one of the browser's limited
 * per-origin connections and stall ordinary portal API requests.
 */
@Injectable({providedIn:'root'})
export class PortalLiveService {
  private readonly eventSubject = new Subject<PortalLiveEvent>();
  private readonly stateSubject = new BehaviorSubject<LiveConnectionState>('offline');
  private readonly errorSubject = new BehaviorSubject<string>('');
  private controller?: AbortController;
  private running = false;
  private reconnectAttempt = 0;
  private consumers = 0;
  private generation = 0;

  readonly events$ = this.eventSubject.asObservable();
  readonly state$ = this.stateSubject.asObservable();
  readonly error$ = this.errorSubject.asObservable();

  constructor(private auth: OidcAuthService) {}

  /** Acquire one live-stream lease for a route that consumes SSE updates. */
  acquire(): void {
    this.consumers++;
    if (this.consumers === 1) this.start();
  }

  /** Release one live-stream lease. The SSE request closes when no route needs it. */
  release(): void {
    if (this.consumers === 0) return;
    this.consumers--;
    if (this.consumers === 0) this.stop();
  }

  /** Backward-compatible explicit start; prefer acquire()/release() for route lifecycles. */
  connect(): void {
    this.start();
  }

  /** Force the stream closed, for example during logout. */
  disconnect(): void {
    this.consumers = 0;
    this.stop();
  }

  private start(): void {
    if (this.running) return;
    this.running = true;
    this.reconnectAttempt = 0;
    const generation = ++this.generation;
    void this.run(generation);
  }

  private stop(): void {
    this.running = false;
    this.generation++;
    this.controller?.abort();
    this.controller = undefined;
    this.reconnectAttempt = 0;
    this.stateSubject.next('offline');
  }

  private isCurrent(generation:number): boolean {
    return this.running && generation === this.generation;
  }

  private async run(generation:number): Promise<void> {
    while (this.isCurrent(generation)) {
      let token: string | null;
      try {
        token = await this.auth.validToken(45);
      } catch {
        if (!this.isCurrent(generation)) break;
        this.reconnectAttempt++;
        this.errorSubject.next('Could not obtain a valid portal access token');
        this.stateSubject.next('reconnecting');
        await this.sleep(this.reconnectDelay());
        continue;
      }

      if (!this.isCurrent(generation)) break;
      if (!token) {
        this.running = false;
        this.stateSubject.next('offline');
        await this.auth.login();
        return;
      }

      const controller = new AbortController();
      this.controller = controller;
      this.stateSubject.next(this.reconnectAttempt === 0 ? 'connecting' : 'reconnecting');
      try {
        const response = await fetch('/api/v1/portal/live/events', {
          method: 'GET',
          headers: {
            'Authorization': `Bearer ${token}`,
            'Accept': 'text/event-stream',
            'Cache-Control': 'no-cache'
          },
          cache: 'no-store',
          credentials: 'same-origin',
          signal: controller.signal
        });

        if (!this.isCurrent(generation)) break;
        if (response.status === 401) {
          const refreshed = await this.auth.refreshAccessToken(true);
          if (!this.isCurrent(generation)) break;
          if (!refreshed) {
            this.running = false;
            this.stateSubject.next('offline');
            await this.auth.reauthenticate();
            return;
          }
          this.reconnectAttempt = 0;
          continue; // immediately reconnect with the newly issued token
        }
        if (!response.ok || !response.body) {
          let detail = '';
          try { detail = (await response.text()).slice(0, 240); } catch { /* ignore */ }
          throw new Error(`Live event stream failed with HTTP ${response.status}${detail ? `: ${detail}` : ''}`);
        }

        this.reconnectAttempt = 0;
        this.errorSubject.next('');
        this.stateSubject.next('live');
        await this.consume(response.body, generation, controller);
        if (this.isCurrent(generation)) throw new Error('Live event stream ended');
      } catch (error:any) {
        if (!this.isCurrent(generation) || controller.signal.aborted) break;
        this.reconnectAttempt++;
        this.errorSubject.next(error?.message || 'Live event stream disconnected');
        this.stateSubject.next('reconnecting');
        await this.sleep(this.reconnectDelay());
      } finally {
        if (this.controller === controller) this.controller = undefined;
      }
    }
  }

  private reconnectDelay(): number {
    return Math.min(1000 * Math.pow(2, Math.min(this.reconnectAttempt - 1, 4)), 15000);
  }

  private async consume(stream: ReadableStream<Uint8Array>, generation:number, controller:AbortController): Promise<void> {
    const reader = stream.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      while (this.isCurrent(generation) && !controller.signal.aborted) {
        const {value, done} = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, {stream:true}).replace(/\r\n/g, '\n');
        let separator: number;
        while ((separator = buffer.indexOf('\n\n')) >= 0) {
          const block = buffer.slice(0, separator);
          buffer = buffer.slice(separator + 2);
          this.handleBlock(block);
        }
      }
    } finally {
      try { reader.releaseLock(); } catch { /* no-op */ }
    }
  }

  private handleBlock(block: string): void {
    if (!block || block.startsWith(':')) return;
    let eventName = 'message';
    const dataLines: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) continue;
      const colon = line.indexOf(':');
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? '' : line.slice(colon + 1);
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'event') eventName = value;
      if (field === 'data') dataLines.push(value);
    }
    if (eventName !== 'payment-status' || !dataLines.length) return;
    try {
      this.eventSubject.next(JSON.parse(dataLines.join('\n')) as PortalLiveEvent);
    } catch {
      // Ignore malformed/forward-incompatible events; periodic reconciliation remains the safety net.
    }
  }

  private sleep(ms:number): Promise<void> {
    return new Promise(resolve => window.setTimeout(resolve, ms));
  }
}
