import { useEffect, useRef, useState, useCallback } from 'react';
import { ChatSocket } from './socket';
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
  // Fires when the socket closes without ever having opened — the only
  // signal the FE gets that TokenAuthHandshakeInterceptor rejected the
  // token (expired/invalid). A forced disconnect (login elsewhere) closes
  // an already-open socket instead and is surfaced via `connected` only.
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

  const socketRef = useRef<ChatSocket | null>(null);
  const currentRoomRef = useRef(currentRoom);
  const usernameRef = useRef(username);
  const themeRef = useRef(theme);

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
          return;
        }
        case 'account_deleted': {
          onAccountDeleted();
          return;
        }
        case 'error': {
          if (msg.text) pushNotice(msg.text);
          return;
        }
        case 'system': {
          appendMessage(msg);
          return;
        }
        case 'dm': {
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
          // "message" and anything else room-scoped.
          if (msg.room && msg.room === currentRoomRef.current) {
            appendMessage(msg);
          } else if (msg.room) {
            incrementUnread(msg.room);
          }
        }
      }
    },
    [addDmPartner, appendMessage, incrementUnread, onAccountDeleted, onUsernameChanged, pushNotice],
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

  useEffect(() => {
    let opened = false;
    const socket = new ChatSocket(token, {
      onOpen: () => {
        opened = true;
        setConnected(true);
      },
      onClose: () => {
        setConnected(false);
        // Handshake was rejected (expired/invalid token) — the socket never
        // reached onOpen. A forced disconnect (login elsewhere) closes an
        // already-open socket and doesn't hit this branch.
        if (!opened) onAuthFailedRef.current();
      },
      onMessage: (msg) => handleServerMessageRef.current(msg),
    });
    socketRef.current = socket;
    return () => socket.close();
  }, [token]);

  const send = useCallback((payload: WireMessage) => socketRef.current?.send(payload), []);

  const changeRoom = useCallback(
    (room: string) => {
      if (room === currentRoomRef.current) return;
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
