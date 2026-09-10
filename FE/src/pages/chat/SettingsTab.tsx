import { useState } from 'react';
import type { ChatController } from '../../chat/useChat';
import Avatar, { AVATAR_CATALOG } from '../../components/Avatar';
import styles from './SettingsTab.module.css';

type Section = 'Profile' | 'Privacy' | 'Social' | 'Danger';
const SECTIONS: Section[] = ['Profile', 'Privacy', 'Social', 'Danger'];

export default function SettingsTab({ chat, username }: { chat: ChatController; username: string }) {
  const [section, setSection] = useState<Section>('Profile');

  return (
    <div className={styles.container}>
      <div className={styles.pillBar}>
        {SECTIONS.map((s) => (
          <button key={s} className={s === section ? styles.pillActive : styles.pill} onClick={() => setSection(s)}>
            {s}
          </button>
        ))}
      </div>
      <div className={styles.content}>
        {section === 'Profile' && <ProfileSection chat={chat} username={username} />}
        {section === 'Privacy' && <PrivacySection chat={chat} />}
        {section === 'Social' && <SocialSection chat={chat} />}
        {section === 'Danger' && <DangerSection chat={chat} />}
      </div>
    </div>
  );
}

function ProfileSection({ chat, username }: { chat: ChatController; username: string }) {
  const [name, setName] = useState(username);
  const [status, setStatus] = useState('');

  function save() {
    const trimmed = name.trim();
    if (!trimmed) return;
    chat.changeUsername(trimmed);
    setStatus('Saving...');
  }

  return (
    <>
      <div className={styles.sectionTitle}>PROFILE</div>

      <div className={styles.label}>Username</div>
      <input className={styles.field} value={name} onChange={(e) => setName(e.target.value)} />
      <button className={styles.primaryButton} onClick={save}>
        Save Username
      </button>
      {status && <div className={styles.status}>{status}</div>}

      <hr style={{ border: 'none', borderTop: '1px solid rgba(122,111,148,0.15)' }} />

      <div className={styles.label} style={{ fontWeight: 700 }}>
        Choose an avatar
      </div>
      <div className={styles.avatarGrid}>
        {AVATAR_CATALOG.map((def) => (
          <div
            key={def.id}
            className={chat.profile?.avatarId === def.id ? styles.avatarSwatchSelected : styles.avatarSwatch}
            onClick={() => chat.setAvatar(def.id)}
          >
            <Avatar avatarId={def.id} displayName="" size={48} />
          </div>
        ))}
        <div
          className={!chat.profile?.avatarId ? styles.avatarSwatchSelected : styles.avatarSwatch}
          onClick={() => chat.setAvatar(null)}
        >
          <div
            style={{
              width: 48,
              height: 48,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--text-muted)',
              border: '1px solid var(--text-muted)',
              borderRadius: '50%',
              boxSizing: 'border-box',
            }}
          >
            —
          </div>
        </div>
      </div>
      <div className={styles.hint}>📌 Uploading a custom avatar from your device is coming in a future update.</div>
    </>
  );
}

function PrivacySection({ chat }: { chat: ChatController }) {
  return (
    <>
      <div className={styles.sectionTitle}>PRIVACY</div>
      <div className={styles.toggleRow}>
        <label style={{ color: 'var(--text-dark)', fontSize: 15 }}>Show Online Status</label>
        <input
          type="checkbox"
          checked={chat.profile?.showOnlineStatus ?? true}
          onChange={(e) => chat.setOnlineVisibility(e.target.checked)}
        />
      </div>
      <div className={styles.explanation}>
        When disabled, other users won't see you in the online list, even while you're connected.
      </div>
      <div className={styles.hint}>📌 "Last Seen" display is planned for a future update.</div>
    </>
  );
}

function SocialSection({ chat }: { chat: ChatController }) {
  const [blockTarget, setBlockTarget] = useState('');

  return (
    <>
      <div className={styles.sectionTitle}>FRIENDS</div>
      <div className={styles.explanation}>Manage your friends from the Chats tab — click any chat to message them.</div>

      <hr style={{ border: 'none', borderTop: '1px solid rgba(122,111,148,0.15)' }} />

      <div className={styles.sectionTitle}>BLOCKED USERS</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {chat.blocked.length === 0 && <div className={styles.status}>No blocked users</div>}
        {chat.blocked.map((user) => (
          <div key={user} className={styles.blockedRow}>
            <span style={{ flex: 1, color: 'var(--text-dark)', fontSize: 14 }}>{user}</span>
            <button className={styles.unblockButton} onClick={() => chat.unblockUser(user)}>
              Unblock
            </button>
          </div>
        ))}
      </div>

      <div className={styles.explanation}>To block someone, type their username below.</div>
      <div className={styles.blockRow}>
        <input
          className={styles.field}
          placeholder="Username to block..."
          value={blockTarget}
          onChange={(e) => setBlockTarget(e.target.value)}
        />
        <button
          className={styles.blockButton}
          onClick={() => {
            const target = blockTarget.trim();
            if (!target) return;
            chat.blockUser(target);
            setBlockTarget('');
          }}
        >
          Block User
        </button>
      </div>
    </>
  );
}

function DangerSection({ chat }: { chat: ChatController }) {
  const [password, setPassword] = useState('');
  const [status, setStatus] = useState('');

  function confirmDelete() {
    if (!password) {
      setStatus('Please enter your password to confirm.');
      return;
    }
    chat.deleteAccount(password);
    setStatus('Processing...');
  }

  return (
    <div className={styles.dangerBox}>
      <div className={styles.dangerTitle}>⚠️ DANGER ZONE</div>
      <div style={{ color: 'var(--text-dark)', fontSize: 13 }}>
        Deleting your account is permanent and cannot be undone. Your chat history will remain visible to others, but
        your profile will be gone.
      </div>
      <input
        className={styles.field}
        type="password"
        placeholder="Confirm your password..."
        value={password}
        onChange={(e) => setPassword(e.target.value)}
      />
      {status && <div style={{ fontSize: 13, color: 'var(--danger-soft-text)' }}>{status}</div>}
      <button className={styles.deleteButton} onClick={confirmDelete}>
        Delete My Account
      </button>
    </div>
  );
}
