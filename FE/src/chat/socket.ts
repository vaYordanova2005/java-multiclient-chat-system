import { apiBase } from '../api/client';
import type { WireMessage } from './types';

export interface ChatSocketHandlers {
  onMessage: (msg: WireMessage) => void;
  onOpen?: () => void;
  onClose?: (event: CloseEvent) => void;
}

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
  private ws: WebSocket;

  constructor(token: string, handlers: ChatSocketHandlers) {
    this.ws = new WebSocket(`${wsOrigin()}/ws?token=${encodeURIComponent(token)}`);

    this.ws.onopen = () => handlers.onOpen?.();
    this.ws.onclose = (event) => handlers.onClose?.(event);
    this.ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data) as WireMessage;
        handlers.onMessage(msg);
      } catch {
        // Malformed frame — ignore, matches legacy's silent drop on parse failure.
      }
    };
  }

  send(payload: WireMessage) {
    if (this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(payload));
    }
  }

  close() {
    this.ws.close();
  }
}
