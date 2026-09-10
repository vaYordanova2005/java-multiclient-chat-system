import { useEffect, useState } from 'react';
import { apiBase } from '../api/client';
import type { ThemeCatalog } from './catalog';

const FALLBACK: ThemeCatalog = {
  bubbleThemes: [],
  backgroundThemes: [],
  uiThemes: [],
  defaults: { bubbleThemeId: 'solid_periwinkle', backgroundThemeId: 'bg_ombre_3', uiThemeId: 'ui_lilac' },
};

// Fetches the bubble/background/UI theme catalogs from GET /api/themes once
// at startup, per BE/README.md's guidance — these ~300 lines of color data
// live only in BE/model/ChatTheme.java and shouldn't be re-typed in TS.
export function useThemeCatalog(): { catalog: ThemeCatalog; loading: boolean } {
  const [catalog, setCatalog] = useState<ThemeCatalog>(FALLBACK);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    fetch(`${apiBase()}/api/themes`)
      .then((res) => res.json())
      .then((data: ThemeCatalog) => {
        if (!cancelled) setCatalog(data);
      })
      .catch(() => {
        // Keep FALLBACK defaults — enough to render a plausible UI even if
        // BE is unreachable; swatches will just be empty until it's up.
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return { catalog, loading };
}
