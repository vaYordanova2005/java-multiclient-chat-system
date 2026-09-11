import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import type { ChatSocketHandlers } from './socket';
import { otherDmUser, dmRoomKey, GLOBAL_ROOM, type WireMessage } from './types';

// vi.mock is hoisted above imports, so the fake it returns can't close over
// module-scope variables declared normally — vi.hoisted runs first and gives
// us a handle both this file and the mock factory can share.
const { FakeChatSocket, instances } = vi.hoisted(() => {
  class FakeChatSocket {
    static instances: FakeChatSocket[] = [];
    token: string;
    handlers: ChatSocketHandlers;
    sent: unknown[] = [];
    closed = false;

    constructor(token: string, handlers: ChatSocketHandlers) {
      this.token = token;
      this.handlers = handlers;
      FakeChatSocket.instances.push(this);
    }

    send(payload: unknown) {
      this.sent.push(payload);
    }

    close() {
      this.closed = true;
    }
  }
  return { FakeChatSocket, instances: FakeChatSocket.instances };
});

vi.mock('./socket', () => ({ ChatSocket: FakeChatSocket }));

// Dynamically imported AFTER the mock is registered above — a static
// top-level import would resolve './socket' before vi.mock has a chance to
// intercept it.
const { useChat } = await import('./useChat');

const ME = 'alice';
const DEFAULT_THEME = { bubbleThemeId: 'b', backgroundThemeId: 'bg', uiThemeId: 'ui' };

function makeToken(username: string, expMs = Date.now() + 3_600_000): string {
  const payload = btoa(JSON.stringify({ username, exp: expMs })).replace(/\+/g, '-').replace(/\//g, '_');
  return `${payload}.sig`;
}

function renderChat() {
  // Computed once per test, outside the render callback — the callback re-runs
  // on every re-render like a component body would, and makeToken's default
  // `expMs` (Date.now()) makes each call produce a distinct token string.
  // A `token` that changes on every render defeats its purpose as a stable
  // effect dependency: useChat's socket effect tears down and rebuilds on
  // every single state update instead of once per real login.
  const token = makeToken(ME);
  const hook = renderHook(() =>
    useChat({
      token,
      username: ME,
      defaultTheme: DEFAULT_THEME,
      onUsernameChanged: vi.fn(),
      onAccountDeleted: vi.fn(),
      onAuthFailed: vi.fn(),
    }),
  );
  const socket = instances[instances.length - 1]!;
  return { ...hook, socket };
}

function push(socket: InstanceType<typeof FakeChatSocket>, msg: WireMessage) {
  act(() => socket.handlers.onMessage(msg));
}

beforeEach(() => {
  instances.length = 0;
});

describe('otherDmUser', () => {
  it('returns the side that is not `self`', () => {
    const room = dmRoomKey('alice', 'bob');
    expect(otherDmUser(room, 'alice')).toBe('bob');
    expect(otherDmUser(room, 'bob')).toBe('alice');
  });

  it('returns "" for a room with no second underscore', () => {
    expect(otherDmUser('dm_alice', 'alice')).toBe('');
  });

  it('returns "" if either side is empty (e.g. "dm__bob")', () => {
    expect(otherDmUser('dm__bob', 'alice')).toBe('');
  });
});

describe('useChat — message dedupe', () => {
  it('drops a second message with an identical signature', () => {
    const { result, socket } = renderChat();
    const msg: WireMessage = { type: 'message', user: 'bob', text: 'hi', timestamp: 't1', room: GLOBAL_ROOM };

    push(socket, msg);
    push(socket, { ...msg });

    expect(result.current.messages).toHaveLength(1);
  });

  it('keeps two messages that only differ by timestamp', () => {
    const { result, socket } = renderChat();
    push(socket, { type: 'message', user: 'bob', text: 'hi', timestamp: 't1', room: GLOBAL_ROOM });
    push(socket, { type: 'message', user: 'bob', text: 'hi', timestamp: 't2', room: GLOBAL_ROOM });

    expect(result.current.messages).toHaveLength(2);
  });
});

describe('useChat — stale room absorption', () => {
  it('drops a late reply for a room that was abandoned by switching away', () => {
    const { result, socket } = renderChat();
    const bobRoom = dmRoomKey(ME, 'bob');
    const carolRoom = dmRoomKey(ME, 'carol');

    act(() => result.current.openDM('bob'));
    // Switching again before bob's room_join reply ever arrives marks
    // bobRoom stale (see requestRoomJoin in useChat.ts).
    act(() => result.current.openDM('carol'));
    expect(result.current.currentRoom).toBe(carolRoom);

    // bob's belated reply lands after we've already moved on.
    push(socket, { type: 'dm', user: 'bob', receiver: ME, text: 'late reply', room: bobRoom });

    expect(result.current.messages.some((m) => m.text === 'late reply')).toBe(false);
    expect(result.current.unread[bobRoom] ?? 0).toBe(0);
  });

  it('does not mark a room stale on its first-ever join', () => {
    const { result, socket } = renderChat();
    const bobRoom = dmRoomKey(ME, 'bob');

    act(() => result.current.openDM('bob'));
    push(socket, { type: 'dm', user: 'bob', receiver: ME, text: 'hi', room: bobRoom });

    expect(result.current.messages.some((m) => m.text === 'hi')).toBe(true);
  });
});

describe('useChat — reconnect resync', () => {
  it('re-requests the active non-global room and absorbs the BE\'s auto "global" reload', () => {
    const { result, socket } = renderChat();
    const bobRoom = dmRoomKey(ME, 'bob');

    // First connect: ClientHandler.start() always loads "global" server-side
    // regardless of what room we go on to open, so this matches reality —
    // nothing to resync yet, hadConnectedBefore just flips true.
    act(() => socket.handlers.onOpen?.());
    act(() => result.current.openDM('bob'));
    socket.sent.length = 0; // only care about what the *reconnect* sends

    // Reconnect on the same ChatSocket instance (its own backoff/retry, not
    // a brand-new socket) while sitting in bob's room.
    act(() => socket.handlers.onOpen?.());

    expect(socket.sent).toContainEqual({ type: 'room_join', room: bobRoom });

    // BE unconditionally re-broadcasts "global"'s own reload on every fresh
    // connection — that unsolicited burst must be swallowed, not appended
    // on top of bob's room.
    push(socket, { type: 'message', user: 'carol', text: 'unsolicited global reload', room: GLOBAL_ROOM });
    expect(result.current.messages.some((m) => m.text === 'unsolicited global reload')).toBe(false);

    // ...while bob's actual resync reply still comes through normally.
    push(socket, { type: 'dm', user: 'bob', receiver: ME, text: 'resynced', room: bobRoom });
    expect(result.current.messages.some((m) => m.text === 'resynced')).toBe(true);
  });

  it('does not resync on the very first connect (nothing to resync yet)', () => {
    const { socket } = renderChat();
    act(() => socket.handlers.onOpen?.());
    expect(socket.sent).toEqual([]);
  });
});
