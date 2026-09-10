import { useEffect, useRef, useState, useCallback } from 'react';
import { ChatSocket } from './socket';
import { decodeExpiry } from '../auth/token';
import {
  GLOBAL_ROOM,
  dmRoomKey,
  otherDmUser,
  type WireMessage,
  type FriendInfo,
  type SearchResult,
  type ThemePreferences,
  type ProfileInfo,
  type DmConversationsPayload,
} from './types';

export interface Notice {
  id: number;
  text: string;
  tone: 'success' | 'error' | 'info';
}

function toneFromText(text: string): Notice['tone'] {
  if (text.startsWith('✅')) return 'success';
  if (text.startsWith('❌')) return 'error';
  return 'info';
}

let noticeSeq = 0;
let messageSeq = 0;

// Wraps WireMessage with a client-side monotonic id so list rendering has a
// stable React key — `msg` fields alone can collide (two "message" events
// with no timestamp) and an array index breaks the moment history is
// prepended (e.g. pagination) instead of only ever appended.
export interface DisplayMessage extends WireMessage {
  _id: number;
}

interface UseChatOptions {
  token: string;
  username: string;
  onUsernameChanged: (newUsername: string, newToken: string) => void;
  onAccountDeleted: () => void;
  // Fires only once ChatSocket has decoded the token's own exp and confirmed
  // it's actually expired — see socket.ts. A merely unreachable BE (or a
  // forced disconnect from logging in elsewhere) never reaches this; those
  // just show up via `connected` while ChatSocket keeps retrying.
  onAuthFailed: () => void;
}

export type ChatController = ReturnType<typeof useChat>;

