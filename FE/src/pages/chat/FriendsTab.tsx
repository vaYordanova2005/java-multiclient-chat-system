import type { ChatController } from '../../chat/useChat';
import Avatar from '../../components/Avatar';
import common from './LeftPanelCommon.module.css';

export default function FriendsTab({ chat, onOpenFriend }: { chat: ChatController; onOpenFriend: (user: string) => void }) {
  const sortedFriends = [...chat.friends].sort((a, b) => a.username.localeCompare(b.username));

  return (
    <div className={common.scrollArea}>
      <div className={common.panelHeader}>👥 Friends</div>

      {chat.pending.length > 0 && (
        <div className={common.pendingBox}>
          <div className={common.pendingTitle}>FRIEND REQUESTS</div>
          {chat.pending.map((requester) => (
            <div key={requester} className={common.card}>
              <Avatar avatarId={chat.peerAvatars[requester]} displayName={requester} size={24} fallbackColor="#a29bfe" />
              <span className={common.cardName}>{requester}</span>
              <button className={common.acceptButton} onClick={() => chat.respondFriendRequest(requester, true)}>
                ✓
              </button>
              <button className={common.declineButton} onClick={() => chat.respondFriendRequest(requester, false)}>
                ✗
              </button>
            </div>
          ))}
        </div>
      )}

      <hr className={common.divider} />

      <div>
        <div className={common.sectionTitle}>ALL FRIENDS</div>
        <div className={common.list}>
          {sortedFriends.length === 0 && (
            <div className={common.emptyHint}>No friends yet — search for someone in Chats to add them.</div>
          )}
          {sortedFriends.map((friend) => {
            const online = chat.onlineUsers.includes(friend.username);
            return (
              <div
                key={friend.username}
                className={common.row}
                onClick={() => onOpenFriend(friend.username)}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    onOpenFriend(friend.username);
                  }
                }}
              >
                <Avatar avatarId={chat.peerAvatars[friend.username]} displayName={friend.username} size={30} fallbackColor={friend.color} />
                <span className={common.rowNameNormal}>{friend.username}</span>
                <span className={online ? common.presenceDotOnline : common.presenceDotOffline} />
                <span className={common.presenceText}>{online ? 'online' : 'offline'}</span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
