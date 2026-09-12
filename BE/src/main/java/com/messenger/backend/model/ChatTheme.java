package com.messenger.backend.model;

import java.util.List;

/**
 * Central place for all color palettes in the app:
 * - app background — a choice of 2/3/4-color gradients
 * - chat bubble themes (10 themes: 5 solid + 5 ombre)
*/
public class ChatTheme {

    // ============================================================
    // BASE PALETTE "Lilac Dreamer"
    // ============================================================
    public static final String LILAC_1 = "#C8CEEE"; // light lilac-blue
    public static final String LILAC_2 = "#E8DAF0"; // light lilac
    public static final String LILAC_3 = "#F3E4F5"; // near-white lilac (app background)
    public static final String LILAC_4 = "#FCDCE1"; // pastel pink
    public static final String LILAC_5 = "#D8BEE5"; // lilac
    public static final String LILAC_6 = "#A7ABDE"; // more saturated lilac-blue

    public static final String[] BASE_PALETTE = {
            LILAC_1, LILAC_2, LILAC_3, LILAC_4, LILAC_5, LILAC_6
    };

    // ============================================================
    // BASE PALETTE "Sky" — the hex values were pulled directly from
    // the pixels of a palette photo uploaded by the user (not eyeballed).
    // Replaces the old green "Sage" palette everywhere — background,
    // bubbles AND UI theme, not just UI theme like before.
    // ============================================================
    public static final String SKY_1 = "#D9F0F6"; // lightest, icy blue-cyan
    public static final String SKY_2 = "#D5E3F0"; // light blue
    public static final String SKY_3 = "#C9D9F0"; // light periwinkle
    public static final String SKY_4 = "#C1D5F0"; // periwinkle
    public static final String SKY_5 = "#B3CBEF"; // medium-saturated periwinkle-blue
    public static final String SKY_6 = "#A8B5E1"; // most saturated periwinkle-indigo

    public static final String[] SKY_PALETTE = {
            SKY_1, SKY_2, SKY_3, SKY_4, SKY_5, SKY_6
    };

    // ============================================================
    // BASE PALETTE "Vivid" — bright, saturated colors (unlike the
    // pastel Lilac/Sky above), gradient yellow → orange → red →
    // magenta → violet → blue, added by user request for brighter themes.
    // ============================================================
    public static final String VIVID_1 = "#FFD93B"; // sunny yellow
    public static final String VIVID_2 = "#FFA630"; // tangerine
    public static final String VIVID_3 = "#FF6B4A"; // coral orange
    public static final String VIVID_4 = "#F03A47"; // poppy/red
    public static final String VIVID_5 = "#E91E8C"; // magenta
    public static final String VIVID_6 = "#B32FD4"; // orchid
    public static final String VIVID_7 = "#7B3FE4"; // violet
    public static final String VIVID_8 = "#3D6FE0"; // electric blue

    public static final String[] VIVID_PALETTE = {
            VIVID_1, VIVID_2, VIVID_3, VIVID_4, VIVID_5, VIVID_6, VIVID_7, VIVID_8
    };

    // ============================================================
    // "Vivid" MUTED FOR BACKGROUND — the VIVID_* above are at full
    // saturation, good for small surfaces (bubbles, UI accent swatch), but
    // they flood the whole chat screen and make text unreadable. These
    // versions are the same hues, blended ~35% toward white — just enough to
    // drop to the brightness level of the left panel (the pastel LILAC/SKY
    // backgrounds), without becoming washed-out pastel.
    // Used ONLY for BackgroundTheme, never for bubble/UI.
    // ============================================================
    public static final String VIVID_BG_1 = "#FFE680"; // soft sunny yellow
    public static final String VIVID_BG_2 = "#FFC578"; // soft tangerine
    public static final String VIVID_BG_3 = "#FF9F89"; // soft coral
    public static final String VIVID_BG_4 = "#F57F87"; // soft red/poppy
    public static final String VIVID_BG_5 = "#F16DB4"; // soft magenta
    public static final String VIVID_BG_6 = "#CE78E3"; // soft orchid
    public static final String VIVID_BG_7 = "#A982ED"; // soft violet
    public static final String VIVID_BG_8 = "#81A1EB"; // soft electric blue

