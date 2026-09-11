import { useEffect, useMemo, useRef, useState } from 'react';
import type { ChatController } from '../../chat/useChat';
import { GLOBAL_ROOM, dmRoomKey } from '../../chat/types';
import Avatar from '../../components/Avatar';
import common from './LeftPanelCommon.module.css';

const SEARCH_DEBOUNCE_MS = 300;

export default function ChatsTab({ chat, username }: { chat: ChatController; username: string }) {
  const [query, setQuery] = useState('');
  const [expandedUser, setExpandedUser] = useState<string | null>(null);
  const searchAreaRef = useRef<HTMLDivElement>(null);

  // Every account's own persistent color (users.color) lives on chat.friends
  // already — reuse it instead of the flat grey Avatar falls back to when no
  // color is passed, so the same person doesn't show a different color in
  // ONLINE/CHATS than in search results (which get color straight off the
  // wire via SearchResult).
  const friendColors = useMemo(
    () => new Map(chat.friends.map((f) => [f.username, f.color])),
    [chat.friends],
  );

  function clearSearch() {
    setQuery('');
    chat.search('');
  }

  // Debounced live search — fires as you type instead of requiring Enter/Go.
  // The button/Enter path still works for an immediate search.
  useEffect(() => {
    if (!query.trim()) {
      chat.search('');
      return;
    }
    const timer = setTimeout(() => chat.search(query), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query]);

  // Results (and the query itself) shouldn't linger once you've acted on
  // them or moved on — clear on room switch (opening a DM from a result
  // counts) and on any click outside the search bar/results area.
  useEffect(() => {
    setQuery('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chat.currentRoom]);

  useEffect(() => {
    const handlePointerDown = (e: MouseEvent) => {
      if (searchAreaRef.current?.contains(e.target as Node)) return;
      clearSearch();
    };
    document.addEventListener('mousedown', handlePointerDown);
    return () => document.removeEventListener('mousedown', handlePointerDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
      <div ref={searchAreaRef}>
        <div className={common.searchBar}>
          <input
            className={common.searchInput}
            placeholder="Search users..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && chat.search(query)}
          />
          <button className={common.searchButton} onClick={() => chat.search(query)}>
            Go
          </button>
        </div>

        {query.trim() && (
          <div className={common.list}>
            {chat.searchResults.length === 0 && <div className={common.emptyHint}>No users found.</div>}
            {chat.searchResults.map((sr) => {
              const blocked = chat.blocked.includes(sr.username);
              // Friends are one click away from a DM — the whole row opens
              // it, same as CHATS/Friends rows, instead of only the small
              // badge (which also required scanning ONLINE first to find them).
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
                    <span className={common.blockedBadge}>Blocked</span>
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
      </div>

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
                <Avatar avatarId={chat.peerAvatars[u]} displayName={u} size={26} fallbackColor={friendColors.get(u)} />
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
              fallbackColor={friendColors.get(partner)}
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
  fallbackColor,
  displayName,
  unread,
  onClick,
}: {
  active: boolean;
  avatarId?: string;
  fallbackColor?: string;
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
      <Avatar avatarId={avatarId} displayName={displayName} size={30} fallbackColor={fallbackColor} />
      <span className={common.rowName}>{displayName}</span>
      {unread > 0 && <span className={common.unreadBadge}>{unread}</span>}
    </div>
  );
}
