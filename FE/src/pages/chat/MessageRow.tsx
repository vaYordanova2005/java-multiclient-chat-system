import type { WireMessage } from '../../chat/types';
import type { BubbleTheme } from '../../theme/catalog';
import { bubbleThemeCss } from '../../theme/catalog';
import Avatar from '../../components/Avatar';

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
    return (
      <div style={{ display: 'flex', justifyContent: 'center' }}>
        <span style={{ color: 'var(--text-muted)', fontStyle: 'italic', fontSize: 14, padding: 6 }}>
          {msg.text || `➡️ ${msg.user} joined`}
        </span>
      </div>
    );
  }

  const sender = msg.user ?? 'Unknown';
  const isMe = sender === myUsername;
  const avatarId = isMe ? myAvatarId : msg.avatarId;
  const color = msg.color ?? '#808080';

  const bubbleStyle = isMe
    ? {
        padding: '11px 16px',
        borderRadius: 16,
        fontSize: 15,
        background: bubbleThemeCss(bubbleTheme),
        color: bubbleTheme?.textColor ?? '#2d2640',
      }
    : {
        padding: '11px 16px',
        borderRadius: 16,
        fontSize: 15,
        background: '#fff',
        color: 'var(--text-dark)',
      };

  return (
    <div style={{ display: 'flex', gap: 10, maxWidth: '100%', justifyContent: isMe ? 'flex-end' : 'flex-start' }}>
      {!isMe && <Avatar avatarId={avatarId} displayName={sender} size={38} fallbackColor={color} />}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 3, alignItems: isMe ? 'flex-end' : 'flex-start', maxWidth: '65%' }}>
        <span style={{ fontSize: 13, fontWeight: 700, color: isMe ? 'var(--text-muted)' : color }}>
          {isMe ? 'You' : sender}
        </span>
        <span style={{ ...bubbleStyle, wordBreak: 'break-word' }}>{msg.text}</span>
        <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{formatTime(msg.timestamp)}</span>
      </div>
      {isMe && <Avatar avatarId={avatarId} displayName={sender} size={38} fallbackColor={color} />}
    </div>
  );
}