    // ============================================================
    // 6 NEW non-pastel hues (outside Vivid's yellow→blue spectrum) —
    // directly calibrated to the same ~35%-toward-white brightness level, so
    // we don't repeat the mistake of a too-saturated full-screen background.
    // ============================================================
    public static final String VIVID_BG_TEAL      = "#66D1C5"; // teal
    public static final String VIVID_BG_EMERALD   = "#6FD996"; // emerald green
    public static final String VIVID_BG_LIME      = "#C3EF7C"; // lime
    public static final String VIVID_BG_ROSE      = "#F88296"; // rose
    public static final String VIVID_BG_TURQUOISE = "#5DD0E3"; // turquoise
    public static final String VIVID_BG_AMBER     = "#F9C060"; // amber yellow

    // Text over a light pastel background — dark lilac-gray, not black (softer contrast)
    public static final String TEXT_DARK   = "#3d3458";
    public static final String TEXT_MUTED  = "#7a6f94";
    public static final String TEXT_ON_BUBBLE = "#2d2640"; // text over the pastel bubbles

    // ============================================================
    // PASTEL SEMANTIC COLORS (success / danger) — same semantics
    // (green = good, red = dangerous), but softened tones so they
    // blend with the rest of the palette instead of standing out as "unfinished"
    // bright buttons on a pastel background.
    // ============================================================
    public static final String SUCCESS_SOFT       = "#9FE0C7"; // soft pastel green (accept buttons)
    public static final String SUCCESS_SOFT_TEXT  = "#1f6e54"; // dark green text for contrast
    public static final String DANGER_SOFT        = "#F2A8B0"; // soft pastel coral (decline/block/delete)
    public static final String DANGER_SOFT_TEXT   = "#7a2330"; // dark maroon text for contrast
    public static final String DANGER_SOFT_BG      = "#FCE8EA"; // very light background for the Danger Zone card

    // Enum shared between bubble and background themes
    public enum BubbleThemeType { SOLID, OMBRE }

    // ============================================================
    // APP / CHAT BACKGROUND THEMES — solid + ombre, picked by click
    // (same model as the bubble themes, for consistent UX)
    // ============================================================

    public static class BackgroundTheme {
        public final String id;
        public final String displayName;
        public final BubbleThemeType type; // reuses the SOLID/OMBRE enum
        public final String[] colors;

        public BackgroundTheme(String id, String displayName, BubbleThemeType type, String[] colors) {
            this.id = id;
            this.displayName = displayName;
            this.type = type;
            this.colors = colors;
        }

        public String toCss() {
            if (type == BubbleThemeType.SOLID) {
                return "-fx-background-color: " + colors[0] + ";";
            }
            return "-fx-background-color: " + toCssLinearGradient(colors, "to bottom right") + ";";
        }
    }

    // ── 4 lightest solid backgrounds (the saturated ones are reserved for bubbles) ──
    private static final BackgroundTheme BG_SOLID_PERIWINKLE = new BackgroundTheme(
            "bg_solid_periwinkle", "Periwinkle", BubbleThemeType.SOLID, new String[]{LILAC_1});
    private static final BackgroundTheme BG_SOLID_LILAC = new BackgroundTheme(
            "bg_solid_lilac", "Lilac", BubbleThemeType.SOLID, new String[]{LILAC_2});
    private static final BackgroundTheme BG_SOLID_MIST = new BackgroundTheme(
            "bg_solid_mist", "Mist", BubbleThemeType.SOLID, new String[]{LILAC_3});
    private static final BackgroundTheme BG_SOLID_BLUSH = new BackgroundTheme(
            "bg_solid_blush", "Blush", BubbleThemeType.SOLID, new String[]{LILAC_4});

    // ── Ombre backgrounds (kept from before, now selectable via swatch) ──
    private static final BackgroundTheme BG_OMBRE_2 = new BackgroundTheme(
            "bg_ombre_2", "Soft Fade", BubbleThemeType.OMBRE, new String[]{LILAC_1, LILAC_4});
    private static final BackgroundTheme BG_OMBRE_3 = new BackgroundTheme(
            "bg_ombre_3", "Dreamer", BubbleThemeType.OMBRE, new String[]{LILAC_1, LILAC_3, LILAC_4});
    private static final BackgroundTheme BG_OMBRE_4 = new BackgroundTheme(
            "bg_ombre_4", "Twilight Sky", BubbleThemeType.OMBRE, new String[]{LILAC_6, LILAC_5, LILAC_3, LILAC_4});

