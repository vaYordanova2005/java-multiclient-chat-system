import type { WireMessage } from '../../chat/types';
import type { BubbleTheme } from '../../theme/catalog';
import { bubbleThemeCss } from '../../theme/catalog';
import Avatar from '../../components/Avatar';
import styles from './MessageRow.module.css';

function formatTime(timestamp?: string): string {
  if (!timestamp) return '';
  const d = new Date(timestamp);
  if (Number.isNaN(d.getTime())) return timestamp;
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export default function MessageRow({
  msg,
  myUsername,
  myAvatarId,
  bubbleTheme,
}: {
  msg: WireMessage;
  myUsername: string;
  myAvatarId: string | null | undefined;
  bubbleTheme: BubbleTheme | undefined;
}) {
  if (msg.type === 'system' || msg.type === 'leave' || msg.type === 'room_join') {
    // Group system events (see BE's sendGroupSystemMessage) carry the actor/
    // target as msg.user/msg.receiver — first-class fields changeUsername
    // keeps in sync on rename — instead of baking the names into msg.text
    // itself, which would keep saying the old name forever after a rename.
    // {user}/{receiver} in the template get interpolated with the CURRENT
    // values at render time. replaceAll, not replace — the latter substitutes
    // only the FIRST occurrence, so a template mentioning the same placeholder
    // twice would render half-interpolated with no error anywhere. Safe to
    // substitute blindly: usernames are ^[A-Za-z0-9]{3,30}$ (UsernameValidator),
    // so no one can be named "{receiver}" and inject through the template.
    const systemText = (msg.text || `${msg.user} joined`)
      .replaceAll('{user}', msg.user ?? '')
      .replaceAll('{receiver}', msg.receiver ?? '');
    return (
      <div className={styles.systemWrapper}>
        <span className={styles.systemText}>{systemText}</span>
      </div>
    );
  }

  const sender = msg.user ?? 'Unknown';
  const isMe = sender === myUsername;
  const avatarId = isMe ? myAvatarId : msg.avatarId;
  const color = msg.color ?? '#808080';

  // Bubble color for "my" messages comes from the user's chosen bubble
  // theme; the other side always uses the fixed surface-card look — only
  // the theme-driven half needs an inline style.
  const bubbleStyleMe = { background: bubbleThemeCss(bubbleTheme), color: bubbleTheme?.textColor ?? '#2d2640' };

  return (
    <div className={isMe ? styles.rowMe : styles.row}>
      {!isMe && <Avatar avatarId={avatarId} displayName={sender} size={38} fallbackColor={color} />}
      <div className={isMe ? styles.contentMe : styles.content}>
        <span className={isMe ? styles.senderNameMe : styles.senderName} style={isMe ? undefined : { color }}>
          {isMe ? 'You' : sender}
        </span>
        <span className={isMe ? styles.bubble : styles.bubbleOther} style={isMe ? bubbleStyleMe : undefined}>
          {msg.text}
        </span>
        <span className={styles.timestamp}>{formatTime(msg.timestamp)}</span>
      </div>
      {isMe && <Avatar avatarId={avatarId} displayName={sender} size={38} fallbackColor={color} />}
    </div>
  );
}
