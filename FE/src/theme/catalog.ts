// Mirrors BE/web/ThemeController.java's ThemeCatalog response, which wraps
// BE/model/ChatTheme.java's BubbleTheme/BackgroundTheme/UiTheme records.
export type ThemeKind = 'SOLID' | 'OMBRE';

export interface BubbleTheme {
  id: string;
  displayName: string;
  type: ThemeKind;
  colors: string[];
  textColor: string;
}

export interface BackgroundTheme {
  id: string;
  displayName: string;
  type: ThemeKind;
  colors: string[];
}

export interface UiTheme {
  id: string;
  displayName: string;
  accent: string;
}

export interface ThemeDefaults {
  bubbleThemeId: string;
  backgroundThemeId: string;
  uiThemeId: string;
}

export interface ThemeCatalog {
  bubbleThemes: BubbleTheme[];
  backgroundThemes: BackgroundTheme[];
  uiThemes: UiTheme[];
  defaults: ThemeDefaults;
}

export function bubbleThemeCss(theme: BubbleTheme | undefined): string {
  if (!theme) return '#A7ABDE';
  if (theme.type === 'SOLID') return theme.colors[0]!;
  return `linear-gradient(135deg, ${theme.colors[0]}, ${theme.colors[1]})`;
}

export function backgroundThemeCss(theme: BackgroundTheme | undefined): string {
  if (!theme) return '#F3E4F5';
  if (theme.type === 'SOLID') return theme.colors[0]!;
  return `linear-gradient(135deg, ${theme.colors.join(', ')})`;
}

// Ports legacy/Main.java's tintTowardWhite/isLightColor — derives the left
// panel / bottom-nav / active-row shades from a single UI theme accent hex.
export function tintTowardWhite(hex: string, towardWhite: number): string {
  const { r, g, b } = hexToRgb(hex);
  const mix = (c: number) => Math.round(c + (255 - c) * towardWhite);
  return rgbToHex(mix(r), mix(g), mix(b));
}

export function isLightColor(hex: string): boolean {
  const { r, g, b } = hexToRgb(hex);
  const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  return luminance > 0.68;
}

function hexToRgb(hex: string): { r: number; g: number; b: number } {
  const clean = hex.replace('#', '');
  return {
    r: parseInt(clean.substring(0, 2), 16),
    g: parseInt(clean.substring(2, 4), 16),
    b: parseInt(clean.substring(4, 6), 16),
  };
}

function rgbToHex(r: number, g: number, b: number): string {
  return `#${[r, g, b].map((c) => c.toString(16).padStart(2, '0')).join('')}`.toUpperCase();
}

export interface UiThemeColors {
  accent: string;
  panelBg: string;
  navBg: string;
  rowActiveBg: string;
  accentText: string;
}

export function deriveUiThemeColors(accentHex: string): UiThemeColors {
  return {
    accent: accentHex,
    panelBg: tintTowardWhite(accentHex, 0.78),
    navBg: tintTowardWhite(accentHex, 0.68),
    rowActiveBg: tintTowardWhite(accentHex, 0.35),
    accentText: isLightColor(accentHex) ? 'var(--text-dark)' : '#FFFFFF',
  };
}
