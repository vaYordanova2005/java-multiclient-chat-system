import { useEffect, useState } from 'react';
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
          {chat.searchResults.map((sr) => {
            const blocked = chat.blocked.includes(sr.username);
            // Friends are one click away from a DM — the whole row opens it,
            // same as CHATS/Friends rows, instead of only the small badge
            // (which also required scanning ONLINE first to find them).
            const clickable = sr.isFriend && !blocked;
            return (
              <div
                key={sr.username}
                className={common.card}
                style={clickable ? { cursor: 'pointer' } : undefined}
                onClick={clickable ? () => chat.openDM(sr.username) : undefined}
                role={clickable ? 'button' : undefined}
                tabIndex={clickable ? 0 : undefined}
                onKeyDown={
                  clickable
                    ? (e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault();
                          chat.openDM(sr.username);
                        }
                      }
                    : undefined
                }
              >
                <Avatar avatarId={chat.peerAvatars[sr.username]} displayName={sr.username} size={30} fallbackColor={sr.color} />
                <span className={common.cardName}>{sr.username}</span>
                {blocked ? (
                  <span className={common.blockedBadge}>🚫 Blocked</span>
                ) : sr.isFriend ? (
                  <span className={common.friendsButton}>✓ Friends</span>
                ) : (
                  <SendRequestButton chat={chat} target={sr.username} />
                )}
              </div>
            );
          })}
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
              <div
                className={common.row}
                onClick={() => handleOnlineUserClick(u)}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    handleOnlineUserClick(u);
                  }
                }}
              >
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
  const [state, setState] = useState<'idle' | 'pending' | 'sent'>('idle');

  // chat.responseSeq only bumps on an actual reply to a request we sent
  // (see useChat's 'error' case — every friend_request outcome, success or
  // failure, comes back through it). Read the tone of whatever notice just
  // landed instead of flipping to "Sent" optimistically on click, so a
  // decline/already-pending/blocked error doesn't get reported as sent.
  useEffect(() => {
    if (state !== 'pending') return;
    const last = chat.notices[chat.notices.length - 1];
    if (last?.tone === 'success') {
      setState('sent');
      onSent?.();
    } else {
      setState('idle');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chat.responseSeq]);

  if (state === 'sent') return <span className={common.sentLabel}>Sent ✓</span>;
  return (
    <button
      className={common.addButton}
      disabled={state === 'pending'}
      onClick={() => {
        chat.sendFriendRequest(target);
        setState('pending');
      }}
    >
      {state === 'pending' ? 'Sending…' : '+ Add'}
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
    <div
      className={active ? common.rowActive : common.row}
      onClick={onClick}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onClick();
        }
      }}
    >
      <Avatar avatarId={avatarId} displayName={displayName} size={30} />
      <span className={common.rowName}>{displayName}</span>
      {unread > 0 && <span className={common.unreadBadge}>{unread}</span>}
    </div>
  );
}
