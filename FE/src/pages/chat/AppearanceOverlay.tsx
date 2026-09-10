import { useEffect, useRef } from 'react';
import type { ChatController } from '../../chat/useChat';
import type { ThemeCatalog } from '../../theme/catalog';
import { backgroundThemeCss, bubbleThemeCss } from '../../theme/catalog';
import styles from './AppearanceOverlay.module.css';

// The "Lilac" and "Sky" base palettes each ended up with a step (LILAC_6
// #A7ABDE / SKY_6 #A8B5E1) that's visually indistinguishable, so that one
// near-duplicate hue shows up under several ids across sections. Hide the
// redundant entry in each spot rather than two swatches that look identical.
const HIDDEN_THEME_IDS = new Set([
  'bg_solid_lilac', // legacy/Main.java: duplicates the left-panel default
  'bg_solid_periwinkle', // near-identical to bg_solid_sky
  'bg_ombre_3', // "Dreamer" ~= "Soft Fade" (bg_ombre_2) with an imperceptible middle stop
  'solid_periwinkle', // near-identical to solid_sky
  'ombre_aurora', // near-identical to ombre_sky ("Ocean Breeze")
  'ui_lilac', // near-identical to ui_sky_6 ("Deep Sky")
]);

export default function AppearanceOverlay({
  chat,
  catalog,
  onClose,
}: {
  chat: ChatController;
  catalog: ThemeCatalog;
  onClose: () => void;
}) {
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handlePointerDown = (e: MouseEvent) => {
      const target = e.target as Node;
      if (panelRef.current?.contains(target)) return;
      // The toggle button that opens this overlay handles its own open/close
      // state — let its onClick run instead of racing it here.
      if (target instanceof Element && target.closest('[data-appearance-toggle]')) return;
      onClose();
    };
    document.addEventListener('mousedown', handlePointerDown);
    return () => document.removeEventListener('mousedown', handlePointerDown);
  }, [onClose]);

  const backgrounds = catalog.backgroundThemes.filter((t) => !HIDDEN_THEME_IDS.has(t.id));
  const bgSolid = backgrounds.filter((t) => t.type === 'SOLID');
  const bgOmbre = backgrounds.filter((t) => t.type === 'OMBRE');
  const bubbleThemes = catalog.bubbleThemes.filter((t) => !HIDDEN_THEME_IDS.has(t.id));
  const bubbleSolid = bubbleThemes.filter((t) => t.type === 'SOLID');
  const bubbleOmbre = bubbleThemes.filter((t) => t.type === 'OMBRE');
  const uiThemes = catalog.uiThemes.filter((t) => !HIDDEN_THEME_IDS.has(t.id));

  return (
    <div className={styles.wrapper}>
      <div className={styles.panel} ref={panelRef}>
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
          {uiThemes.map((t) => (
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
