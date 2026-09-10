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
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {chat.searchResults.map((sr) => (
            <div
              key={sr.username}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: 5,
                background: '#fff',
                borderRadius: 6,
              }}
            >
              <Avatar avatarId={undefined} displayName={sr.username} size={30} fallbackColor={sr.color} />
              <span style={{ flex: 1, color: 'var(--text-dark)', fontSize: 14 }}>{sr.username}</span>
              {chat.blocked.includes(sr.username) ? (
                <span style={{ color: 'var(--danger-soft-text)', fontSize: 12, fontWeight: 700 }}>🚫 Blocked</span>
              ) : sr.isFriend ? (
                <button
                  style={{ background: 'none', border: 'none', color: 'var(--success-soft-text)', fontSize: 12, fontWeight: 700, cursor: 'pointer' }}
                  onClick={() => chat.openDM(sr.username)}
                >
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
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginTop: 4 }}>
          {visibleOnline.map((u) => (
            <div key={u}>
              <div className={common.row} onClick={() => handleOnlineUserClick(u)}>
                <Avatar avatarId={chat.peerAvatars[u]} displayName={u} size={26} />
                <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#00b894', flexShrink: 0 }} />
                <span className={common.rowName} style={{ fontWeight: 400 }}>
                  {u}
                </span>
              </div>
              {expandedUser === u && (
                <div
                  style={{
                    margin: '4px 0 4px 34px',
                    padding: 8,
                    background: '#fff',
                    borderRadius: 8,
                    fontSize: 13,
                    color: 'var(--text-dark)',
                  }}
                >
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
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginTop: 4 }}>
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
  if (sent) return <span style={{ color: 'var(--text-muted)', fontSize: 13 }}>Sent ✓</span>;
  return (
    <button
      style={{
        background: 'none',
        border: 'none',
        color: 'var(--lilac-6)',
        textDecoration: 'underline',
        fontSize: 13,
        fontWeight: 700,
        cursor: 'pointer',
      }}
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