    // ── "Sky" solid + ombre backgrounds (the new palette, replaces the green one) ──
    private static final BackgroundTheme BG_SOLID_SKY_ICE = new BackgroundTheme(
            "bg_solid_sky_ice", "Sky Ice", BubbleThemeType.SOLID, new String[]{SKY_1});
    private static final BackgroundTheme BG_SOLID_SKY = new BackgroundTheme(
            "bg_solid_sky", "Sky", BubbleThemeType.SOLID, new String[]{SKY_3});
    private static final BackgroundTheme BG_OMBRE_SKY = new BackgroundTheme(
            "bg_ombre_sky", "Ocean Breeze", BubbleThemeType.OMBRE, new String[]{SKY_1, SKY_6});

    // ── "Vivid" solid + ombre backgrounds — muted VIVID_BG_* hues, not
    // the raw VIVID_* ones (those flooded the whole screen and drowned the text) ──
    private static final BackgroundTheme BG_SOLID_SUNFLOWER = new BackgroundTheme(
            "bg_solid_sunflower", "Sunflower", BubbleThemeType.SOLID, new String[]{VIVID_BG_1});
    private static final BackgroundTheme BG_SOLID_COBALT = new BackgroundTheme(
            "bg_solid_cobalt", "Cobalt", BubbleThemeType.SOLID, new String[]{VIVID_BG_8});
    private static final BackgroundTheme BG_OMBRE_TROPICAL_SUNSET = new BackgroundTheme(
            "bg_ombre_tropical_sunset", "Tropical Sunset", BubbleThemeType.OMBRE,
            new String[]{VIVID_BG_1, VIVID_BG_3, VIVID_BG_5});
    private static final BackgroundTheme BG_OMBRE_COSMIC_FADE = new BackgroundTheme(
            "bg_ombre_cosmic_fade", "Cosmic Fade", BubbleThemeType.OMBRE,
            new String[]{VIVID_BG_6, VIVID_BG_7, VIVID_BG_8});
    private static final BackgroundTheme BG_OMBRE_FULL_SPECTRUM = new BackgroundTheme(
            "bg_ombre_full_spectrum", "Full Spectrum", BubbleThemeType.OMBRE,
            new String[]{VIVID_BG_1, VIVID_BG_4, VIVID_BG_6, VIVID_BG_8});

    // ── 6 new non-pastel solid backgrounds (same muted brightness level) ──
    private static final BackgroundTheme BG_SOLID_TEAL = new BackgroundTheme(
            "bg_solid_teal", "Teal", BubbleThemeType.SOLID, new String[]{VIVID_BG_TEAL});
    private static final BackgroundTheme BG_SOLID_EMERALD = new BackgroundTheme(
            "bg_solid_emerald", "Emerald", BubbleThemeType.SOLID, new String[]{VIVID_BG_EMERALD});
    private static final BackgroundTheme BG_SOLID_LIME = new BackgroundTheme(
            "bg_solid_lime", "Lime", BubbleThemeType.SOLID, new String[]{VIVID_BG_LIME});
    private static final BackgroundTheme BG_SOLID_ROSE = new BackgroundTheme(
            "bg_solid_rose", "Rose", BubbleThemeType.SOLID, new String[]{VIVID_BG_ROSE});
    private static final BackgroundTheme BG_SOLID_TURQUOISE = new BackgroundTheme(
            "bg_solid_turquoise", "Turquoise", BubbleThemeType.SOLID, new String[]{VIVID_BG_TURQUOISE});
    private static final BackgroundTheme BG_SOLID_AMBER = new BackgroundTheme(
            "bg_solid_amber", "Amber", BubbleThemeType.SOLID, new String[]{VIVID_BG_AMBER});

    // FE/src/pages/chat/AppearanceOverlay.tsx keeps a HIDDEN_THEME_IDS set
    // that hides a few near-duplicate hues from the picker by id. Adding a
    // theme here doesn't update that set automatically — if the new entry
    // visually duplicates an existing one, hide it there too.
    public static final List<BackgroundTheme> ALL_BACKGROUND_THEMES = List.of(
            BG_SOLID_PERIWINKLE, BG_SOLID_LILAC, BG_SOLID_MIST, BG_SOLID_BLUSH,
            BG_SOLID_SKY_ICE, BG_SOLID_SKY, BG_SOLID_SUNFLOWER, BG_SOLID_COBALT,
            BG_SOLID_TEAL, BG_SOLID_EMERALD, BG_SOLID_LIME,
            BG_SOLID_ROSE, BG_SOLID_TURQUOISE, BG_SOLID_AMBER,
            BG_OMBRE_2, BG_OMBRE_3, BG_OMBRE_4, BG_OMBRE_SKY,
            BG_OMBRE_TROPICAL_SUNSET, BG_OMBRE_COSMIC_FADE, BG_OMBRE_FULL_SPECTRUM
    );

