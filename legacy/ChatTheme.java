import java.util.List;

/**
 * Централно място за всички цветови палитри в приложението:
 * - фон на приложението (app background) — избор от 2/3/4 цвета градиент
 * - теми на чат-балончетата (10 теми: 5 едноцветни + 5 омбре)
*/
public class ChatTheme {

    // ============================================================
    // БАЗОВА ПАЛИТРА "Lilac Dreamer"
    // ============================================================
    public static final String LILAC_1 = "#C8CEEE"; // светло лилаво-синьо
    public static final String LILAC_2 = "#E8DAF0"; // светло лилаво
    public static final String LILAC_3 = "#F3E4F5"; // почти бяло лилаво (app background)
    public static final String LILAC_4 = "#FCDCE1"; // пастелно розово
    public static final String LILAC_5 = "#D8BEE5"; // лилаво
    public static final String LILAC_6 = "#A7ABDE"; // по-наситено лилаво-синьо

    public static final String[] BASE_PALETTE = {
            LILAC_1, LILAC_2, LILAC_3, LILAC_4, LILAC_5, LILAC_6
    };

    // ============================================================
    // БАЗОВА ПАЛИТРА "Sky" — hex стойностите са извадени директно от
    // пикселите на палитра-снимка, качена от потребителя (не на око).
    // Заменя старата зелена "Sage" палитра навсякъде — background,
    // balloons И UI theme, а не само UI theme както преди.
    // ============================================================
    public static final String SKY_1 = "#D9F0F6"; // най-светло, ледено синьо-циан
    public static final String SKY_2 = "#D5E3F0"; // светло синьо
    public static final String SKY_3 = "#C9D9F0"; // светъл periwinkle
    public static final String SKY_4 = "#C1D5F0"; // periwinkle
    public static final String SKY_5 = "#B3CBEF"; // средно наситен periwinkle-син
    public static final String SKY_6 = "#A8B5E1"; // най-наситен periwinkle-индиго

    public static final String[] SKY_PALETTE = {
            SKY_1, SKY_2, SKY_3, SKY_4, SKY_5, SKY_6
    };

    // Текст върху светъл пастелен фон — тъмно лилаво-сиво, не черно (по-мек контраст)
    public static final String TEXT_DARK   = "#3d3458";
    public static final String TEXT_MUTED  = "#7a6f94";
    public static final String TEXT_ON_BUBBLE = "#2d2640"; // текст върху пастелните балончета

    // ============================================================
    // ПАСТЕЛНИ SEMANTIC ЦВЕТОВЕ (success / danger) — same семантика
    // (зелено = добро, червено = опасно), но омекотени тонове, за да се
    // blend-ват с останалата палитра вместо да стоят като "недовършени"
    // ярки бутони върху пастелен фон.
    // ============================================================
    public static final String SUCCESS_SOFT       = "#9FE0C7"; // мек пастелен зелен (accept бутони)
    public static final String SUCCESS_SOFT_TEXT  = "#1f6e54"; // тъмно зелен текст за контраст
    public static final String DANGER_SOFT        = "#F2A8B0"; // мек пастелен корал (decline/block/delete)
    public static final String DANGER_SOFT_TEXT   = "#7a2330"; // тъмно бордо текст за контраст
    public static final String DANGER_SOFT_BG      = "#FCE8EA"; // много светъл фон за Danger Zone карта

    // Споделен enum между bubble и background темите
    public enum BubbleThemeType { SOLID, OMBRE }

    // ============================================================
    // APP / CHAT BACKGROUND THEMES — solid + ombre, избор чрез клик
    // (същия модел като bubble темите, за консистентен UX)
    // ============================================================

    public static class BackgroundTheme {
        public final String id;
        public final String displayName;
        public final BubbleThemeType type; // преизползваме SOLID/OMBRE enum-а
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

    // ── 4 най-светли solid фона (наситените са оставени за балончета) ──
    private static final BackgroundTheme BG_SOLID_PERIWINKLE = new BackgroundTheme(
            "bg_solid_periwinkle", "Periwinkle", BubbleThemeType.SOLID, new String[]{LILAC_1});
    private static final BackgroundTheme BG_SOLID_LILAC = new BackgroundTheme(
            "bg_solid_lilac", "Lilac", BubbleThemeType.SOLID, new String[]{LILAC_2});
    private static final BackgroundTheme BG_SOLID_MIST = new BackgroundTheme(
            "bg_solid_mist", "Mist", BubbleThemeType.SOLID, new String[]{LILAC_3});
    private static final BackgroundTheme BG_SOLID_BLUSH = new BackgroundTheme(
            "bg_solid_blush", "Blush", BubbleThemeType.SOLID, new String[]{LILAC_4});

