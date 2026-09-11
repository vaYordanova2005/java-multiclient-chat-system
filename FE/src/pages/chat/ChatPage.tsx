import { useState, type CSSProperties } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { useChat } from '../../chat/useChat';
import { useThemeCatalog } from '../../theme/useThemeCatalog';
import { deriveUiThemeColors } from '../../theme/catalog';
import ChatsTab from './ChatsTab';
import FriendsTab from './FriendsTab';
import SettingsTab from './SettingsTab';
import ChatArea from './ChatArea';
import styles from './ChatPage.module.css';

export type BottomTab = 'chats' | 'friends' | 'settings';

export default function ChatPage() {
  const { session, login: setAuthSession, logout } = useAuth();
  const { catalog, loading: catalogLoading } = useThemeCatalog();
  const [bottomTab, setBottomTab] = useState<BottomTab>('chats');

  // Redirecting to /login is RequireAuth's job, triggered by `session`
  // turning null below — logout() carries the notice through context so
  // there's exactly one <Navigate>, not this callback's own racing another.
  const chat = useChat({
    token: session!.token,
    username: session!.username,
    defaultTheme: catalog.defaults,
    onUsernameChanged: (newUsername, newToken) => setAuthSession({ token: newToken, username: newUsername }),
    onAccountDeleted: () => logout(),
    onAuthFailed: () => logout({ text: 'Session expired — please log in again.', variant: 'error' }),
  });

  // Catalog (and its `defaults`, which seed `chat.theme` above) starts out
  // as FALLBACK and flips to the real /api/themes response moments later —
  // rendering through that flip means a real user briefly sees the fallback
  // accent before it jumps to their actual theme. Hold the first paint until
  // it resolves instead.
  if (catalogLoading) {
    return <div className={styles.loading}>Loading…</div>;
  }

  const uiTheme = catalog.uiThemes.find((t) => t.id === chat.theme.uiThemeId);
  const colors = deriveUiThemeColors(uiTheme?.accent ?? '#A7ABDE');

  const rootStyle: CSSProperties & Record<string, string> = {
    '--ui-panel-bg': colors.panelBg,
    '--ui-nav-bg': colors.navBg,
    '--ui-accent': colors.accent,
    '--ui-row-active-bg': colors.rowActiveBg,
    '--ui-accent-text': colors.accentText,
  };

  return (
    <div className={styles.root} style={rootStyle}>
      <div className={styles.leftPanel}>
        <div className={styles.tabSwitcher}>
          {bottomTab === 'chats' && <ChatsTab chat={chat} username={session!.username} />}
          {bottomTab === 'friends' && (
            <FriendsTab
              chat={chat}
              onOpenFriend={(user) => {
                setBottomTab('chats');
                chat.openDM(user);
              }}
            />
          )}
          {bottomTab === 'settings' && <SettingsTab chat={chat} username={session!.username} />}
        </div>
        <div className={styles.bottomNav}>
          <button
            className={bottomTab === 'chats' ? styles.navButtonActive : styles.navButton}
            onClick={() => setBottomTab('chats')}
          >
            Chats
          </button>
          <button
            className={bottomTab === 'friends' ? styles.navButtonActive : styles.navButton}
            onClick={() => setBottomTab('friends')}
          >
            Friends
          </button>
          <button
            className={bottomTab === 'settings' ? styles.navButtonIconOnlyActive : styles.navButtonIconOnly}
            title="Settings"
            aria-label="Settings"
            onClick={() => setBottomTab('settings')}
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
            </svg>
          </button>
        </div>
      </div>

      <div className={styles.chatColumn}>
        <ChatArea chat={chat} catalog={catalog} username={session!.username} />
      </div>
    </div>
  );
}