    // At the user's request: the "dev" account set Sky/Electric Blue and
    // asked for this to become the app-wide default instead of the previous
    // pastel "Dreamer" (bg_ombre_3).
    public static final String DEFAULT_BACKGROUND_THEME_ID = "bg_solid_sky";

    public static BackgroundTheme getBackgroundThemeById(String id) {
        if (id == null) return getDefaultBackgroundTheme();
        for (BackgroundTheme t : ALL_BACKGROUND_THEMES) {
            if (t.id.equals(id)) return t;
        }
        return getDefaultBackgroundTheme();
    }

    public static BackgroundTheme getDefaultBackgroundTheme() {
        return ALL_BACKGROUND_THEMES.stream()
                .filter(t -> t.id.equals(DEFAULT_BACKGROUND_THEME_ID))
                .findFirst()
                .orElse(ALL_BACKGROUND_THEMES.get(0));
    }

    public static boolean isValidBackgroundThemeId(String id) {
        return ALL_BACKGROUND_THEMES.stream().anyMatch(t -> t.id.equals(id));
    }

    // Helper method: turns an array of HEX colors into a CSS linear-gradient string
    public static String toCssLinearGradient(String[] colors, String direction) {
        StringBuilder sb = new StringBuilder("linear-gradient(" + direction);
        for (String c : colors) {
            sb.append(", ").append(c);
        }
        sb.append(")");
        return sb.toString();
    }

    // ============================================================
    // BUBBLE THEMES — 10 themes (5 solid + 5 ombre)
    // ============================================================

    public static class BubbleTheme {
        public final String id;
        public final String displayName;
        public final BubbleThemeType type;
        public final String[] colors;     // 1 color for SOLID, 2 for OMBRE
        public final String textColor;    // text color over the bubble

        public BubbleTheme(String id, String displayName, BubbleThemeType type, String[] colors, String textColor) {
            this.id = id;
            this.displayName = displayName;
            this.type = type;
            this.colors = colors;
            this.textColor = textColor;
        }

        // CSS -fx-background-color value (solid color or linear-gradient)
        public String toFxBackground() {
            if (type == BubbleThemeType.SOLID) {
                return colors[0];
            }
            return "linear-gradient(to bottom right, " + colors[0] + ", " + colors[1] + ")";
        }
    }

    // ── 5 pastel solid themes ────────────────────────────
    private static final BubbleTheme SOLID_LAVENDER = new BubbleTheme(
            "solid_lavender", "Lavender", BubbleThemeType.SOLID,
            new String[]{"#C8CEEE"}, TEXT_ON_BUBBLE);

    private static final BubbleTheme SOLID_BLUSH = new BubbleTheme(
            "solid_blush", "Blush", BubbleThemeType.SOLID,
            new String[]{"#FCDCE1"}, TEXT_ON_BUBBLE);

    private static final BubbleTheme SOLID_ORCHID = new BubbleTheme(
            "solid_orchid", "Orchid", BubbleThemeType.SOLID,
            new String[]{"#D8BEE5"}, TEXT_ON_BUBBLE);

    private static final BubbleTheme SOLID_PERIWINKLE = new BubbleTheme(
            "solid_periwinkle", "Periwinkle", BubbleThemeType.SOLID,
            new String[]{"#A7ABDE"}, "#FFFFFF"); // darker pastel -> white text

    private static final BubbleTheme SOLID_MINT = new BubbleTheme(
            "solid_mint", "Mint", BubbleThemeType.SOLID,
            new String[]{"#C2F0E0"}, TEXT_ON_BUBBLE);

    private static final BubbleTheme SOLID_SKY = new BubbleTheme(
            "solid_sky", "Sky", BubbleThemeType.SOLID,
            new String[]{SKY_6}, "#FFFFFF"); // saturated periwinkle-indigo -> white text

    // ── 4 bright solid themes ("Vivid" palette) ────────────────
    private static final BubbleTheme SOLID_SUNFLOWER = new BubbleTheme(
            "solid_sunflower", "Sunflower", BubbleThemeType.SOLID,
            new String[]{VIVID_1}, TEXT_ON_BUBBLE);

