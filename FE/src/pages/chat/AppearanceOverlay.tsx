import type { ChatController } from '../../chat/useChat';
import type { ThemeCatalog } from '../../theme/catalog';
import { backgroundThemeCss, bubbleThemeCss } from '../../theme/catalog';
import styles from './AppearanceOverlay.module.css';

export default function AppearanceOverlay({
  chat,
  catalog,
  onClose,
}: {
  chat: ChatController;
  catalog: ThemeCatalog;
  onClose: () => void;
}) {
  // legacy/Main.java skips the "bg_solid_lilac" swatch here — it duplicates
  // the left-panel default and isn't worth a second entry in this picker.
  const backgrounds = catalog.backgroundThemes.filter((t) => !(t.colors.length === 1 && t.colors[0] === '#E8DAF0'));
  const bgSolid = backgrounds.filter((t) => t.type === 'SOLID');
  const bgOmbre = backgrounds.filter((t) => t.type === 'OMBRE');
  const bubbleSolid = catalog.bubbleThemes.filter((t) => t.type === 'SOLID');
  const bubbleOmbre = catalog.bubbleThemes.filter((t) => t.type === 'OMBRE');

  return (
    <div className={styles.wrapper}>
      <div className={styles.panel}>
        <div className={styles.header}>
          <span className={styles.title}>🎨 Appearance</span>
          <button className={styles.closeButton} onClick={onClose}>
            ✕
          </button>
        </div>
        <hr className={styles.divider} />

        <div className={styles.sectionTitle}>BACKGROUND</div>
        <div className={styles.subLabel}>Solid</div>
        <div className={styles.swatchGrid}>
          {bgSolid.map((t) => (
            <div
              key={t.id}
              className={t.id === chat.theme.backgroundThemeId ? styles.swatchSelected : styles.swatch}
              onClick={() => chat.saveTheme({ backgroundThemeId: t.id })}
            >
              <div className={styles.swatchPreview} style={{ background: backgroundThemeCss(t) }} />
              <span className={styles.swatchLabel}>{t.displayName}</span>
            </div>
          ))}
        </div>
        <div className={styles.subLabel}>Ombre</div>
        <div className={styles.swatchGrid}>
          {bgOmbre.map((t) => (
            <div
              key={t.id}
              className={t.id === chat.theme.backgroundThemeId ? styles.swatchSelected : styles.swatch}
              onClick={() => chat.saveTheme({ backgroundThemeId: t.id })}
            >
              <div className={styles.swatchPreview} style={{ background: backgroundThemeCss(t) }} />
              <span className={styles.swatchLabel}>{t.displayName}</span>
            </div>
          ))}
        </div>

        <hr className={styles.divider} />

        <div className={styles.sectionTitle}>MESSAGE BUBBLES</div>
        <div className={styles.subLabel}>Solid</div>
        <div className={styles.swatchGrid}>
          {bubbleSolid.map((t) => (
            <div
              key={t.id}
              className={t.id === chat.theme.bubbleThemeId ? styles.swatchSelected : styles.swatch}
              onClick={() => chat.saveTheme({ bubbleThemeId: t.id })}
            >
              <div className={styles.swatchPreview} style={{ background: bubbleThemeCss(t) }} />
              <span className={styles.swatchLabel}>{t.displayName}</span>
            </div>
          ))}
        </div>
        <div className={styles.subLabel}>Ombre</div>
        <div className={styles.swatchGrid}>
          {bubbleOmbre.map((t) => (
            <div
              key={t.id}
              className={t.id === chat.theme.bubbleThemeId ? styles.swatchSelected : styles.swatch}
              onClick={() => chat.saveTheme({ bubbleThemeId: t.id })}
            >
              <div className={styles.swatchPreview} style={{ background: bubbleThemeCss(t) }} />
              <span className={styles.swatchLabel}>{t.displayName}</span>
            </div>
          ))}
        </div>

        <hr className={styles.divider} />

        <div className={styles.sectionTitle}>UI THEME</div>
        <div className={styles.hint}>Accent for the left panel, bottom nav & chat header</div>
        <div className={styles.swatchGrid}>
          {catalog.uiThemes.map((t) => (
            <div
              key={t.id}
              className={t.id === chat.theme.uiThemeId ? styles.swatchSelectedDark : styles.swatch}
              onClick={() => chat.saveTheme({ uiThemeId: t.id })}
            >
              <div className={styles.uiSwatchPreview} style={{ background: t.accent }} />
              <span className={styles.swatchLabel}>{t.displayName}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
