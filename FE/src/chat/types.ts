// Wire protocol types — see BE/websocket/ClientHandler.java + model/Message.java.
// Single flat envelope for every direction; many "structured" pushes put a
// JSON-encoded string in `text` that must be parsed again.
export interface WireMessage {
  type: string;
  user?: string;
  color?: string;
  text?: string;
  timestamp?: string;
  room?: string;
  receiver?: string;
  avatarId?: string;
  token?: string;
}

export interface FriendInfo {
  username: string;
  color: string;
}

export interface SearchResult {
  username: string;
  color: string;
  isFriend: boolean;
}

export interface ThemePreferences {
  bubbleThemeId: string;
  backgroundThemeId: string;
  uiThemeId: string;
}

export interface ProfileInfo {
  username: string;
  avatarId: string | null;
  showOnlineStatus: boolean;
}

export interface DmConversationsPayload {
  partners: string[];
  avatars: Record<string, string>;
}

export const GLOBAL_ROOM = 'global';

export function dmRoomKey(a: string, b: string): string {
  return a < b ? `dm_${a}_${b}` : `dm_${b}_${a}`;
}

// Mirrors BE's `room.split("_", 3)` (ClientHandler/MessageDAO/UserDAO): split
// on only the first two underscores so a `_` inside a username (if one ever
// got past UsernameValidator's alnum-only whitelist) wouldn't shift which
// piece is which. JS's `split(sep, limit)` truncates instead of keeping the
// remainder joined like Java's does, so this is done manually.
export function otherDmUser(room: string, self: string): string {
  const first = room.indexOf('_');
  if (first < 0) return '';
  const second = room.indexOf('_', first + 1);
  if (second < 0) return '';
  const user1 = room.slice(first + 1, second);
  const user2 = room.slice(second + 1);
  if (!user1 || !user2) return '';
  return user1 === self ? user2 : user1;
}
