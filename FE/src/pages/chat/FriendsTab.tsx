import type { ChatController } from '../../chat/useChat';
import Avatar from '../../components/Avatar';
import common from './LeftPanelCommon.module.css';

export default function FriendsTab({ chat, onOpenFriend }: { chat: ChatController; onOpenFriend: (user: string) => void }) {
  const sortedFriends = [...chat.friends].sort((a, b) => a.username.localeCompare(b.username));

  return (
    <div className={common.scrollArea}>
      <div style={{ color: 'var(--text-dark)', fontSize: 16, fontWeight: 700 }}>👥 Friends</div>

      {chat.pending.length > 0 && (
        <div style={{ background: 'var(--lilac-4)', borderRadius: 8, padding: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
          <div style={{ color: 'var(--text-dark)', fontSize: 13, fontWeight: 700 }}>FRIEND REQUESTS</div>
          {chat.pending.map((requester) => (
            <div
              key={requester}
              style={{ display: 'flex', alignItems: 'center', gap: 6, padding: 4, background: '#fff', borderRadius: 6 }}
            >
              <Avatar avatarId={undefined} displayName={requester} size={24} fallbackColor="#a29bfe" />
              <span style={{ flex: 1, color: 'var(--text-dark)', fontSize: 14 }}>{requester}</span>
              <button
                style={{
                  background: 'var(--success-soft)',
                  color: 'var(--success-soft-text)',
                  border: 'none',
                  borderRadius: 6,
                  padding: '3px 8px',
                  fontSize: 13,
                  fontWeight: 700,
                  cursor: 'pointer',
                }}
                onClick={() => chat.respondFriendRequest(requester, true)}
              >
                ✓
              </button>
              <button
                style={{
                  background: 'var(--danger-soft)',
                  color: 'var(--danger-soft-text)',
                  border: 'none',
                  borderRadius: 6,
                  padding: '3px 8px',
                  fontSize: 13,
                  fontWeight: 700,
                  cursor: 'pointer',
                }}
                onClick={() => chat.respondFriendRequest(requester, false)}
              >
                ✗
              </button>
            </div>
          ))}
        </div>
      )}

      <hr className={common.divider} />

      <div>
        <div className={common.sectionTitle}>ALL FRIENDS</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginTop: 4 }}>
          {sortedFriends.length === 0 && (
            <div className={common.emptyHint}>No friends yet — search for someone in Chats to add them.</div>
          )}
          {sortedFriends.map((friend) => {
            const online = chat.onlineUsers.includes(friend.username);
            return (
              <div key={friend.username} className={common.row} onClick={() => onOpenFriend(friend.username)}>
                <Avatar avatarId={chat.peerAvatars[friend.username]} displayName={friend.username} size={30} fallbackColor="#00b894" />
                <span className={common.rowName} style={{ fontWeight: 400 }}>
                  {friend.username}
                </span>
                <span
                  style={{ width: 8, height: 8, borderRadius: '50%', background: online ? '#00b894' : '#c2c2c2', flexShrink: 0 }}
                />
                <span style={{ color: 'var(--text-muted)', fontSize: 11 }}>{online ? 'online' : 'offline'}</span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