export function useChat({ token, username, onUsernameChanged, onAccountDeleted, onAuthFailed }: UseChatOptions) {
  const [connected, setConnected] = useState(false);
  const [currentRoom, setCurrentRoom] = useState(GLOBAL_ROOM);
  const [messages, setMessages] = useState<DisplayMessage[]>([]);
  const [dmPartners, setDmPartners] = useState<string[]>([]);
  const [friends, setFriends] = useState<FriendInfo[]>([]);
  const [blocked, setBlocked] = useState<string[]>([]);
  const [pending, setPending] = useState<string[]>([]);
  const [onlineUsers, setOnlineUsers] = useState<string[]>([]);
  const [peerAvatars, setPeerAvatars] = useState<Record<string, string>>({});
  const [unread, setUnread] = useState<Record<string, number>>({});
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [theme, setThemeState] = useState<ThemePreferences>({
    bubbleThemeId: 'solid_periwinkle',
    backgroundThemeId: 'bg_ombre_3',
    uiThemeId: 'ui_lilac',
  });
  const [profile, setProfile] = useState<ProfileInfo | null>(null);
  const [notices, setNotices] = useState<Notice[]>([]);
  // Bumped only by the two message types that are actually "a settings
  // request got a reply" (username_changed/error) — unlike `notices`, it
  // doesn't also tick over when an unrelated notice's 4s auto-dismiss timer
  // fires, which would otherwise let an in-flight "Saving..." get cleared by
  // a completely unrelated toast expiring.
  const [responseSeq, setResponseSeq] = useState(0);

  const socketRef = useRef<ChatSocket | null>(null);
  const currentRoomRef = useRef(currentRoom);
  const usernameRef = useRef(username);
  const themeRef = useRef(theme);
  // Set right after a *reconnect* (not the first connect) when the room
  // we're actually viewing isn't "global" — see the onOpen handler below.
  // ClientHandler.start() unconditionally reloads "global" history (and
  // rebroadcasts a join notice) on every fresh connection before our own
  // room_join for this room can even be processed, so anything tagged for a
  // different room while this is set is that stale burst, not new activity.
  const resyncTargetRoomRef = useRef<string | null>(null);
  const resyncTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    currentRoomRef.current = currentRoom;
  }, [currentRoom]);
  useEffect(() => {
    usernameRef.current = username;
  }, [username]);
  useEffect(() => {
    themeRef.current = theme;
  }, [theme]);

  const noticeTimeouts = useRef<Map<number, ReturnType<typeof setTimeout>>>(new Map());

  const pushNotice = useCallback((text: string) => {
    const id = ++noticeSeq;
    setNotices((prev) => [...prev, { id, text, tone: toneFromText(text) }]);
    const timeout = setTimeout(() => {
      setNotices((prev) => prev.filter((n) => n.id !== id));
      noticeTimeouts.current.delete(id);
    }, 4000);
    noticeTimeouts.current.set(id, timeout);
  }, []);

  useEffect(() => {
    const timeouts = noticeTimeouts.current;
    return () => {
      timeouts.forEach(clearTimeout);
      timeouts.clear();
    };
  }, []);

  const appendMessage = useCallback((msg: WireMessage) => {
    setMessages((prev) => [...prev, { ...msg, _id: ++messageSeq }]);
  }, []);

  const incrementUnread = useCallback((room: string) => {
    if (room === currentRoomRef.current) return;
    setUnread((prev) => ({ ...prev, [room]: (prev[room] ?? 0) + 1 }));
  }, []);

  const clearUnread = useCallback((room: string) => {
    setUnread((prev) => {
      if (!(room in prev)) return prev;
      const next = { ...prev };
      delete next[room];
      return next;
    });
  }, []);

  const addDmPartner = useCallback((partner: string) => {
    setDmPartners((prev) => (prev.includes(partner) ? prev : [...prev, partner]));
  }, []);

  // True while `msg` should be treated as the stale post-reconnect "global"
  // burst rather than real activity (see resyncTargetRoomRef above) — drop
  // it outright instead of appending it into the wrong room or inflating
  // that room's unread badge. Clears itself the moment the target room's
  // own data starts arriving, which is also a signal worth reacting to
  // (nothing further to absorb), not just a check.
  const absorbDuringResync = useCallback((msg: WireMessage): boolean => {
    const target = resyncTargetRoomRef.current;
    // Roomless pushes (e.g. a direct "X accepted your friend request"
    // notice) aren't part of the per-room reload burst at all — never
    // absorb those, regardless of resync state.
    if (!target || !msg.room) return false;
    if (msg.room === target) {
      resyncTargetRoomRef.current = null;
      if (resyncTimeoutRef.current) {
        clearTimeout(resyncTimeoutRef.current);
        resyncTimeoutRef.current = null;
      }
      return false;
    }
    return true;
  }, []);

  // ── Incoming message dispatch — mirrors legacy/Main.java's handleServerMessage ──
  const handleServerMessage = useCallback(
    (msg: WireMessage) => {
      const self = usernameRef.current;

      switch (msg.type) {
        case 'online_users': {
          const list: string[] = msg.text ? JSON.parse(msg.text) : [];
          setOnlineUsers(list);
          return;
        }
        case 'avatar_directory': {
          const map: Record<string, string> = msg.text ? JSON.parse(msg.text) : {};
          setPeerAvatars((prev) => ({ ...prev, ...map }));
          return;
        }
        case 'friend_list': {
          const list: FriendInfo[] = msg.text ? JSON.parse(msg.text) : [];
          setFriends(list);
          return;
        }
        case 'pending_requests': {
          const list: string[] = msg.text ? JSON.parse(msg.text) : [];
          setPending(list);
          return;
        }
        case 'user_search_result': {
          const list: SearchResult[] = msg.text ? JSON.parse(msg.text) : [];
          setSearchResults(list);
          return;
        }
        case 'theme_update': {
          if (!msg.text) return;
          const prefs: ThemePreferences = JSON.parse(msg.text);
          setThemeState(prefs);
          return;
        }
        case 'blocked_list': {
          const list: string[] = msg.text ? JSON.parse(msg.text) : [];
          setBlocked(list);
          return;
        }
        case 'dm_conversations': {
          if (!msg.text) return;
          const payload: DmConversationsPayload = JSON.parse(msg.text);
          if (payload.avatars) setPeerAvatars((prev) => ({ ...prev, ...payload.avatars }));
          if (payload.partners) setDmPartners((prev) => [...new Set([...prev, ...payload.partners])]);
          return;
        }
        case 'profile_info': {
          if (!msg.text) return;
          setProfile(JSON.parse(msg.text));
          return;
        }
        case 'username_changed': {
          if (msg.text && msg.token) {
            onUsernameChanged(msg.text, msg.token);
            pushNotice(`✅ Username changed to ${msg.text}`);
          }
          setResponseSeq((n) => n + 1);
          return;
        }
        case 'account_deleted': {
          onAccountDeleted();
          return;
        }
        case 'error': {
          if (msg.text) pushNotice(msg.text);
          setResponseSeq((n) => n + 1);
          return;
        }
        case 'system': {
          if (absorbDuringResync(msg)) return;
          // Room-scoped (join/leave, via broadcastToRoom) only ever
          // disagrees with currentRoomRef during the resync window above;
          // roomless ones (e.g. a direct "friend request accepted" notice)
          // are always relevant regardless of the active room.
          if (!msg.room || msg.room === currentRoomRef.current) appendMessage(msg);
          return;
        }
        case 'dm': {
          if (absorbDuringResync(msg)) return;
          const otherUser = msg.user === self ? msg.receiver! : msg.user!;
          addDmPartner(otherUser);
          const roomKey = dmRoomKey(self, otherUser);
          if (msg.room && msg.room === currentRoomRef.current) {
            appendMessage(msg);
          } else if (msg.user !== self) {
            incrementUnread(roomKey);
          }
          return;
        }
        default: {
          if (absorbDuringResync(msg)) return;
          // "message" and anything else room-scoped.
          if (msg.room && msg.room === currentRoomRef.current) {
            appendMessage(msg);
          } else if (msg.room) {
            incrementUnread(msg.room);
          }
        }
      }
    },
    [absorbDuringResync, addDmPartner, appendMessage, incrementUnread, onAccountDeleted, onUsernameChanged, pushNotice],
  );

  // handleServerMessage/onAuthFailed close over per-render state (current
  // room, etc.) and would otherwise force the socket to be torn down and
  // reopened on every render if listed as effect deps. Routing calls through
  // a ref keeps the effect scoped to just `token` without going stale.
  const handleServerMessageRef = useRef(handleServerMessage);
  useEffect(() => {
    handleServerMessageRef.current = handleServerMessage;
  }, [handleServerMessage]);

  const onAuthFailedRef = useRef(onAuthFailed);
  useEffect(() => {
    onAuthFailedRef.current = onAuthFailed;
  }, [onAuthFailed]);

  // ChatSocket only re-checks expiry on its own close/reconnect — a socket
  // that stays open the whole time (TokenService.verify only runs at
  // handshake, not per-message) would otherwise sail past `exp` unnoticed
  // until something else happens to disconnect it. Schedule a timer for the
  // token's own expiry directly so a still-open connection gets logged out
  // right on time instead of on the next unrelated hiccup.
  useEffect(() => {
    const exp = decodeExpiry(token);
    if (exp === null) return;
    const delay = exp - Date.now();
    if (delay <= 0) {
      onAuthFailedRef.current();
      return;
    }
    const timer = setTimeout(() => onAuthFailedRef.current(), delay);
    return () => clearTimeout(timer);
  }, [token]);

  useEffect(() => {
    // Reconnect-with-backoff and the expiry-based auth-failure check both
    // live inside ChatSocket now (see socket.ts) — a plain close/error here
    // says nothing about *why* the connection dropped (dead BE, sleeping
    // laptop, and a genuinely rejected token all look identical), so this
    // hook just reflects `connected` and defers to ChatSocket's own signal.
    let hadConnectedBefore = false;
    const socket = new ChatSocket(token, {
      onOpen: () => {
        setConnected(true);
        if (hadConnectedBefore) {
          // ClientHandler.start() runs the exact same "reset to global,
          // reload its history, rebroadcast a join notice" sequence on
          // every fresh connection, with no memory of where this client
          // actually was — clear the stale view and resync instead of
          // appending a second copy of everything on top of it.
          setMessages([]);
          const room = currentRoomRef.current;
          if (room !== GLOBAL_ROOM) {
            // BE's own auto-reload above already targeted "global", not
            // this room — ask for the right one. absorbDuringResync drops
            // that now-irrelevant "global" burst instead of it leaking
            // into this room's view or unread count while we wait.
            resyncTargetRoomRef.current = room;
            if (resyncTimeoutRef.current) clearTimeout(resyncTimeoutRef.current);
            resyncTimeoutRef.current = setTimeout(() => {
              resyncTargetRoomRef.current = null;
              resyncTimeoutRef.current = null;
            }, 5000);
            socket.send({ type: 'room_join', room });
          }
        }
        hadConnectedBefore = true;
      },
      onClose: () => setConnected(false),
      onMessage: (msg) => handleServerMessageRef.current(msg),
      onAuthFailed: () => onAuthFailedRef.current(),
    });
    socketRef.current = socket;
    return () => {
      socket.close();
      if (resyncTimeoutRef.current) {
        clearTimeout(resyncTimeoutRef.current);
        resyncTimeoutRef.current = null;
      }
      resyncTargetRoomRef.current = null;
    };
  }, [token]);

  const send = useCallback((payload: WireMessage) => socketRef.current?.send(payload), []);

  const changeRoom = useCallback(
    (room: string) => {
      if (room === currentRoomRef.current) return;
      // A manual switch supersedes any pending post-reconnect resync.
      resyncTargetRoomRef.current = null;
      if (resyncTimeoutRef.current) {
        clearTimeout(resyncTimeoutRef.current);
        resyncTimeoutRef.current = null;
      }
      currentRoomRef.current = room;
      setCurrentRoom(room);
      setMessages([]);
      clearUnread(room);
      send({ type: 'room_join', room });
    },
    [clearUnread, send],
  );

  const switchToGlobal = useCallback(() => changeRoom(GLOBAL_ROOM), [changeRoom]);

  const openDM = useCallback(
    (otherUser: string) => {
      if (otherUser === usernameRef.current) return;
      addDmPartner(otherUser);
      changeRoom(dmRoomKey(usernameRef.current, otherUser));
    },
    [addDmPartner, changeRoom],
  );

  const sendChatMessage = useCallback(
    (text: string) => {
      const trimmed = text.trim();
      if (!trimmed) return;
      const room = currentRoomRef.current;
      if (room.startsWith('dm_')) {
        const receiver = otherDmUser(room, usernameRef.current);
        send({ type: 'dm', text: trimmed, receiver, room });
      } else {
        send({ type: 'message', text: trimmed, room });
      }
    },
    [send],
  );

  const search = useCallback(
    (query: string) => {
      const trimmed = query.trim();
      if (!trimmed) {
        setSearchResults([]);
        return;
      }
      send({ type: 'search_users', text: trimmed });
    },
    [send],
  );

  const sendFriendRequest = useCallback((user: string) => send({ type: 'friend_request', receiver: user }), [send]);

  const respondFriendRequest = useCallback(
    (requester: string, accept: boolean) =>
      send({ type: 'friend_response', receiver: requester, text: accept ? 'accept' : 'decline' }),
    [send],
  );

  const saveTheme = useCallback(
    (next: Partial<ThemePreferences>) => {
      const merged = { ...themeRef.current, ...next };
      themeRef.current = merged;
      setThemeState(merged);
      send({ type: 'set_theme', text: `${merged.bubbleThemeId}|${merged.backgroundThemeId}|${merged.uiThemeId}` });
    },
    [send],
  );

  const setAvatar = useCallback(
    (avatarId: string | null) => {
      setProfile((prev) => (prev ? { ...prev, avatarId } : prev));
      send({ type: 'set_avatar', text: avatarId ?? '' });
    },
    [send],
  );

  const setOnlineVisibility = useCallback(
    (visible: boolean) => {
      setProfile((prev) => (prev ? { ...prev, showOnlineStatus: visible } : prev));
      send({ type: 'set_online_visibility', text: String(visible) });
    },
    [send],
  );

  const blockUser = useCallback((user: string) => send({ type: 'block_user', receiver: user }), [send]);
  const unblockUser = useCallback((user: string) => send({ type: 'unblock_user', receiver: user }), [send]);
  const changeUsername = useCallback((name: string) => send({ type: 'change_username', text: name }), [send]);
  const deleteAccount = useCallback((password: string) => send({ type: 'delete_account', text: password }), [send]);

  return {
    connected,
    currentRoom,
    messages,
    dmPartners,
    friends,
    blocked,
    pending,
    onlineUsers,
    peerAvatars,
    unread,
    searchResults,
    theme,
    profile,
    notices,
    responseSeq,
    switchToGlobal,
    openDM,
    sendChatMessage,
    search,
    sendFriendRequest,
    respondFriendRequest,
    saveTheme,
    setAvatar,
    setOnlineVisibility,
    blockUser,
    unblockUser,
    changeUsername,
    deleteAccount,
  };
}
