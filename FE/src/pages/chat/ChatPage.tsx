import { useState, type CSSProperties } from 'react';
import { useNavigate } from 'react-router-dom';
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
  const navigate = useNavigate();
  const { catalog } = useThemeCatalog();
  const [bottomTab, setBottomTab] = useState<BottomTab>('chats');

  const chat = useChat({
    token: session!.token,
    username: session!.username,
    onUsernameChanged: (newUsername, newToken) => setAuthSession({ token: newToken, username: newUsername }),
    onAccountDeleted: () => {
      logout();
      navigate('/login', { replace: true });
    },
  });

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
            💬 Chats
          </button>
          <button
            className={bottomTab === 'friends' ? styles.navButtonActive : styles.navButton}
            onClick={() => setBottomTab('friends')}
          >
            👥 Friends
          </button>
          <button
            className={bottomTab === 'settings' ? styles.navButtonIconOnlyActive : styles.navButtonIconOnly}
            title="Settings"
            onClick={() => setBottomTab('settings')}
          >
            ⚙️
          </button>
        </div>
      </div>

      <div className={styles.chatColumn}>
        <ChatArea chat={chat} catalog={catalog} username={session!.username} />
      </div>
    </div>
  );
}
