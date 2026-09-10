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

// Content signature, not identity — the wire protocol has no stable
// per-message id (see BE/model/Message.java) to key on. Guards against a
// room's history genuinely arriving twice: e.g. switch A -> B -> A fast
// enough and clearRoomStale(A) (see requestRoomJoin) correctly un-marks A
// so real new activity in it isn't dropped — but that also means the
// still-in-flight reply to the *first*, abandoned visit to A is no longer
// stale-suppressed either, and lands right alongside the second visit's
// own reply for the exact same rows. Two distinct live messages colliding
// on this key would need the same user, text, and timestamp down to
// whatever precision the BE stamps (effectively nanoseconds) — the room
// isn't part of the key on purpose, since messages is always cleared on
// room switch anyway.
function messageSignature(msg: WireMessage): string {
  return `${msg.type}|${msg.user ?? ''}|${msg.text ?? ''}|${msg.timestamp ?? ''}|${msg.receiver ?? ''}`;
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
  // The room whose reload we're currently waiting on (set by requestRoomJoin
  // below, whether from a manual switch or a post-reconnect resync) —
  // resolved (cleared) the moment that room's own data starts arriving.
  const pendingRoomJoinRef = useRef<string | null>(null);
  // Rooms whose next incoming batch should be dropped outright instead of
  // appended or counted as unread. Two distinct things land here: (1) "global",
  // preemptively, right after a reconnect into some other room — BE
  // unconditionally reloads and rebroadcasts a join for "global" on every
  // fresh connection (ClientHandler.start()) with no memory of where this
  // client actually was, regardless of what we go on to ask for; and (2) a
  // room whose room_join we sent but abandoned by switching again before its
  // reply arrived — that reply is still coming and would otherwise inflate
  // the now-irrelevant room's unread badge. Each entry expires on its own
  // after a bounded window so nothing can stay suppressed forever — an
  // exact fix needs the BE to echo back a request id, which it doesn't.
  const staleRoomsRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

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

  // Signatures of everything currently in `messages`, kept in lockstep with
  // it so a dedupe check is a Set lookup instead of an O(n) rescan of the
  // whole array (O(n²) across a full history load) — must be cleared
  // wherever `messages` itself is reset to [].
  const seenSignaturesRef = useRef<Set<string>>(new Set());

  const appendMessage = useCallback((msg: WireMessage) => {
    const sig = messageSignature(msg);
    if (seenSignaturesRef.current.has(sig)) return;
    seenSignaturesRef.current.add(sig);
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

  const markRoomStale = useCallback((room: string) => {
    const existing = staleRoomsRef.current.get(room);
    if (existing) clearTimeout(existing);
    const timer = setTimeout(() => staleRoomsRef.current.delete(room), 5000);
    staleRoomsRef.current.set(room, timer);
  }, []);

  const clearRoomStale = useCallback((room: string) => {
    const existing = staleRoomsRef.current.get(room);
    if (existing) clearTimeout(existing);
    staleRoomsRef.current.delete(room);
  }, []);

  // True if `msg` belongs to a room currently marked stale — drop it
  // outright instead of appending it or inflating an unread badge.
  // Roomless pushes (e.g. a direct "X accepted your friend request" notice)
  // are never part of a per-room reload batch, so they're never eligible.
  const absorbIfStale = useCallback((msg: WireMessage): boolean => {
    return !!msg.room && staleRoomsRef.current.has(msg.room);
  }, []);

  // Send a room_join and track it as "pending" so its reply can be told
  // apart from unrelated traffic. If a *previous* room_join hadn't resolved
  // yet (its data never arrived before this one superseded it), that room
  // is now abandoned — its eventual, now-unwanted reply gets marked stale
  // instead of landing as a phantom unread bump for a room nobody's asking
  // about anymore.
  const requestRoomJoin = useCallback(
    (room: string) => {
      const previous = pendingRoomJoinRef.current;
      if (previous && previous !== room) markRoomStale(previous);
      pendingRoomJoinRef.current = room;
      clearRoomStale(room);
      socketRef.current?.send({ type: 'room_join', room });
    },
    [markRoomStale, clearRoomStale],
  );

  // Call once per inbound room-scoped message, before deciding whether to
  // append/count it — resolves pendingRoomJoinRef the moment the room we
  // actually asked for starts delivering data.
  const resolvePendingRoomJoin = useCallback((msg: WireMessage) => {
    if (msg.room && msg.room === pendingRoomJoinRef.current) {
      pendingRoomJoinRef.current = null;
    }
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
          if (absorbIfStale(msg)) return;
          resolvePendingRoomJoin(msg);
          // Room-scoped (join/leave, via broadcastToRoom) only ever
          // disagrees with currentRoomRef while a room_join is in flight;
          // roomless ones (e.g. a direct "friend request accepted" notice)
          // are always relevant regardless of the active room.
          if (!msg.room || msg.room === currentRoomRef.current) appendMessage(msg);
          return;
        }
        case 'dm': {
          if (absorbIfStale(msg)) return;
          resolvePendingRoomJoin(msg);
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
          if (absorbIfStale(msg)) return;
          resolvePendingRoomJoin(msg);
          // "message" and anything else room-scoped.
          if (msg.room && msg.room === currentRoomRef.current) {
            appendMessage(msg);
          } else if (msg.room) {
            incrementUnread(msg.room);
          }
        }
      }
    },
    [absorbIfStale, addDmPartner, appendMessage, incrementUnread, onAccountDeleted, onUsernameChanged, pushNotice, resolvePendingRoomJoin],
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
          seenSignaturesRef.current.clear();
          const room = currentRoomRef.current;
          if (room !== GLOBAL_ROOM) {
            // BE's own auto-reload above already targeted "global", not
            // this room — mark that unsolicited burst stale (it's never
            // real DM traffic — start() only ever auto-reloads "global")
            // and ask for the room we actually want.
            markRoomStale(GLOBAL_ROOM);
            requestRoomJoin(room);
          }
        }
        hadConnectedBefore = true;
      },
      onClose: () => setConnected(false),
      onMessage: (msg) => handleServerMessageRef.current(msg),
      onAuthFailed: () => onAuthFailedRef.current(),
    });
    socketRef.current = socket;
    const staleTimers = staleRoomsRef.current;
    return () => {
      socket.close();
      staleTimers.forEach(clearTimeout);
      staleTimers.clear();
      pendingRoomJoinRef.current = null;
    };
  }, [token, markRoomStale, requestRoomJoin]);

  const send = useCallback((payload: WireMessage) => socketRef.current?.send(payload), []);

  const changeRoom = useCallback(
    (room: string) => {
      if (room === currentRoomRef.current) return;
      currentRoomRef.current = room;
      setCurrentRoom(room);
      setMessages([]);
      seenSignaturesRef.current.clear();
      clearUnread(room);
      requestRoomJoin(room);
    },
    [clearUnread, requestRoomJoin],
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
