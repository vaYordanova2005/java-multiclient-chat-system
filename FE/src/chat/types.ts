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

export function otherDmUser(room: string, self: string): string {
  const parts = room.split('_');
  if (parts.length < 3) return '';
  return parts[1] === self ? parts[2]! : parts[1]!;
}
