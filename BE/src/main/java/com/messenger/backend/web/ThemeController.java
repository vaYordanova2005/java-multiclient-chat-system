package com.messenger.backend.web;

import com.messenger.backend.model.ChatTheme;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// ChatTheme (BE/README.md "Known limitation: Message.java / ChatTheme.java
// duplication") is ~300 lines of color catalogs, server-side only. Instead of the
// FE hand-copying it into TypeScript — a guaranteed source of drift the
// first time a theme gets added/changed — it pulls it from here once on load.
// isValidBubbleThemeId/isValidBackgroundThemeId/isValidUiThemeId in
// ClientHandler.isValidThemeSelection() remain the sole authority on which
// IDs are accepted — this endpoint is just a read model for the UI, it doesn't change that
// validation.
@RestController
public class ThemeController {

    public record ThemeCatalog(
            java.util.List<ChatTheme.BubbleTheme> bubbleThemes,
            java.util.List<ChatTheme.BackgroundTheme> backgroundThemes,
            java.util.List<ChatTheme.UiTheme> uiThemes,
            Defaults defaults
    ) {
    }

    public record Defaults(String bubbleThemeId, String backgroundThemeId, String uiThemeId) {
    }

    @GetMapping("/api/themes")
    public ThemeCatalog getThemeCatalog() {
        return new ThemeCatalog(
                ChatTheme.ALL_BUBBLE_THEMES,
                ChatTheme.ALL_BACKGROUND_THEMES,
                ChatTheme.ALL_UI_THEMES,
                new Defaults(ChatTheme.DEFAULT_BUBBLE_THEME_ID,
                        ChatTheme.DEFAULT_BACKGROUND_THEME_ID, ChatTheme.DEFAULT_UI_THEME_ID)
        );
    }
}
