package com.messenger.backend.web;

import com.messenger.backend.model.ChatTheme;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// ChatTheme (BE/README.md "Known limitation: Message.java / ChatTheme.java
// duplication") е ~300 реда цветови каталози, само на сървъра. Вместо ФЕ да
// го преписва на ръка в TypeScript — сигурен източник на разминаване при
// първата добавена/сменена тема — то си го дърпа оттук веднъж при зареждане.
// isValidBubbleThemeId/isValidBackgroundThemeId/isValidUiThemeId в
// ClientHandler.isValidThemeSelection() остават единствения authority за кои
// ID-та се приемат — тоя endpoint е само read-модел за UI-то, не променя тая
// валидация.
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
