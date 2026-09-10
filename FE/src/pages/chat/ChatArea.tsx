import { useEffect, useRef, useState } from 'react';
import type { ChatController } from '../../chat/useChat';
import { GLOBAL_ROOM, otherDmUser } from '../../chat/types';
import type { ThemeCatalog } from '../../theme/catalog';
import { backgroundThemeCss } from '../../theme/catalog';
import MessageRow from './MessageRow';
import AppearanceOverlay from './AppearanceOverlay';
import styles from './ChatArea.module.css';

export default function ChatArea({
  chat,
  catalog,
  username,
}: {
  chat: ChatController;
  catalog: ThemeCatalog;
  username: string;
}) {
  const [input, setInput] = useState('');
  const [appearanceOpen, setAppearanceOpen] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [chat.messages]);

  const isDm = chat.currentRoom.startsWith('dm_');
  const otherUser = isDm ? otherDmUser(chat.currentRoom, username) : null;
  const title = chat.currentRoom === GLOBAL_ROOM ? '🌍 Global' : `💬 ${otherUser}`;
  const otherOnline = otherUser ? chat.onlineUsers.includes(otherUser) : false;

  const bubbleTheme = catalog.bubbleThemes.find((t) => t.id === chat.theme.bubbleThemeId);
  const backgroundTheme = catalog.backgroundThemes.find((t) => t.id === chat.theme.backgroundThemeId);

  function submit() {
    chat.sendChatMessage(input);
    setInput('');
  }

  return (
    <div className={styles.column}>
      <div className={styles.header}>
        <span className={styles.title}>{title}</span>
        {isDm && otherOnline && <span className={styles.statusDot} />}
        <span className={styles.spacer} />
        <button
          className={styles.appearanceToggle}
          data-appearance-toggle
          onClick={() => setAppearanceOpen((v) => !v)}
        >
          ⋯
        </button>
      </div>

      <div className={styles.background} style={{ background: backgroundThemeCss(backgroundTheme) }}>
        {!chat.connected && <div className={styles.disconnectedBanner}>⚠️ Disconnected — reconnecting…</div>}

        {chat.notices.length > 0 && (
          <div className={styles.notices}>
            {chat.notices.map((n) => (
              <div
                key={n.id}
                className={n.tone === 'success' ? styles.noticeSuccess : n.tone === 'error' ? styles.noticeError : styles.noticeInfo}
              >
                {n.text}
              </div>
            ))}
          </div>
        )}

        {appearanceOpen && <AppearanceOverlay chat={chat} catalog={catalog} onClose={() => setAppearanceOpen(false)} />}

        <div className={styles.messageScroll} ref={scrollRef}>
          {chat.messages.map((msg) => (
            <MessageRow
              key={msg._id}
              msg={msg}
              myUsername={username}
              myAvatarId={chat.profile?.avatarId}
              bubbleTheme={bubbleTheme}
            />
          ))}
        </div>

        <div className={styles.inputBar}>
          <input
            className={styles.textInput}
            placeholder={chat.connected ? 'Type a message...' : 'Disconnected...'}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && submit()}
            disabled={!chat.connected}
          />
          <button className={styles.sendButton} onClick={submit} disabled={!chat.connected}>
            Send
          </button>
        </div>
      </div>
    </div>
  );
}
