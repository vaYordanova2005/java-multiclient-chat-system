import type { WireMessage } from './types';

export interface ChatSocketHandlers {
  onMessage: (msg: WireMessage) => void;
  onOpen?: () => void;
  onClose?: (event: CloseEvent) => void;
}

export class ChatSocket {
  private ws: WebSocket;

  constructor(token: string, handlers: ChatSocketHandlers) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.ws = new WebSocket(`${protocol}//${window.location.host}/ws?token=${encodeURIComponent(token)}`);

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
