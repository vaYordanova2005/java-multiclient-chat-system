package com.messenger.backend.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// handleSetTheme записва суровия id стринг направо в users.bubble_theme/
// background_theme/ui_theme (VARCHAR(30), без constraint) — тия helper-и са
// единствената защита срещу непознат/произволен id, затова покрити тук.
class ChatThemeValidationTest {

    @Test
    void knownCatalogIdsAreValid() {
        assertTrue(ChatTheme.isValidBubbleThemeId(ChatTheme.DEFAULT_BUBBLE_THEME_ID));
        assertTrue(ChatTheme.isValidBackgroundThemeId(ChatTheme.DEFAULT_BACKGROUND_THEME_ID));
        assertTrue(ChatTheme.isValidUiThemeId(ChatTheme.DEFAULT_UI_THEME_ID));

        for (ChatTheme.BubbleTheme t : ChatTheme.ALL_BUBBLE_THEMES) {
            assertTrue(ChatTheme.isValidBubbleThemeId(t.id));
        }
        for (ChatTheme.BackgroundTheme t : ChatTheme.ALL_BACKGROUND_THEMES) {
            assertTrue(ChatTheme.isValidBackgroundThemeId(t.id));
        }
        for (ChatTheme.UiTheme t : ChatTheme.ALL_UI_THEMES) {
            assertTrue(ChatTheme.isValidUiThemeId(t.id));
        }
    }

    @Test
    void unknownOrNullIdsAreRejected() {
        assertFalse(ChatTheme.isValidBubbleThemeId("not_a_real_theme"));
        assertFalse(ChatTheme.isValidBackgroundThemeId("not_a_real_theme"));
        assertFalse(ChatTheme.isValidUiThemeId("not_a_real_theme"));

        assertFalse(ChatTheme.isValidBubbleThemeId(null));
        assertFalse(ChatTheme.isValidBackgroundThemeId(null));
        assertFalse(ChatTheme.isValidUiThemeId(null));
    }

    @Test
    void themeCategoriesDoNotCrossValidateAsOthers() {
        // solid_periwinkle е bubble theme id, не background/ui — cross-check
        // хваща бъгове от типа "объркахме кой каталог сравняваме".
        assertFalse(ChatTheme.isValidBackgroundThemeId(ChatTheme.DEFAULT_BUBBLE_THEME_ID));
        assertFalse(ChatTheme.isValidUiThemeId(ChatTheme.DEFAULT_BUBBLE_THEME_ID));
    }
}
