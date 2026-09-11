package com.messenger.backend.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// handleSetTheme writes the raw id string directly into users.bubble_theme/
// background_theme/ui_theme (VARCHAR(30), no constraint) — these helpers are
// the only defense against an unknown/arbitrary id, hence covered here.
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
        // solid_periwinkle is a bubble theme id, not background/ui — this cross-check
        // catches bugs like "we mixed up which catalog we're comparing against".
        assertFalse(ChatTheme.isValidBackgroundThemeId(ChatTheme.DEFAULT_BUBBLE_THEME_ID));
        assertFalse(ChatTheme.isValidUiThemeId(ChatTheme.DEFAULT_BUBBLE_THEME_ID));
    }
}
