import { useEffect, useRef, useState } from 'react';
import type { ChatController } from '../../chat/useChat';
import { MAX_GROUP_NAME_LENGTH, type GroupInfo } from '../../chat/types';
import styles from './GroupHeaderMenu.module.css';

export default function GroupHeaderMenu({
  chat,
  group,
  onClose,
}: {
  chat: ChatController;
  group: GroupInfo;
  onClose: () => void;
}) {
  const [renaming, setRenaming] = useState(false);
  const [nameDraft, setNameDraft] = useState(group.name);
  const panelRef = useRef<HTMLDivElement>(null);
  // Enter -> submitRename() -> setRenaming(false) unmounts the input ->
  // onBlur fires -> submitRename() again. The trimmed !== group.name guard
  // doesn't catch this because group.name hasn't been updated by the server
  // yet when the second call runs. This ref makes the first call win and the
  // blur-triggered-by-unmount call a no-op.
  const submittedRef = useRef(false);

  useEffect(() => {
    const handlePointerDown = (e: MouseEvent) => {
      const target = e.target as Node;
      if (panelRef.current?.contains(target)) return;
      if (target instanceof Element && target.closest('[data-group-menu-toggle]')) return;
      onClose();
    };
    document.addEventListener('mousedown', handlePointerDown);
    return () => document.removeEventListener('mousedown', handlePointerDown);
  }, [onClose]);

  function submitRename() {
    if (submittedRef.current) return;
    submittedRef.current = true;

    const trimmed = nameDraft.trim();
    if (trimmed && trimmed.length <= MAX_GROUP_NAME_LENGTH && trimmed !== group.name) {
      chat.renameGroup(group.id, trimmed);
    }
    setRenaming(false);
  }

  const addableFriends = chat.friends.filter((f) => !group.members.includes(f.username));

  return (
    <div className={styles.panel} ref={panelRef}>
      <div className={styles.section}>
        <div className={styles.sectionTitle}>NAME</div>
        {renaming ? (
          <input
            className={styles.nameInput}
            autoFocus
            value={nameDraft}
            maxLength={MAX_GROUP_NAME_LENGTH}
            onChange={(e) => setNameDraft(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && submitRename()}
            onBlur={submitRename}
          />
        ) : (
          <div
            className={styles.nameRow}
            onClick={() => {
              submittedRef.current = false;
              setRenaming(true);
            }}
          >
            <span className={styles.nameText}>{group.name}</span>
            <span className={styles.editHint}>✎</span>
          </div>
        )}
      </div>

      <hr className={styles.divider} />

      <div className={styles.section}>
        <div className={styles.sectionTitle}>MEMBERS ({group.members.length})</div>
        <div className={styles.memberList}>
          {group.members.map((m) => (
            <div key={m} className={styles.memberRow}>
              {m}
            </div>
          ))}
        </div>

        {addableFriends.length > 0 && (
          <>
            <div className={styles.sectionTitle}>ADD FRIEND</div>
            <div className={styles.memberList}>
              {addableFriends.map((f) => (
                <div key={f.username} className={styles.memberRow}>
                  <span className={styles.memberName}>{f.username}</span>
                  <button className={styles.addButton} onClick={() => chat.addGroupMember(group.id, f.username)}>
                    + Add
                  </button>
                </div>
              ))}
            </div>
          </>
        )}
      </div>

      <hr className={styles.divider} />

      <button
        className={styles.leaveButton}
        onClick={() => {
          chat.leaveGroup(group.id);
          onClose();
        }}
      >
        Leave Group
      </button>
    </div>
  );
}
