import { useState } from 'react';
import type { ChatController } from '../../chat/useChat';
import { GLOBAL_ROOM, dmRoomKey } from '../../chat/types';
import Avatar from '../../components/Avatar';
import common from './LeftPanelCommon.module.css';

export default function ChatsTab({ chat, username }: { chat: ChatController; username: string }) {
  const [query, setQuery] = useState('');
  const [expandedUser, setExpandedUser] = useState<string | null>(null);

  function runSearch() {
    chat.search(query);
  }

  function handleOnlineUserClick(user: string) {
    if (user === username || chat.blocked.includes(user)) return;
    if (chat.friends.some((f) => f.username === user)) {
      chat.openDM(user);
    } else {
      setExpandedUser((prev) => (prev === user ? null : user));
    }
  }

  const visibleOnline = chat.onlineUsers.filter((u) => u !== username);

  return (
    <div className={common.scrollArea}>
      <div className={common.searchBar}>
        <input
          className={common.searchInput}
          placeholder="🔍 Search users..."
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && runSearch()}
        />
        <button className={common.searchButton} onClick={runSearch}>
          Go
        </button>
      </div>

      {chat.searchResults.length > 0 && (
        <div className={common.list}>
          {chat.searchResults.map((sr) => (
            <div key={sr.username} className={common.card}>
              <Avatar avatarId={chat.peerAvatars[sr.username]} displayName={sr.username} size={30} fallbackColor={sr.color} />
              <span className={common.cardName}>{sr.username}</span>
              {chat.blocked.includes(sr.username) ? (
                <span className={common.blockedBadge}>🚫 Blocked</span>
              ) : sr.isFriend ? (
                <button className={common.friendsButton} onClick={() => chat.openDM(sr.username)}>
                  ✓ Friends
                </button>
              ) : (
                <SendRequestButton chat={chat} target={sr.username} />
              )}
            </div>
          ))}
        </div>
      )}

      <RoomRow
        active={chat.currentRoom === GLOBAL_ROOM}
        avatarId={undefined}
        displayName="Global"
        unread={chat.unread[GLOBAL_ROOM] ?? 0}
        onClick={chat.switchToGlobal}
      />

      <hr className={common.divider} />

      <div>
        <div className={common.sectionTitle}>ONLINE</div>
        <div className={common.sectionMeta}>({visibleOnline.length})</div>
        <div className={common.list}>
          {visibleOnline.map((u) => (
            <div key={u}>
              <div className={common.row} onClick={() => handleOnlineUserClick(u)}>
                <Avatar avatarId={chat.peerAvatars[u]} displayName={u} size={26} />
                <span className={common.onlineDotSmall} />
                <span className={common.rowNameNormal}>{u}</span>
              </div>
              {expandedUser === u && (
                <div className={common.notFriendsBox}>
                  You are not friends with {u}.
                  <div>
                    <SendRequestButton chat={chat} target={u} onSent={() => setExpandedUser(null)} />
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      <hr className={common.divider} />

      <div>
        <div className={common.sectionTitle}>CHATS</div>
        <div className={common.list}>
          {chat.dmPartners.map((partner) => (
            <RoomRow
              key={partner}
              active={chat.currentRoom === dmRoomKey(username, partner)}
              avatarId={chat.peerAvatars[partner]}
              displayName={partner}
              unread={chat.unread[dmRoomKey(username, partner)] ?? 0}
              onClick={() => chat.openDM(partner)}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function SendRequestButton({ chat, target, onSent }: { chat: ChatController; target: string; onSent?: () => void }) {
  const [sent, setSent] = useState(false);
  if (sent) return <span className={common.sentLabel}>Sent ✓</span>;
  return (
    <button
      className={common.addButton}
      onClick={() => {
        chat.sendFriendRequest(target);
        setSent(true);
        onSent?.();
      }}
    >
      + Add
    </button>
  );
}

function RoomRow({
  active,
  avatarId,
  displayName,
  unread,
  onClick,
}: {
  active: boolean;
  avatarId?: string;
  displayName: string;
  unread: number;
  onClick: () => void;
}) {
  return (
    <div className={active ? common.rowActive : common.row} onClick={onClick}>
      <Avatar avatarId={avatarId} displayName={displayName} size={30} />
      <span className={common.rowName}>{displayName}</span>
      {unread > 0 && <span className={common.unreadBadge}>{unread}</span>}
    </div>
  );
}
