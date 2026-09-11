import { apiBase } from '../api/client';
import { isTokenExpired } from '../auth/token';
import type { WireMessage } from './types';

export interface ChatSocketHandlers {
  onMessage: (msg: WireMessage) => void;
  onOpen?: () => void;
  onClose?: () => void;
  // Fires once we've positively confirmed (by decoding the token's own exp,
  // not by the mere fact that a connection attempt failed) that the session
  // is dead and retrying is pointless. A close/error event on its own is
  // NOT enough signal — the browser reports the exact same close (no onopen,
  // code 1006) whether the handshake was rejected for an expired token, the
  // BE is down, a dev proxy dropped, or the machine just woke from sleep.
  onAuthFailed?: () => void;
}

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30000;

// Derives the WS origin from the same VITE_API_BASE_URL used for REST calls
// (see api/client.ts), instead of always assuming same-origin — a split
// deploy (FE served separately from BE) would otherwise send WS traffic to
// the FE's own host, which has no /ws endpoint.
function wsOrigin(): string {
  const base = apiBase();
  if (!base) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${window.location.host}`;
  }
  const url = new URL(base);
  const protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${url.host}`;
}

export class ChatSocket {
  private ws: WebSocket | null = null;
  private readonly token: string;
  private readonly handlers: ChatSocketHandlers;
  private backoff = INITIAL_BACKOFF_MS;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private destroyed = false;

  constructor(token: string, handlers: ChatSocketHandlers) {
    this.token = token;
    this.handlers = handlers;
    this.connect();
  }

  private connect() {
    // Checked before every attempt (not just the first) — a long backoff
    // wait or a laptop sleep can cross the token's expiry while we were
    // busy retrying.
    if (isTokenExpired(this.token)) {
      this.handlers.onAuthFailed?.();
      return;
    }

    // Carried as the Sec-WebSocket-Protocol request header (via the
    // constructor's second arg) instead of a "?token=" query param — the
    // query string is the thing proxy/server access logs record by default,
    // and this header isn't. Server side: TokenAuthHandshakeInterceptor
    // reads it the same way. Safe as a subprotocol token: TokenService's
    // base64url(payload) + "." + base64url(signature) format only ever uses
    // [A-Za-z0-9_-.], all valid per the HTTP token grammar the WebSocket
    // handshake requires here.
    const ws = new WebSocket(`${wsOrigin()}/ws`, [this.token]);
    this.ws = ws;

    ws.onopen = () => {
      this.backoff = INITIAL_BACKOFF_MS;
      this.handlers.onOpen?.();
    };

    ws.onclose = () => {
      this.handlers.onClose?.();
      if (this.destroyed) return;

      if (isTokenExpired(this.token)) {
        this.handlers.onAuthFailed?.();
        return;
      }

      // Jitter the actual delay (not the stored backoff, so growth stays
      // predictable) — otherwise every client reconnects in lockstep the
      // moment a restarted BE comes back up.
      const delay = this.backoff * (0.5 + Math.random() * 0.5);
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = null;
        this.connect();
      }, delay);
      this.backoff = Math.min(this.backoff * 2, MAX_BACKOFF_MS);
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data) as WireMessage;
        this.handlers.onMessage(msg);
      } catch {
        // Malformed frame — ignore, matches legacy's silent drop on parse failure.
      }
    };
  }

  send(payload: WireMessage) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(payload));
    }
  }

  close() {
    this.destroyed = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.ws?.close();
  }
}