    private static final BubbleTheme SOLID_POPPY = new BubbleTheme(
            "solid_poppy", "Poppy", BubbleThemeType.SOLID,
            new String[]{VIVID_4}, "#FFFFFF");

    private static final BubbleTheme SOLID_MAGENTA = new BubbleTheme(
            "solid_magenta", "Magenta", BubbleThemeType.SOLID,
            new String[]{VIVID_5}, "#FFFFFF");

    private static final BubbleTheme SOLID_ELECTRIC_BLUE = new BubbleTheme(
            "solid_electric_blue", "Electric Blue", BubbleThemeType.SOLID,
            new String[]{VIVID_8}, "#FFFFFF");

    // ── 5 pastel ombre themes ──────────────────────────────────
    private static final BubbleTheme OMBRE_SUNSET = new BubbleTheme(
            "ombre_sunset", "Sunset Glow", BubbleThemeType.OMBRE,
            new String[]{"#FCDCE1", "#D8BEE5"}, TEXT_ON_BUBBLE);

    private static final BubbleTheme OMBRE_TWILIGHT = new BubbleTheme(
            "ombre_twilight", "Twilight", BubbleThemeType.OMBRE,
            new String[]{"#A7ABDE", "#D8BEE5"}, "#FFFFFF");

    private static final BubbleTheme OMBRE_COTTON_CANDY = new BubbleTheme(
            "ombre_cotton_candy", "Cotton Candy", BubbleThemeType.OMBRE,
            new String[]{"#C8CEEE", "#FCDCE1"}, TEXT_ON_BUBBLE);

    private static final BubbleTheme OMBRE_PEACH_LILAC = new BubbleTheme(
            "ombre_peach_lilac", "Peach Lilac", BubbleThemeType.OMBRE,
            new String[]{"#FFE0CC", "#E8DAF0"}, TEXT_ON_BUBBLE);

    private static final BubbleTheme OMBRE_AURORA = new BubbleTheme(
            "ombre_aurora", "Aurora", BubbleThemeType.OMBRE,
            new String[]{"#C2F0E0", "#A7ABDE"}, "#FFFFFF");

    private static final BubbleTheme OMBRE_SKY = new BubbleTheme(
            "ombre_sky", "Ocean Breeze", BubbleThemeType.OMBRE,
            new String[]{SKY_1, SKY_6}, TEXT_ON_BUBBLE);

    // ── 6 bright ombre themes ("Vivid" palette, 2 colors each) ──────
    private static final BubbleTheme OMBRE_GOLDEN_HOUR = new BubbleTheme(
            "ombre_golden_hour", "Golden Hour", BubbleThemeType.OMBRE,
            new String[]{VIVID_1, VIVID_2}, TEXT_ON_BUBBLE);

    private static final BubbleTheme OMBRE_BLAZING_SUNSET = new BubbleTheme(
            "ombre_blazing_sunset", "Blazing Sunset", BubbleThemeType.OMBRE,
            new String[]{VIVID_2, VIVID_4}, "#FFFFFF");

    private static final BubbleTheme OMBRE_FUCHSIA_FIRE = new BubbleTheme(
            "ombre_fuchsia_fire", "Fuchsia Fire", BubbleThemeType.OMBRE,
            new String[]{VIVID_4, VIVID_5}, "#FFFFFF");

    private static final BubbleTheme OMBRE_BERRY_PUNCH = new BubbleTheme(
            "ombre_berry_punch", "Berry Punch", BubbleThemeType.OMBRE,
            new String[]{VIVID_5, VIVID_6}, "#FFFFFF");

    private static final BubbleTheme OMBRE_COSMIC_VIOLET = new BubbleTheme(
            "ombre_cosmic_violet", "Cosmic Violet", BubbleThemeType.OMBRE,
            new String[]{VIVID_6, VIVID_7}, "#FFFFFF");

    private static final BubbleTheme OMBRE_ELECTRIC_DUSK = new BubbleTheme(
            "ombre_electric_dusk", "Electric Dusk", BubbleThemeType.OMBRE,
            new String[]{VIVID_7, VIVID_8}, "#FFFFFF");