    // ── Омбре фонове (запазени от преди, вече избираеми чрез swatch) ──
    private static final BackgroundTheme BG_OMBRE_2 = new BackgroundTheme(
            "bg_ombre_2", "Soft Fade", BubbleThemeType.OMBRE, new String[]{LILAC_1, LILAC_4});
    private static final BackgroundTheme BG_OMBRE_3 = new BackgroundTheme(
            "bg_ombre_3", "Dreamer", BubbleThemeType.OMBRE, new String[]{LILAC_1, LILAC_3, LILAC_4});
    private static final BackgroundTheme BG_OMBRE_4 = new BackgroundTheme(
            "bg_ombre_4", "Twilight Sky", BubbleThemeType.OMBRE, new String[]{LILAC_6, LILAC_5, LILAC_3, LILAC_4});

    // ── "Sky" solid + ombre фонове (новата палитра, заменя зеленото) ──
    private static final BackgroundTheme BG_SOLID_SKY_ICE = new BackgroundTheme(
            "bg_solid_sky_ice", "Sky Ice", BubbleThemeType.SOLID, new String[]{SKY_1});
    private static final BackgroundTheme BG_SOLID_SKY = new BackgroundTheme(
            "bg_solid_sky", "Sky", BubbleThemeType.SOLID, new String[]{SKY_3});
    private static final BackgroundTheme BG_OMBRE_SKY = new BackgroundTheme(
            "bg_ombre_sky", "Ocean Breeze", BubbleThemeType.OMBRE, new String[]{SKY_1, SKY_6});

    public static final List<BackgroundTheme> ALL_BACKGROUND_THEMES = List.of(
            BG_SOLID_PERIWINKLE, BG_SOLID_LILAC, BG_SOLID_MIST, BG_SOLID_BLUSH,
            BG_SOLID_SKY_ICE, BG_SOLID_SKY,
            BG_OMBRE_2, BG_OMBRE_3, BG_OMBRE_4, BG_OMBRE_SKY
    );

    public static final String DEFAULT_BACKGROUND_THEME_ID = "bg_ombre_3";

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

    // Помощен метод: превръща масив HEX цветове в CSS linear-gradient низ
    public static String toCssLinearGradient(String[] colors, String direction) {
        StringBuilder sb = new StringBuilder("linear-gradient(" + direction);
        for (String c : colors) {
            sb.append(", ").append(c);
        }
        sb.append(")");
        return sb.toString();
    }

    // ============================================================
    // BUBBLE THEMES — 10 теми (5 едноцветни + 5 омбре)
    // ============================================================

    public static class BubbleTheme {
        public final String id;
        public final String displayName;
        public final BubbleThemeType type;
        public final String[] colors;     // 1 цвят за SOLID, 2 за OMBRE
        public final String textColor;    // цвят на текста върху балончето

        public BubbleTheme(String id, String displayName, BubbleThemeType type, String[] colors, String textColor) {
            this.id = id;
            this.displayName = displayName;
            this.type = type;
            this.colors = colors;
            this.textColor = textColor;
        }

        // CSS -fx-background-color стойност (плътен цвят или linear-gradient)
        public String toFxBackground() {
            if (type == BubbleThemeType.SOLID) {
                return colors[0];
            }
            return "linear-gradient(to bottom right, " + colors[0] + ", " + colors[1] + ")";
        }
    }

    // ── 5 пастелни едноцветни теми ────────────────────────────
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
            new String[]{"#A7ABDE"}, "#FFFFFF"); // по-тъмен пастел -> бял текст

    private static final BubbleTheme SOLID_MINT = new BubbleTheme(
            "solid_mint", "Mint", BubbleThemeType.SOLID,
            new String[]{"#C2F0E0"}, TEXT_ON_BUBBLE);

    private static final BubbleTheme SOLID_SKY = new BubbleTheme(
            "solid_sky", "Sky", BubbleThemeType.SOLID,
            new String[]{SKY_6}, "#FFFFFF"); // наситен periwinkle-индиго -> бял текст

    // ── 5 пастелни омбре теми ──────────────────────────────────
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

    public static final List<BubbleTheme> ALL_BUBBLE_THEMES = List.of(
            SOLID_LAVENDER, SOLID_BLUSH, SOLID_ORCHID, SOLID_PERIWINKLE, SOLID_MINT, SOLID_SKY,
            OMBRE_SUNSET, OMBRE_TWILIGHT, OMBRE_COTTON_CANDY, OMBRE_PEACH_LILAC, OMBRE_AURORA, OMBRE_SKY
    );

    public static final String DEFAULT_BUBBLE_THEME_ID = "solid_periwinkle";

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

    // ============================================================
    // UI THEME — swatch избор на ЕДИН accent цвят за UI chrome-а
    // (ляв панел / bottom-nav / chat header), НЕ за bubble/background теми.
    // От тоя единствен accent цвят Main.java извежда програмно нужните
    // по-светли нюанси (панел фон, nav фон, active-row highlight) —
    // затова тук пазим само 1 hex цвят на тема, не цяла палитра.
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
            new UiTheme("ui_sky_6", "Deep Sky", SKY_6)
    );

    public static final String DEFAULT_UI_THEME_ID = "ui_lilac";

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
}
