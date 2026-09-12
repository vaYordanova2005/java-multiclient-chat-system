import { useEffect, useRef, useState } from 'react';
import type { ChatController } from '../../chat/useChat';
import { MAX_GROUP_NAME_LENGTH } from '../../chat/types';
import Avatar from '../../components/Avatar';
import styles from './NewGroupOverlay.module.css';

export default function NewGroupOverlay({ chat, onClose }: { chat: ChatController; onClose: () => void }) {
  const [name, setName] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handlePointerDown = (e: MouseEvent) => {
      const target = e.target as Node;
      if (panelRef.current?.contains(target)) return;
      if (target instanceof Element && target.closest('[data-new-group-toggle]')) return;
      onClose();
    };
    document.addEventListener('mousedown', handlePointerDown);
    return () => document.removeEventListener('mousedown', handlePointerDown);
  }, [onClose]);

  function toggleMember(username: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(username)) next.delete(username);
      else next.add(username);
      return next;
    });
  }

  // Mirrors the BE's own create_group validation (ClientHandler) so the
  // error round-trip is the exception path, not the common one.
  const trimmedName = name.trim();
  const canCreate = trimmedName.length > 0 && trimmedName.length <= MAX_GROUP_NAME_LENGTH && selected.size > 0;

  function submit() {
    if (!canCreate) return;
    chat.createGroup(trimmedName, [...selected]);
    onClose();
  }

  return (
    <div className={styles.wrapper}>
      <div className={styles.panel} ref={panelRef}>
        <div className={styles.header}>
          <span className={styles.title}>New Group</span>
          <button className={styles.closeButton} onClick={onClose}>
            ✕
          </button>
        </div>
        <hr className={styles.divider} />

        <input
          className={styles.nameInput}
          placeholder="Group name..."
          value={name}
          maxLength={MAX_GROUP_NAME_LENGTH}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
        />

        <div className={styles.sectionTitle}>MEMBERS</div>
        <div className={styles.friendList}>
          {chat.friends.length === 0 && <div className={styles.emptyHint}>Add some friends first.</div>}
          {chat.friends.map((f) => (
            <label key={f.username} className={styles.friendRow}>
              <input
                type="checkbox"
                checked={selected.has(f.username)}
                onChange={() => toggleMember(f.username)}
              />
              <Avatar avatarId={chat.peerAvatars[f.username]} displayName={f.username} size={26} fallbackColor={f.color} />
              <span className={styles.friendName}>{f.username}</span>
            </label>
          ))}
        </div>

        <button className={styles.createButton} disabled={!canCreate} onClick={submit}>
          Create
        </button>
      </div>
    </div>
  );
}