    // See the HIDDEN_THEME_IDS note on ALL_BACKGROUND_THEMES above — same
    // applies here.
    public static final List<BubbleTheme> ALL_BUBBLE_THEMES = List.of(
            SOLID_LAVENDER, SOLID_BLUSH, SOLID_ORCHID, SOLID_PERIWINKLE, SOLID_MINT, SOLID_SKY,
            SOLID_SUNFLOWER, SOLID_POPPY, SOLID_MAGENTA, SOLID_ELECTRIC_BLUE,
            OMBRE_SUNSET, OMBRE_TWILIGHT, OMBRE_COTTON_CANDY, OMBRE_PEACH_LILAC, OMBRE_AURORA, OMBRE_SKY,
            OMBRE_GOLDEN_HOUR, OMBRE_BLAZING_SUNSET, OMBRE_FUCHSIA_FIRE,
            OMBRE_BERRY_PUNCH, OMBRE_COSMIC_VIOLET, OMBRE_ELECTRIC_DUSK
    );

    public static final String DEFAULT_BUBBLE_THEME_ID = "solid_electric_blue";

    public static BubbleTheme getBubbleThemeById(String id) {
        if (id == null) return getDefaultBubbleTheme();
        for (BubbleTheme t : ALL_BUBBLE_THEMES) {
            if (t.id.equals(id)) return t;
        }
        return getDefaultBubbleTheme();
    }

    public static BubbleTheme getDefaultBubbleTheme() {
        return ALL_BUBBLE_THEMES.stream()
                .filter(t -> t.id.equals(DEFAULT_BUBBLE_THEME_ID))
                .findFirst()
                .orElse(ALL_BUBBLE_THEMES.get(0));
    }

    public static boolean isValidBubbleThemeId(String id) {
        return ALL_BUBBLE_THEMES.stream().anyMatch(t -> t.id.equals(id));
    }

    // ============================================================
    // UI THEME — swatch selection of ONE accent color for the UI chrome
    // (left panel / bottom-nav / chat header), NOT for bubble/background themes.
    // From this single accent color, Main.java programmatically derives the
    // needed lighter shades (panel background, nav background, active-row highlight) —
    // that's why we only keep 1 hex color per theme here, not a whole palette.
    // ============================================================
    public static class UiTheme {
        public final String id;
        public final String displayName;
        public final String accent; // hex

        public UiTheme(String id, String displayName, String accent) {
            this.id = id;
            this.displayName = displayName;
            this.accent = accent;
        }
    }

    // See the HIDDEN_THEME_IDS note on ALL_BACKGROUND_THEMES above — same
    // applies here.
    public static final List<UiTheme> ALL_UI_THEMES = List.of(
            new UiTheme("ui_lilac", "Lilac", LILAC_6),
            new UiTheme("ui_periwinkle", "Periwinkle", LILAC_1),
            new UiTheme("ui_soft_lilac", "Soft Lilac", LILAC_2),
            new UiTheme("ui_cloud", "Cloud", LILAC_3),
            new UiTheme("ui_blush", "Blush", LILAC_4),
            new UiTheme("ui_orchid", "Orchid", LILAC_5),
            new UiTheme("ui_sky_1", "Sky Ice", SKY_1),
            new UiTheme("ui_sky_2", "Pale Sky", SKY_2),
            new UiTheme("ui_sky_3", "Sky", SKY_3),
            new UiTheme("ui_sky_4", "Soft Sky", SKY_4),
            new UiTheme("ui_sky_5", "Ocean", SKY_5),
            new UiTheme("ui_sky_6", "Deep Sky", SKY_6),
            new UiTheme("ui_vivid_poppy", "Poppy", VIVID_4),
            new UiTheme("ui_vivid_violet", "Violet", VIVID_7),
            new UiTheme("ui_vivid_electric_blue", "Electric Blue", VIVID_8)
    );

    public static final String DEFAULT_UI_THEME_ID = "ui_vivid_electric_blue";

    public static UiTheme getUiThemeById(String id) {
        if (id == null) return getDefaultUiTheme();
        for (UiTheme t : ALL_UI_THEMES) {
            if (t.id.equals(id)) return t;
        }
        return getDefaultUiTheme();
    }

    public static UiTheme getDefaultUiTheme() {
        return ALL_UI_THEMES.stream()
                .filter(t -> t.id.equals(DEFAULT_UI_THEME_ID))
                .findFirst()
                .orElse(ALL_UI_THEMES.get(0));
    }

    public static boolean isValidUiThemeId(String id) {
        return ALL_UI_THEMES.stream().anyMatch(t -> t.id.equals(id));
    }
}
