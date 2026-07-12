import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.io.IOException;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

public class Main extends Application {

    private enum ViewState { LOGIN, REGISTER, FORGOT_PASSWORD, CHAT }
    private enum BottomTab { CHATS, FRIENDS, SETTINGS }

    // ── Network ───────────────────────────────────────
    private Client client;
    private String username;
    private String currentPassword; // пазен само в паметта за re-auth на Client constructor-а; никога не се логва/пише на диск

    // ── State ─────────────────────────────────────────
    private final Gson gson = new Gson();
    private String currentRoom = "global";
    private final Set<String> friendSet = new HashSet<>();
    private final Set<String> blockedSet = new HashSet<>();
    private final Map<String, Integer> unreadCounts = new HashMap<>();
    private String myAvatarId = null;
    private final Map<String, String> peerAvatars = new HashMap<>(); // username -> avatarId на ДРУГИ потребители
    private final Map<String, HBox> dmRowsByUsername = new HashMap<>(); // username -> вече построен DM sidebar ред (за avatar refresh)
    private boolean showOnlineStatus = true;

    // ── Chat UI ───────────────────────────────────────
    private VBox chatBox = new VBox(10);
    private ScrollPane chatScrollPane;
    private Label chatTitleLabel;
    private Circle chatHeaderStatusDot;

    // ── Left panel: Chats tab ──────────────────────────
    private TextField searchField;
    private VBox searchResultsBox = new VBox(4);
    private VBox pendingBox      = new VBox(4);
    private Label pendingTitle   = new Label("FRIEND REQUESTS");
    private VBox dmBox           = new VBox(4);
    private VBox onlineBox       = new VBox(4);
    private Label onlineTitle    = new Label("(0)");
    private Map<String, HBox> roomRowsMap = new HashMap<>();
    private VBox pendingSectionRef;

    // ── Left panel: Friends tab ─────────────────────────
    private VBox friendsBox = new VBox(6); // построен списък с приятели (avatar + online статус)
    private VBox friendsTabContent;
    private final Set<String> onlineUsersCache = new HashSet<>(); // последният online_users push, за да знаем кой е online в Friends таба

    // ── Left panel: Settings tab ───────────────────────
    private VBox settingsTabContent;
    private VBox chatsTabContent;
    private BottomTab currentBottomTab = BottomTab.CHATS;

    // ── UI Theme (accent swatch за ляв панел / bottom-nav / chat header) ──
    private String currentUiThemeId = ChatTheme.DEFAULT_UI_THEME_ID;
    private String uiAccent    = ChatTheme.LILAC_6; // избраният swatch цвят, непроменен
    private String uiPanelBg   = ChatTheme.LILAC_2; // изведен по-светъл нюанс — фон на ляв панел + chat header
    private String uiNavBg     = ChatTheme.LILAC_1; // изведен нюанс — фон на bottom-nav лентата
    private String uiRowActiveBg = ChatTheme.LILAC_5; // изведен нюанс — фон на активния room ред
    private String uiAccentText = "#FFFFFF"; // текст върху active tab бутона — тъмен ако accent-ът е твърде светъл (напр. "Sky")
    private VBox leftPanelRef;
    private HBox bottomNavRef;
    private HBox topBarRef;
    private Runnable refreshBottomTabStyles; // preserve-нат reference, за да може UI theme промяна да опресни active/inactive стиловете на таб бутоните

    // ── Theme state ─────────────────────────────────────
    private String currentBubbleThemeId = ChatTheme.DEFAULT_BUBBLE_THEME_ID;
    private String currentBackgroundThemeId = ChatTheme.DEFAULT_BACKGROUND_THEME_ID;
    private final List<Region> chatBubbleBackgrounds = new ArrayList<>();
    private Region chatBackgroundRegion;
    private VBox appearanceOverlayRef; // overlay панел, отварян с "⋯" до заглавието на стаята
    private final List<Node> uiThemeSwatchNodes = new ArrayList<>(); // за refreshUiThemeSwatchSelection() отвън

    // ── Root views ────────────────────────────────────
    private StackPane root;
    private StackPane authView;
    private HBox      chatView;

    // ── Auth fields ───────────────────────────────────
    private VBox loginPane, registerPane;
    private TextField loginUsernameField;
    private PasswordField loginPasswordField;
    private Label loginStatusLabel;

    private TextField regUsernameField;
    private PasswordField regPasswordField, regRepeatPasswordField;
    private TextField regSecurityQuestionField, regSecurityAnswerField;
    private Label regStatusLabel;

    // ── Forgot password fields ─────────────────────────
    private VBox forgotPasswordPane;
    private TextField forgotUsernameField;
    private Label forgotQuestionLabel;
    private TextField forgotAnswerField;
    private PasswordField forgotNewPasswordField, forgotNewPasswordRepeatField;
    private Label forgotStatusLabel;
    private VBox forgotStep1Box, forgotStep2Box; // вход на username -> вход на answer+нова парола

    private Stage mainStage;

    // ══════════════════════════════════════════════════
    // START
    // ══════════════════════════════════════════════════
    @Override
    public void start(Stage stage) {
        this.mainStage = stage;
        this.root      = new StackPane();
        this.authView  = new StackPane();
        // Базов font-size за ЦЕЛИЯ root container — повечето -fx-font-size
        // стилове по-долу са в "em"-подобни абсолютни px стойности, но този
        // root override гарантира, че контролни елементи без явен font-size
        // (Button, TextField default text) не остават джуджета на голям екран.
        this.root.setStyle("-fx-font-size: 16px;");

        createLoginPane();
        createRegisterPane();
        createForgotPasswordPane();
        navigateTo(ViewState.LOGIN);

        // По-голям default размер — "presentable" на цял екран, не остатъчно
        // малък прозорец, който после потребителят сам трябва да разтяга.
        Scene scene = new Scene(root, 1180, 740);
        stage.setTitle("Chat Application");
        stage.setMinWidth(820);
        stage.setMinHeight(520);
        stage.setScene(scene);

        // ГОЛЯМ БЪГ FIX: преди натискането на "X" НЕ разкачаше клиента от
        // сървъра (сървърът разбираше чак при Ctrl+C в конзолата). stop() си
        // съществуваше и коректно викаше disconnectSocket(), но JavaFX никога
        // не го извикваше на натискане на "X" — implicitExit разчита stage-ът
        // да premine през WINDOW_CLOSE_REQUEST -> hide -> stop(), а тук нищо
        // изрично не потвърждаваше/форсираше тази верига. Сега вика
        // disconnectSocket() директно И принудително спира JVM-а, за да няма
        // никакъв сценарий, в който socket-ът остава висящ отворен.
        stage.setOnCloseRequest(e -> {
            disconnectSocket();
            Platform.exit();
            System.exit(0);
        });

        stage.show();
    }

    private void navigateTo(ViewState state) {
        switch (state) {
            case LOGIN    -> { loginStatusLabel.setText("Welcome Back!"); authView.getChildren().setAll(loginPane);    root.getChildren().setAll(authView); }
            case REGISTER -> { regStatusLabel.setText("Create Account"); authView.getChildren().setAll(registerPane); root.getChildren().setAll(authView); }
            case FORGOT_PASSWORD -> { resetForgotPasswordFlow(); authView.getChildren().setAll(forgotPasswordPane); root.getChildren().setAll(authView); }
            case CHAT     -> { if (chatView != null) root.getChildren().setAll(chatView); }
        }
    }

    // ══════════════════════════════════════════════════
    // AUTH PANES (непроменени от преди)
    // ══════════════════════════════════════════════════
    private void createLoginPane() {
        loginStatusLabel = new Label("Welcome Back!");
        loginStatusLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 20px; -fx-font-weight: bold;");
        loginStatusLabel.setWrapText(true);
        loginStatusLabel.setMaxWidth(300);

        loginUsernameField = new TextField();
        loginUsernameField.setPromptText("Username...");
        loginUsernameField.setStyle(authFieldStyle());

        loginPasswordField = new PasswordField();
        loginPasswordField.setPromptText("Password...");
        loginPasswordField.setStyle(authFieldStyle());

        Button joinButton = new Button("Join Chat");
        joinButton.setDefaultButton(true);
        joinButton.setStyle(primaryButtonStyle());

        Label switchLabel = new Label("Don't have an account? Sign up");
        switchLabel.setStyle("-fx-text-fill: " + ChatTheme.LILAC_6 + "; -fx-underline: true; -fx-cursor: hand; -fx-font-size: 14px;");
        switchLabel.setOnMouseClicked(e -> navigateTo(ViewState.REGISTER));

        Label forgotLabel = new Label("Forgot password?");
        forgotLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-underline: true; -fx-cursor: hand; -fx-font-size: 13px;");
        forgotLabel.setOnMouseClicked(e -> navigateTo(ViewState.FORGOT_PASSWORD));

        VBox card = new VBox(12, loginStatusLabel, loginUsernameField, loginPasswordField, joinButton, switchLabel, forgotLabel);
        card.setStyle("-fx-padding: 30; -fx-alignment: center; -fx-background-color: #FFFFFF; "
                + "-fx-background-radius: 16; -fx-effect: dropshadow(gaussian, rgba(122,111,148,0.25), 18, 0.2, 0, 4);");
        card.setMaxWidth(320);
        card.setAlignment(Pos.CENTER);

        loginPane = new VBox(card);
        loginPane.setAlignment(Pos.CENTER);
        loginPane.setStyle("-fx-padding: 40; -fx-background-color: " + ChatTheme.LILAC_3 + ";");

        joinButton.setOnAction(e -> {
            String user = loginUsernameField.getText().trim();
            String pass = loginPasswordField.getText().trim();
            if (user.isEmpty() || pass.isEmpty()) { loginStatusLabel.setText("❌ Please fill in all fields"); return; }

            joinButton.setDisable(true);
            loginStatusLabel.setText("Connecting...");

            // Login вече минава ИЗЦЯЛО през сървъра (не директна MySQL
            // връзка) — Client.login() отваря ЕДНА TLS връзка, праща
            // AUTH_LOGIN, и при успех ПАЗИ същия socket като постоянната
            // chat връзка (вместо да я затваря и да отваря нова — старото
            // поведение правеше 2 сървърни connection-а за всеки login).
            // Това става на отделна нишка, за да не блокираме UI нишката,
            // докато сървъра/мрежата отговорят.
            new Thread(() -> {
                Client.LoginResult result = Client.login(user, pass, this::handleServerMessage);

                Platform.runLater(() -> {
                    joinButton.setDisable(false);
                    if (result.success) {
                        // Ако имаше "стара" връзка от предишна сесия (login без
                        // рестарт на приложението), затваряме НЕЯ — не новата,
                        // която тъкмо получихме от Client.login() по-горе.
                        Client oldClient = this.client;
                        this.client = result.client;
                        this.username = result.client.getUsername();
                        this.currentPassword = pass;
                        if (oldClient != null) oldClient.closeEverything();
                        openChat(mainStage);
                    } else {
                        loginStatusLabel.setText("❌ " + result.message);
                    }
                });
            }).start();
        });
    }

    private String authFieldStyle() {
        return "-fx-background-radius: 10; -fx-padding: 10; -fx-background-color: " + ChatTheme.LILAC_2 + "; "
                + "-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-prompt-text-fill: " + ChatTheme.TEXT_MUTED + "; "
                + "-fx-font-size: 15px;";
    }

    private String primaryButtonStyle() {
        return "-fx-background-color: " + ChatTheme.LILAC_6 + "; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-background-radius: 10; -fx-padding: 10 18; -fx-font-size: 15px; -fx-cursor: hand;";
    }

    private void createRegisterPane() {
        regStatusLabel = new Label("Create Account");
        regStatusLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 20px; -fx-font-weight: bold;");
        regStatusLabel.setWrapText(true);
        regStatusLabel.setMaxWidth(300);

        regUsernameField       = new TextField();     regUsernameField.setPromptText("Username...");
        regPasswordField       = new PasswordField(); regPasswordField.setPromptText("Password...");
        regRepeatPasswordField = new PasswordField(); regRepeatPasswordField.setPromptText("Repeat Password...");

        // Security question вместо email — ползва се по-късно за password
        // reset без нужда от SMTP/имейл инфраструктура.
        regSecurityQuestionField = new TextField();
        regSecurityQuestionField.setPromptText("Security question (e.g. 'Name of first pet?')...");

        regSecurityAnswerField = new TextField();
        regSecurityAnswerField.setPromptText("Answer...");

        for (var f : List.of(regUsernameField, regPasswordField, regRepeatPasswordField,
                regSecurityQuestionField, regSecurityAnswerField))
            f.setStyle(authFieldStyle());

        Button registerButton = new Button("Register");
        registerButton.setStyle(primaryButtonStyle());

        Label switchLabel = new Label("Already have an account? Sign in");
        switchLabel.setStyle("-fx-text-fill: " + ChatTheme.LILAC_6 + "; -fx-underline: true; -fx-cursor: hand; -fx-font-size: 14px;");
        switchLabel.setOnMouseClicked(e -> navigateTo(ViewState.LOGIN));

        VBox card = new VBox(12, regStatusLabel, regUsernameField,
                regPasswordField, regRepeatPasswordField,
                regSecurityQuestionField, regSecurityAnswerField,
                registerButton, switchLabel);
        card.setStyle("-fx-padding: 30; -fx-alignment: center; -fx-background-color: #FFFFFF; "
                + "-fx-background-radius: 16; -fx-effect: dropshadow(gaussian, rgba(122,111,148,0.25), 18, 0.2, 0, 4);");
        card.setMaxWidth(340);
        card.setAlignment(Pos.CENTER);

        registerPane = new VBox(card);
        registerPane.setAlignment(Pos.CENTER);
        registerPane.setStyle("-fx-padding: 40; -fx-background-color: " + ChatTheme.LILAC_3 + ";");

        registerButton.setOnAction(e -> {
            String user     = regUsernameField.getText().trim();
            String pass     = regPasswordField.getText().trim();
            String rep      = regRepeatPasswordField.getText().trim();
            String question = regSecurityQuestionField.getText().trim();
            String answer   = regSecurityAnswerField.getText().trim();

            if (user.isEmpty() || pass.isEmpty() || rep.isEmpty() || question.isEmpty() || answer.isEmpty()) {
                regStatusLabel.setText("❌ Please fill in all fields");
                return;
            }
            if (!pass.equals(rep)) { regStatusLabel.setText("❌ Passwords do not match"); return; }
            if (pass.length() < 6) { regStatusLabel.setText("❌ Password must be at least 6 characters"); return; }

            registerButton.setDisable(true);
            regStatusLabel.setText("Creating account...");

            new Thread(() -> {
                Client.AuthResult result = Client.attemptRegister(user, pass, question, answer);

                Platform.runLater(() -> {
                    registerButton.setDisable(false);
                    if (result.success) {
                        loginUsernameField.setText(user);
                        loginPasswordField.clear();
                        navigateTo(ViewState.LOGIN);
                        loginStatusLabel.setText("✅ Registration successful! Please login.");
                        loginStatusLabel.setStyle("-fx-text-fill: #00b894; -fx-font-size: 15px; -fx-font-weight: bold;");
                    } else {
                        regStatusLabel.setText("❌ " + result.message);
                    }
                });
            }).start();
        });
    }

    // ══════════════════════════════════════════════════
    // FORGOT PASSWORD FLOW — security question базиран reset, без имейл.
    // Двустъпков: (1) въвеждаш username -> получаваш въпроса; (2) отговаряш
    // + нова парола -> ако верно, паролата се сменя.
    // ══════════════════════════════════════════════════
    private void createForgotPasswordPane() {
        Label title = new Label("Reset Password");
        title.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 20px; -fx-font-weight: bold;");

        forgotStatusLabel = new Label();
        forgotStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + ChatTheme.TEXT_MUTED + ";");
        forgotStatusLabel.setWrapText(true);
        forgotStatusLabel.setMaxWidth(300);

        // ── Step 1: username fetch security question ──
        forgotUsernameField = new TextField();
        forgotUsernameField.setPromptText("Your username...");
        forgotUsernameField.setStyle(authFieldStyle());

        Button getQuestionBtn = new Button("Continue");
        getQuestionBtn.setStyle(primaryButtonStyle());

        forgotStep1Box = new VBox(10, forgotUsernameField, getQuestionBtn);

        getQuestionBtn.setOnAction(e -> {
            String user = forgotUsernameField.getText().trim();
            if (user.isEmpty()) { forgotStatusLabel.setText("❌ Enter your username"); return; }

            getQuestionBtn.setDisable(true);
            forgotStatusLabel.setText("Looking up...");

            new Thread(() -> {
                Client.AuthResult result = Client.getResetQuestion(user);

                Platform.runLater(() -> {
                    getQuestionBtn.setDisable(false);
                    if (result.success) {
                        forgotQuestionLabel.setText(result.message); // message носи question текста тук
                        forgotStep1Box.setVisible(false);
                        forgotStep1Box.setManaged(false);
                        forgotStep2Box.setVisible(true);
                        forgotStep2Box.setManaged(true);
                        forgotStatusLabel.setText("");
                    } else {
                        forgotStatusLabel.setText("❌ " + result.message);
                    }
                });
            }).start();
        });

        // ── Step 2: answer + new password verify & reset ──
        forgotQuestionLabel = new Label();
        forgotQuestionLabel.setWrapText(true);
        forgotQuestionLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 14px; -fx-font-weight: bold;");

        forgotAnswerField = new TextField();
        forgotAnswerField.setPromptText("Your answer...");
        forgotAnswerField.setStyle(authFieldStyle());

        forgotNewPasswordField = new PasswordField();
        forgotNewPasswordField.setPromptText("New password...");
        forgotNewPasswordField.setStyle(authFieldStyle());

        forgotNewPasswordRepeatField = new PasswordField();
        forgotNewPasswordRepeatField.setPromptText("Repeat new password...");
        forgotNewPasswordRepeatField.setStyle(authFieldStyle());

        Button resetBtn = new Button("Reset Password");
        resetBtn.setStyle(primaryButtonStyle());

        forgotStep2Box = new VBox(10, forgotQuestionLabel, forgotAnswerField,
                forgotNewPasswordField, forgotNewPasswordRepeatField, resetBtn);
        forgotStep2Box.setVisible(false);
        forgotStep2Box.setManaged(false);

        resetBtn.setOnAction(e -> {
            String user = forgotUsernameField.getText().trim();
            String answer = forgotAnswerField.getText().trim();
            String newPass = forgotNewPasswordField.getText().trim();
            String newPassRepeat = forgotNewPasswordRepeatField.getText().trim();

            if (answer.isEmpty() || newPass.isEmpty()) { forgotStatusLabel.setText("❌ Fill in all fields"); return; }
            if (!newPass.equals(newPassRepeat)) { forgotStatusLabel.setText("❌ Passwords do not match"); return; }
            if (newPass.length() < 6) { forgotStatusLabel.setText("❌ Password must be at least 6 characters"); return; }

            resetBtn.setDisable(true);
            forgotStatusLabel.setText("Resetting...");

            new Thread(() -> {
                Client.AuthResult result = Client.submitResetAnswer(user, answer, newPass);

                Platform.runLater(() -> {
                    resetBtn.setDisable(false);
                    if (result.success) {
                        navigateTo(ViewState.LOGIN);
                        loginUsernameField.setText(user);
                        loginStatusLabel.setText("✅ Password reset! Please login.");
                        loginStatusLabel.setStyle("-fx-text-fill: #00b894; -fx-font-size: 15px; -fx-font-weight: bold;");
                    } else {
                        forgotStatusLabel.setText("❌ " + result.message);
                    }
                });
            }).start();
        });

        Label backLabel = new Label("← Back to login");
        backLabel.setStyle("-fx-text-fill: " + ChatTheme.LILAC_6 + "; -fx-underline: true; -fx-cursor: hand; -fx-font-size: 13px;");
        backLabel.setOnMouseClicked(e -> navigateTo(ViewState.LOGIN));

        VBox card = new VBox(14, title, forgotStatusLabel, forgotStep1Box, forgotStep2Box, backLabel);
        card.setStyle("-fx-padding: 30; -fx-alignment: center; -fx-background-color: #FFFFFF; "
                + "-fx-background-radius: 16; -fx-effect: dropshadow(gaussian, rgba(122,111,148,0.25), 18, 0.2, 0, 4);");
        card.setMaxWidth(340);
        card.setAlignment(Pos.CENTER);

        forgotPasswordPane = new VBox(card);
        forgotPasswordPane.setAlignment(Pos.CENTER);
        forgotPasswordPane.setStyle("-fx-padding: 40; -fx-background-color: " + ChatTheme.LILAC_3 + ";");
    }

    // Ресетва forgot-password екрана до стъпка 1, всеки път когато се отваря
    // отново — иначе старите въведени стойности от предишен опит остават.
    private void resetForgotPasswordFlow() {
        if (forgotUsernameField != null) forgotUsernameField.clear();
        if (forgotAnswerField != null) forgotAnswerField.clear();
        if (forgotNewPasswordField != null) forgotNewPasswordField.clear();
        if (forgotNewPasswordRepeatField != null) forgotNewPasswordRepeatField.clear();
        if (forgotStatusLabel != null) forgotStatusLabel.setText("");
        if (forgotStep1Box != null) { forgotStep1Box.setVisible(true); forgotStep1Box.setManaged(true); }
        if (forgotStep2Box != null) { forgotStep2Box.setVisible(false); forgotStep2Box.setManaged(false); }
    }

    // ══════════════════════════════════════════════════
    // OPEN CHAT — главна структура: ляв панел (bottom-nav) + чат площ.
    // Десният customization панел е премахнат изцяло (Priority 1).
    // ══════════════════════════════════════════════════
    private void openChat(Stage stage) {
        clearChat();

        // Изчистваме state от предишна сесия (ако потребител се е логнал
        // повторно без рестарт на приложението) — иначе roomRowsMap/dmBox/
        // unreadCounts пазят "orphaned" reference-и към вече изтрити Node-ове.
        roomRowsMap.clear();
        unreadCounts.clear();
        friendSet.clear();
        blockedSet.clear();
        peerAvatars.clear();
        dmRowsByUsername.clear();
        onlineUsersCache.clear();
        dmBox.getChildren().clear();
        onlineBox.getChildren().clear();
        currentRoom = "global";

        // Темите и avatar-ът вече НЕ се четат директно от MySQL тук —
        // клиентът няма повече DB credentials. Те идват от сървъра чрез
        // "theme_update" и "profile_info" push съобщения веднага след login
        // (виж ClientHandler.pushThemePreferences/pushProfileInfo). Докато
        // не пристигнат, ползваме разумни дефолти, за да UI-то не е празно.
        currentBubbleThemeId = ChatTheme.DEFAULT_BUBBLE_THEME_ID;
        currentBackgroundThemeId = ChatTheme.DEFAULT_BACKGROUND_THEME_ID;
        currentUiThemeId = ChatTheme.DEFAULT_UI_THEME_ID;
        applyUiThemeColors(ChatTheme.getDefaultUiTheme().accent);
        myAvatarId = null;

        chatScrollPane = new ScrollPane(chatBox);
        chatScrollPane.setFitToWidth(true);
        chatScrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(chatScrollPane, Priority.ALWAYS);
        chatBox.heightProperty().addListener((obs, o, n) -> chatScrollPane.setVvalue(1.0));

        TextField input = new TextField();
        input.setPromptText("Type a message...");
        input.setStyle("-fx-background-radius: 10; -fx-padding: 10; -fx-background-color: white; "
                + "-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 15px;");
        HBox.setHgrow(input, Priority.ALWAYS);

        Button sendButton = new Button("Send");
        sendButton.setStyle(primaryButtonStyle());

        HBox bottom = new HBox(10, input, sendButton);
        bottom.setAlignment(Pos.CENTER);
        bottom.setStyle("-fx-padding: 10;");

        // ── Chat header — цвят съвпада с левия панел (Priority 1: room header styling) ──
        chatTitleLabel = new Label("🌍 Global");
        chatTitleLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 18px; -fx-font-weight: bold;");

        chatHeaderStatusDot = new Circle(4);
        chatHeaderStatusDot.setFill(Color.web("#00b894"));
        chatHeaderStatusDot.setVisible(false);

        Button appearanceToggleBtn = new Button("⋯");
        appearanceToggleBtn.setStyle("-fx-background-color: rgba(255,255,255,0.6); -fx-text-fill: " + ChatTheme.TEXT_DARK
                + "; -fx-font-size: 18px; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 4 12; -fx-cursor: hand;");
        appearanceToggleBtn.setOnAction(e -> toggleAppearanceOverlay());

        HBox topBar = new HBox(8, chatTitleLabel, chatHeaderStatusDot, spacer(), appearanceToggleBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-padding: 14 18; -fx-background-color: " + uiPanelBg + ";");
        this.topBarRef = topBar;

        StackPane chatBackground = new StackPane();
        chatBackground.setStyle(buildChatBackgroundCss());

        VBox chatColumn = new VBox(chatScrollPane, bottom);
        VBox.setVgrow(chatScrollPane, Priority.ALWAYS);

        // Appearance overlay — плъзга се ВЪРХУ чат площта при клик на "⋯",
        // вместо да е отделен таб в Settings (старото поведение от преди).
        VBox appearanceOverlay = buildAppearanceOverlay();
        appearanceOverlay.setVisible(false);
        appearanceOverlay.setManaged(false);
        this.appearanceOverlayRef = appearanceOverlay;

        StackPane chatStack = new StackPane(chatColumn, appearanceOverlay);
        StackPane.setAlignment(appearanceOverlay, Pos.TOP_RIGHT);

        chatBackground.getChildren().add(chatStack);
        StackPane.setAlignment(chatStack, Pos.CENTER);

        VBox chatAreaContainer = new VBox(topBar, chatBackground);
        VBox.setVgrow(chatBackground, Priority.ALWAYS);
        HBox.setHgrow(chatAreaContainer, Priority.ALWAYS);

        this.chatBackgroundRegion = chatBackground;

        VBox leftPanel = buildLeftPanel();

        chatView = new HBox(leftPanel, chatAreaContainer);
        navigateTo(ViewState.CHAT);

        connectToServer(input, sendButton);
    }

    private Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private void toggleAppearanceOverlay() {
        if (appearanceOverlayRef == null) return;
        boolean willShow = !appearanceOverlayRef.isVisible();
        appearanceOverlayRef.setVisible(willShow);
        appearanceOverlayRef.setManaged(willShow);
    }

    // Appearance overlay-карта — съдържа background + bubble theme избор.
    // Отваря се с "⋯" бутона до заглавието на стаята (както беше преди),
    // вместо да е таб вътре в Settings.
    private VBox buildAppearanceOverlay() {
        Label title = new Label("🎨 Appearance");
        title.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 15px; -fx-font-weight: bold;");

        Button closeBtn = new Button("✕");
        closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + ChatTheme.TEXT_MUTED
                + "; -fx-font-size: 15px; -fx-cursor: hand; -fx-font-weight: bold;");
        closeBtn.setOnAction(e -> toggleAppearanceOverlay());

        HBox headerRow = new HBox(title, spacer(), closeBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        Label bgSectionTitle = new Label("BACKGROUND");
        bgSectionTitle.setStyle(settingsSectionTitleStyle());

        Label bgSolidLabel = new Label("Solid");
        bgSolidLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 12px; -fx-font-weight: bold;");
        Label bgOmbreLabel = new Label("Ombre");
        bgOmbreLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 12px; -fx-font-weight: bold;");

        FlowPane bgSolidGrid = new FlowPane(8, 8);
        FlowPane bgOmbreGrid = new FlowPane(8, 8);

        List<Node> bgSwatchNodes = new ArrayList<>();
        for (ChatTheme.BackgroundTheme bgTheme : ChatTheme.ALL_BACKGROUND_THEMES) {
            if (bgTheme.colors.length == 1 && ChatTheme.LILAC_2.equals(bgTheme.colors[0])) continue;
            Node swatch = buildBackgroundThemeSwatch(bgTheme, bgSwatchNodes);
            if (bgTheme.type == ChatTheme.BubbleThemeType.SOLID) bgSolidGrid.getChildren().add(swatch);
            else bgOmbreGrid.getChildren().add(swatch);
        }
        refreshBackgroundSwatchSelection(bgSwatchNodes);

        Label bubbleSectionTitle = new Label("MESSAGE BUBBLES");
        bubbleSectionTitle.setStyle(settingsSectionTitleStyle());

        Label soloLabel = new Label("Solid");
        soloLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 12px; -fx-font-weight: bold;");
        FlowPane solidGrid = new FlowPane(8, 8);
        FlowPane ombreGrid = new FlowPane(8, 8);
        Label ombreLabel = new Label("Ombre");
        ombreLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 12px; -fx-font-weight: bold;");

        List<Node> swatchNodes = new ArrayList<>();
        for (ChatTheme.BubbleTheme theme : ChatTheme.ALL_BUBBLE_THEMES) {
            Node swatch = buildBubbleThemeSwatch(theme, swatchNodes);
            if (theme.type == ChatTheme.BubbleThemeType.SOLID) solidGrid.getChildren().add(swatch);
            else ombreGrid.getChildren().add(swatch);
        }
        refreshBubbleSwatchSelection(swatchNodes);

        Label uiThemeSectionTitle = new Label("UI THEME");
        uiThemeSectionTitle.setStyle(settingsSectionTitleStyle());
        Label uiThemeHint = new Label("Accent for the left panel, bottom nav & chat header");
        uiThemeHint.setWrapText(true);
        uiThemeHint.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 11px;");

        FlowPane uiThemeGrid = new FlowPane(8, 8);
        uiThemeSwatchNodes.clear();
        for (ChatTheme.UiTheme uiTheme : ChatTheme.ALL_UI_THEMES) {
            uiThemeGrid.getChildren().add(buildUiThemeSwatch(uiTheme));
        }
        refreshUiThemeSwatchSelection();

        // FIX: панелът никога не е бил в ScrollPane — работеше "случайно"
        // докато съдържанието се побираше на екран. Добавянето на UI THEME
        // секцията го направи по-високо от прозореца, и понеже нямаше начин
        // за скрол, последните swatch-ове (напр. "Forest") просто се отрязваха
        // отдолу — изглеждаше като "заяло", а всъщност просто липсваше скрол.
        VBox scrollableContent = new VBox(12,
                bgSectionTitle, bgSolidLabel, bgSolidGrid, bgOmbreLabel, bgOmbreGrid,
                new Separator(),
                bubbleSectionTitle, soloLabel, solidGrid, ombreLabel, ombreGrid,
                new Separator(),
                uiThemeSectionTitle, uiThemeHint, uiThemeGrid
        );
        scrollableContent.setPadding(new Insets(0, 6, 4, 0));

        ScrollPane scroll = new ScrollPane(scrollableContent);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scroll.setMaxHeight(400);

        VBox content = new VBox(10, headerRow, new Separator(), scroll);
        content.setPadding(new Insets(16));
        content.setMaxWidth(320);
        content.setStyle("-fx-background-color: white; -fx-background-radius: 14; "
                + "-fx-effect: dropshadow(gaussian, rgba(122,111,148,0.35), 20, 0.25, 0, 5);");

        VBox wrapper = new VBox(content);
        wrapper.setPadding(new Insets(14));
        return wrapper;
    }

    private String buildChatBackgroundCss() {
        ChatTheme.BackgroundTheme theme = ChatTheme.getBackgroundThemeById(currentBackgroundThemeId);
        return theme.toCss();
    }

    // ══════════════════════════════════════════════════
    // UI THEME — от ЕДИН swatch accent цвят извеждаме нужните нюанси
    // (панел фон / nav фон / active-row фон), вместо да пазим отделни
    // hex-ове за всяка тема — така swatch-ът може да е БУКВАЛНО кой да е
    // цвят (по-гъвкаво от 2 фиксирани пакетирани теми).
    // ══════════════════════════════════════════════════
    private void applyUiThemeColors(String accentHex) {
        this.uiAccent = accentHex;
        this.uiPanelBg = tintTowardWhite(accentHex, 0.78);
        this.uiNavBg = tintTowardWhite(accentHex, 0.68);
        this.uiRowActiveBg = tintTowardWhite(accentHex, 0.35);
        this.uiAccentText = isLightColor(accentHex) ? ChatTheme.TEXT_DARK : "#FFFFFF";
    }

    // Относителна яркост (luminance) на цвят — над прага значи "светъл",
    // значи белият текст върху него ще е нечетлив (напр. светлия "Sky" swatch).
    private boolean isLightColor(String hex) {
        try {
            Color c = Color.web(hex);
            double luminance = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
            return luminance > 0.68;
        } catch (Exception e) {
            return false;
        }
    }

    // Смесва даден hex цвят с бяло — по-голямо towardWhite (0..1) = по-светъл резултат
    private String tintTowardWhite(String hex, double towardWhite) {
        try {
            Color base = Color.web(hex);
            Color mixed = base.interpolate(Color.WHITE, towardWhite);
            return String.format("#%02X%02X%02X",
                    Math.round(mixed.getRed() * 255),
                    Math.round(mixed.getGreen() * 255),
                    Math.round(mixed.getBlue() * 255));
        } catch (Exception e) {
            return hex;
        }
    }

    // Прилага текущите uiAccent/uiPanelBg/uiNavBg/uiRowActiveBg върху вече
    // построените nodes (левия панел, bottom-nav, chat header, room редовете).
    // Извиква се и веднага при login (за default темата), и на всяко
    // swatch кликване, и когато theme_update push пристигне от сървъра.
    private void refreshUiThemeVisuals() {
        if (leftPanelRef != null) {
            leftPanelRef.setStyle("-fx-background-color: " + uiPanelBg + ";");
        }
        if (bottomNavRef != null) {
            bottomNavRef.setStyle("-fx-background-color: " + uiNavBg + ";");
        }
        if (topBarRef != null) {
            topBarRef.setStyle("-fx-padding: 14 18; -fx-background-color: " + uiPanelBg + ";");
        }
        if (refreshBottomTabStyles != null) {
            refreshBottomTabStyles.run();
        }
        refreshActiveRoomHighlight();
    }

    // ══════════════════════════════════════════════════
    // LEFT PANEL — bottom navigation между "Chats" / "Friends" / "Settings"
    // ══════════════════════════════════════════════════
    private VBox buildLeftPanel() {

        chatsTabContent = buildChatsTabContent();
        friendsTabContent = buildFriendsTabContent();
        settingsTabContent = buildSettingsTabContent();
        friendsTabContent.setVisible(false);
        friendsTabContent.setManaged(false);
        settingsTabContent.setVisible(false);
        settingsTabContent.setManaged(false);

        StackPane tabSwitcher = new StackPane(chatsTabContent, friendsTabContent, settingsTabContent);
        VBox.setVgrow(tabSwitcher, Priority.ALWAYS);

        Button chatsTabBtn = new Button("💬 Chats");
        Button friendsTabBtn = new Button("👥 Friends");
        Button settingsTabBtn = new Button("⚙️");
        settingsTabBtn.setTooltip(new Tooltip("Settings"));

        refreshBottomTabStyles = () -> {
            chatsTabBtn.setStyle(bottomTabButtonStyle(currentBottomTab == BottomTab.CHATS));
            friendsTabBtn.setStyle(bottomTabButtonStyle(currentBottomTab == BottomTab.FRIENDS));
            settingsTabBtn.setStyle(bottomTabButtonStyle(currentBottomTab == BottomTab.SETTINGS));
        };

        chatsTabBtn.setOnAction(e -> switchBottomTab(BottomTab.CHATS));
        friendsTabBtn.setOnAction(e -> switchBottomTab(BottomTab.FRIENDS));
        settingsTabBtn.setOnAction(e -> switchBottomTab(BottomTab.SETTINGS));
        refreshBottomTabStyles.run();

        HBox bottomNav = new HBox(6, chatsTabBtn, friendsTabBtn, settingsTabBtn);
        bottomNav.setAlignment(Pos.CENTER);
        bottomNav.setPadding(new Insets(8));
        bottomNav.setStyle("-fx-background-color: " + uiNavBg + ";");
        HBox.setHgrow(chatsTabBtn, Priority.ALWAYS);
        HBox.setHgrow(friendsTabBtn, Priority.ALWAYS);
        chatsTabBtn.setMaxWidth(Double.MAX_VALUE);
        friendsTabBtn.setMaxWidth(Double.MAX_VALUE);
        this.bottomNavRef = bottomNav;

        VBox leftPanel = new VBox(tabSwitcher, bottomNav);
        leftPanel.setPrefWidth(250);
        leftPanel.setMinWidth(250);
        leftPanel.setStyle("-fx-background-color: " + uiPanelBg + ";");
        this.leftPanelRef = leftPanel;

        return leftPanel;
    }

    // Централизирана логика за смяна на bottom-nav таба — извиква се и от
    // самите таб бутони, и програматично (напр. клик на приятел в Friends
    // таба трябва да те прехвърли в Chats и да отвори DM-а му).
    private void switchBottomTab(BottomTab tab) {
        currentBottomTab = tab;

        chatsTabContent.setVisible(tab == BottomTab.CHATS);
        chatsTabContent.setManaged(tab == BottomTab.CHATS);

        friendsTabContent.setVisible(tab == BottomTab.FRIENDS);
        friendsTabContent.setManaged(tab == BottomTab.FRIENDS);

        settingsTabContent.setVisible(tab == BottomTab.SETTINGS);
        settingsTabContent.setManaged(tab == BottomTab.SETTINGS);

        if (refreshBottomTabStyles != null) refreshBottomTabStyles.run();
    }

    private String bottomTabButtonStyle(boolean active) {
        return "-fx-font-size: 13px; -fx-padding: 8 10; -fx-background-radius: 8; -fx-cursor: hand; "
                + (active
                    ? "-fx-background-color: " + uiAccent + "; -fx-text-fill: " + uiAccentText + "; -fx-font-weight: bold;"
                    : "-fx-background-color: transparent; -fx-text-fill: " + ChatTheme.TEXT_DARK + ";");
    }

    // ══════════════════════════════════════════════════
    // CHATS TAB — search, online users, friends, chat list, global
    // ══════════════════════════════════════════════════
    private VBox buildChatsTabContent() {
        searchField = new TextField();
        searchField.setPromptText("🔍 Search users...");
        searchField.setStyle("-fx-background-radius: 8; -fx-padding: 6; -fx-background-color: white; "
                + "-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-prompt-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 14px;");

        Button searchBtn = new Button("Go");
        searchBtn.setStyle("-fx-background-color: " + ChatTheme.LILAC_6 + "; -fx-text-fill: white; -fx-background-radius: 8; -fx-padding: 6 10; -fx-cursor: hand;");
        searchBtn.setOnAction(e -> doSearch());
        searchField.setOnAction(e -> doSearch());

        HBox searchBar = new HBox(6, searchField, searchBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchResultsBox.setStyle("-fx-padding: 4 0 0 0;");

        Label onlineSectionTitle = new Label("ONLINE");
        onlineSectionTitle.setStyle("-fx-text-fill: " + ChatTheme.LILAC_6 + "; -fx-font-size: 13px; -fx-font-weight: bold;");
        onlineTitle.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 12px;");
        onlineBox.setStyle("-fx-padding: 2 0 6 0;");

        Label dmTitle = new Label("CHATS");
        dmTitle.setStyle("-fx-text-fill: " + ChatTheme.LILAC_6 + "; -fx-font-size: 13px; -fx-font-weight: bold;");
        dmBox.setStyle("-fx-padding: 2 0 0 0;");

        HBox globalRow = buildRoomRow("global", "🌍", "Global", null);

        VBox inner = new VBox(10,
                searchBar,
                searchResultsBox,
                globalRow,
                new Separator(),
                new VBox(4, onlineSectionTitle, onlineTitle, onlineBox),
                new Separator(),
                new VBox(4, dmTitle, dmBox)
        );
        inner.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(inner);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox container = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return container;
    }

    // ══════════════════════════════════════════════════
    // FRIENDS TAB — пълен списък приятели (avatar + online статус) + pending
    // friend requests (преместени тук от Chats — по-логично място за тях).
    // Клик на приятел те прехвърля обратно в Chats и отваря DM-а му.
    // ══════════════════════════════════════════════════
    private VBox buildFriendsTabContent() {
        Label screenTitle = new Label("👥 Friends");
        screenTitle.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 16px; -fx-font-weight: bold;");

        pendingTitle.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 13px; -fx-font-weight: bold;");
        pendingBox.setStyle("-fx-padding: 2 0 6 0;");
        VBox pendingSection = new VBox(4, pendingTitle, pendingBox);
        pendingSection.setStyle("-fx-background-color: " + ChatTheme.LILAC_4 + "; -fx-background-radius: 8; -fx-padding: 8;");
        pendingSection.setVisible(false);
        pendingSection.setManaged(false);
        pendingSectionRef = pendingSection;

        Label friendsSectionTitle = new Label("ALL FRIENDS");
        friendsSectionTitle.setStyle("-fx-text-fill: " + ChatTheme.LILAC_6 + "; -fx-font-size: 13px; -fx-font-weight: bold;");
        friendsBox.setStyle("-fx-padding: 2 0 0 0;");

        Label emptyHint = new Label("No friends yet — search for someone in Chats to add them.");
        emptyHint.setWrapText(true);
        emptyHint.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 12px;");
        emptyHint.setUserData("friends_empty_hint");
        friendsBox.getChildren().add(emptyHint);

        VBox inner = new VBox(12,
                screenTitle,
                pendingSection,
                new Separator(),
                new VBox(4, friendsSectionTitle, friendsBox)
        );
        inner.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(inner);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox container = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return container;
    }

    // Пренарежда пълния приятелски списък от текущия friendSet, използвайки
    // онлайн статус (onlineUsersCache) и известни avatar-и (peerAvatars).
    // Извиква се при всяко обновяване на friendSet/online users/avatars,
    // за да е Friends табът винаги в синхрон.
    private void refreshFriendsList() {
        friendsBox.getChildren().clear();

        if (friendSet.isEmpty()) {
            Label emptyHint = new Label("No friends yet — search for someone in Chats to add them.");
            emptyHint.setWrapText(true);
            emptyHint.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 12px;");
            friendsBox.getChildren().add(emptyHint);
            return;
        }

        List<String> sortedFriends = new ArrayList<>(friendSet);
        sortedFriends.sort(String.CASE_INSENSITIVE_ORDER);

        for (String friend : sortedFriends) {
            friendsBox.getChildren().add(buildFriendRow(friend));
        }
    }

    private HBox buildFriendRow(String friendUsername) {
        boolean online = onlineUsersCache.contains(friendUsername);

        Node avatar = buildAvatarNode(peerAvatars.get(friendUsername), friendUsername, 15, "#00b894");

        Circle statusDot = new Circle(4);
        statusDot.setFill(Color.web(online ? "#00b894" : "#c2c2c2"));

        Label nameLabel = new Label(friendUsername);
        nameLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 14px;");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        Label statusLabel = new Label(online ? "online" : "offline");
        statusLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 11px;");

        HBox row = new HBox(8, avatar, nameLabel, statusDot, statusLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-cursor: hand; -fx-background-radius: 8;");

        row.setOnMouseEntered(e -> row.setStyle("-fx-cursor: hand; -fx-background-radius: 8; -fx-background-color: white;"));
        row.setOnMouseExited(e -> row.setStyle("-fx-cursor: hand; -fx-background-radius: 8;"));
        row.setOnMouseClicked(e -> {
            switchBottomTab(BottomTab.CHATS);
            openDM(friendUsername);
        });

        return row;
    }

    // Универсален builder за един ред в чат списъка (global ИЛИ DM) с avatar,
    // active highlight, hover ефект, и unread badge поддръжка (Priority 4 + 5).
    private HBox buildRoomRow(String roomKey, String avatarFallbackEmoji, String displayName, String avatarId) {
        Node avatarNode = buildAvatarNode(avatarId, displayName, 18, "#00b894");

        Label nameLabel = new Label(displayName);
        nameLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 14px; -fx-font-weight: bold;");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        Label unreadBadge = new Label();
        unreadBadge.setStyle("-fx-background-color: " + ChatTheme.LILAC_6 + "; -fx-text-fill: white; "
                + "-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 1 6; -fx-background-radius: 10;");
        unreadBadge.setVisible(false);
        unreadBadge.setManaged(false);

        HBox row = new HBox(8, avatarNode, nameLabel, unreadBadge);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 8, 7, 8));
        row.setUserData(roomKey);

        roomRowsMap.put(roomKey, row);
        applyRoomRowStyle(row, roomKey.equals(currentRoom));

        row.setOnMouseEntered(e -> { if (!roomKey.equals(currentRoom)) row.setStyle(roomRowHoverStyle()); });
        row.setOnMouseExited(e -> applyRoomRowStyle(row, roomKey.equals(currentRoom)));
        row.setOnMouseClicked(e -> {
            if (roomKey.equals("global")) switchRoom("global");
            else if (roomKey.startsWith("dm_")) openDM(displayName);
        });

        return row;
    }

    private String roomRowBaseStyle(boolean active) {
        if (active) {
            return "-fx-background-color: " + uiRowActiveBg + "; -fx-background-radius: 8; -fx-cursor: hand; "
                    + "-fx-border-color: " + uiAccent + " transparent transparent transparent; "
                    + "-fx-border-width: 0 0 0 3;";
        }
        return "-fx-background-color: transparent; -fx-background-radius: 8; -fx-cursor: hand;";
    }

    private String roomRowHoverStyle() {
        return "-fx-background-color: rgba(255,255,255,0.6); -fx-background-radius: 8; -fx-cursor: hand;";
    }

    private void applyRoomRowStyle(HBox row, boolean active) {
        row.setStyle(roomRowBaseStyle(active));
    }

    private void refreshActiveRoomHighlight() {
        roomRowsMap.forEach((key, row) -> applyRoomRowStyle(row, key.equals(currentRoom)));
    }

    // ══════════════════════════════════════════════════
    // AVATAR HELPER (Priority 5)
    // ══════════════════════════════════════════════════
    private Node buildAvatarNode(String avatarId, String displayName, double radius, String fallbackColorHex) {
        Node builtIn = AvatarCatalog.build(avatarId, radius);
        if (builtIn != null) return builtIn;

        Color color;
        try { color = Color.web(fallbackColorHex); } catch (Exception e) { color = Color.GRAY; }

        Circle circle = new Circle(radius);
        circle.setFill(color);
        String initial = (displayName == null || displayName.isEmpty()) ? "?" : displayName.substring(0, 1).toUpperCase();
        Text text = new Text(initial);
        text.setFill(Color.WHITE);
        text.setStyle("-fx-font-size: " + Math.max(9, radius) + "px;");
        return new StackPane(circle, text);
    }

    // ══════════════════════════════════════════════════
    // SEARCH
    // ══════════════════════════════════════════════════
    private void doSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) { searchResultsBox.getChildren().clear(); return; }
        if (client != null) client.searchUsers(query);
    }

    private void handleSearchResults(String jsonText) {
        Type listType = new TypeToken<List<SearchResult>>() {}.getType();
        List<SearchResult> results = gson.fromJson(jsonText, listType);

        Platform.runLater(() -> {
            searchResultsBox.getChildren().clear();
            if (results == null || results.isEmpty()) {
                Label none = new Label("No users found");
                none.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 13px;");
                searchResultsBox.getChildren().add(none);
                return;
            }
            for (SearchResult sr : results) {
                searchResultsBox.getChildren().add(buildSearchResultRow(sr));
            }
        });
    }

    private HBox buildSearchResultRow(SearchResult sr) {
        Node avatar = buildAvatarNode(null, sr.username, 15, sr.color);

        Label nameLabel = new Label(sr.username);
        nameLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 14px;");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        HBox row = new HBox(8, avatar, nameLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5));
        row.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-cursor: hand;");

        if (blockedSet.contains(sr.username)) {
            Label blockedTag = new Label("🚫 Blocked");
            blockedTag.setStyle("-fx-text-fill: " + ChatTheme.DANGER_SOFT_TEXT + "; -fx-font-size: 12px; -fx-font-weight: bold;");
            row.getChildren().add(blockedTag);
        } else if (sr.isFriend) {
            Label friendTag = new Label("✓ Friends");
            friendTag.setStyle("-fx-text-fill: " + ChatTheme.SUCCESS_SOFT_TEXT + "; -fx-font-size: 12px; -fx-font-weight: bold;");
            row.getChildren().add(friendTag);
            row.setOnMouseClicked(e -> openDM(sr.username));
        } else {
            Label addBtn = new Label("+ Add");
            addBtn.setStyle("-fx-text-fill: " + ChatTheme.LILAC_6 + "; -fx-underline: true; -fx-font-size: 13px; -fx-cursor: hand; -fx-font-weight: bold;");
            addBtn.setOnMouseClicked(e -> {
                if (client != null) client.sendFriendRequest(sr.username);
                addBtn.setText("Sent ✓");
                addBtn.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 13px;");
            });
            row.getChildren().add(addBtn);
        }

        return row;
    }

    private static class SearchResult {
        String username, color;
        boolean isFriend;
    }

    // ══════════════════════════════════════════════════
    // THEME UPDATE (push от сървъра)
    // ══════════════════════════════════════════════════
    private void applyThemeUpdate(String jsonText) {
        UserDAO.ThemePreferences prefs;
        try {
            prefs = gson.fromJson(jsonText, UserDAO.ThemePreferences.class);
        } catch (Exception e) {
            return;
        }
        if (prefs == null) return;

        currentBubbleThemeId = prefs.bubbleThemeId;
        currentBackgroundThemeId = prefs.backgroundThemeId;
        currentUiThemeId = prefs.uiThemeId != null ? prefs.uiThemeId : ChatTheme.DEFAULT_UI_THEME_ID;
        applyUiThemeColors(ChatTheme.getUiThemeById(currentUiThemeId).accent);

        Platform.runLater(() -> {
            applyBackgroundPreview();
            refreshAllBubblesInChat();
            refreshUiThemeVisuals();
            refreshUiThemeSwatchSelection();
        });
    }

    private void applyBackgroundPreview() {
        if (chatBackgroundRegion != null) {
            chatBackgroundRegion.setStyle(buildChatBackgroundCss());
        }
    }

    private void refreshAllBubblesInChat() {
        ChatTheme.BubbleTheme theme = ChatTheme.getBubbleThemeById(currentBubbleThemeId);
        for (Region bubble : chatBubbleBackgrounds) {
            bubble.setStyle("-fx-padding: 11 16; -fx-background-radius: 16; -fx-font-size: 15px; "
                    + "-fx-background-color: " + theme.toFxBackground() + "; -fx-text-fill: " + theme.textColor + ";");
        }
    }

    private void saveThemeChoice() {
        if (client != null) {
            client.sendSetTheme(currentBubbleThemeId, currentBackgroundThemeId, currentUiThemeId);
        }
    }

    // ══════════════════════════════════════════════════
    // FRIEND LIST UI
    // ══════════════════════════════════════════════════
    private void updateFriendList(String jsonText) {
        Type listType = new TypeToken<List<FriendEntry>>() {}.getType();
        List<FriendEntry> friends = gson.fromJson(jsonText, listType);

        Platform.runLater(() -> {
            friendSet.clear();
            if (friends != null) {
                for (FriendEntry fe : friends) friendSet.add(fe.username);
            }
            refreshFriendsList();
        });
    }

    private static class FriendEntry {
        String username, color;
    }

    // ══════════════════════════════════════════════════
    // PENDING REQUESTS UI
    // ══════════════════════════════════════════════════
    private void updatePendingRequests(String jsonText) {
        Type listType = new TypeToken<List<String>>() {}.getType();
        List<String> pending = gson.fromJson(jsonText, listType);

        Platform.runLater(() -> {
            pendingBox.getChildren().clear();

            if (pending == null || pending.isEmpty()) {
                if (pendingSectionRef != null) {
                    pendingSectionRef.setVisible(false);
                    pendingSectionRef.setManaged(false);
                }
                return;
            }

            if (pendingSectionRef != null) {
                pendingSectionRef.setVisible(true);
                pendingSectionRef.setManaged(true);
            }

            for (String requester : pending) {
                pendingBox.getChildren().add(buildPendingRow(requester));
            }
        });
    }

    private HBox buildPendingRow(String requester) {
        Node avatar = buildAvatarNode(null, requester, 12, "#a29bfe");

        Label nameLabel = new Label(requester);
        nameLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 14px;");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        Button acceptBtn = new Button("✓");
        acceptBtn.setStyle("-fx-background-color: " + ChatTheme.SUCCESS_SOFT + "; -fx-text-fill: " + ChatTheme.SUCCESS_SOFT_TEXT
                + "; -fx-background-radius: 6; -fx-padding: 3 8; -fx-font-size: 13px; -fx-font-weight: bold; -fx-cursor: hand;");

        Button declineBtn = new Button("✗");
        declineBtn.setStyle("-fx-background-color: " + ChatTheme.DANGER_SOFT + "; -fx-text-fill: " + ChatTheme.DANGER_SOFT_TEXT
                + "; -fx-background-radius: 6; -fx-padding: 3 8; -fx-font-size: 13px; -fx-font-weight: bold; -fx-cursor: hand;");

        acceptBtn.setOnAction(e -> { if (client != null) client.sendFriendResponse(requester, true); });
        declineBtn.setOnAction(e -> { if (client != null) client.sendFriendResponse(requester, false); });

        HBox row = new HBox(6, avatar, nameLabel, acceptBtn, declineBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4));
        row.setStyle("-fx-background-color: white; -fx-background-radius: 6;");

        return row;
    }

    // ══════════════════════════════════════════════════
    // ONLINE USERS — вече вътре в Chats таба, не отделен десен панел
    // ══════════════════════════════════════════════════
    private void updateOnlineUsers(String jsonText) {
        Type listType = new TypeToken<List<String>>() {}.getType();
        List<String> users = gson.fromJson(jsonText, listType);

        Platform.runLater(() -> {
            onlineBox.getChildren().clear();
            onlineUsersCache.clear();

            if (users == null) {
                onlineTitle.setText("(0)");
                refreshFriendsList();
                return;
            }

            onlineUsersCache.addAll(users);
            onlineTitle.setText("(" + users.size() + ")");

            for (String u : users) {
                if (u.equals(username)) continue;
                HBox row = new HBox(6);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(4));
                row.setStyle("-fx-cursor: hand; -fx-background-radius: 6;");

                Node avatar = buildAvatarNode(peerAvatars.get(u), u, 13, "#00b894");
                Circle onlineDot = new Circle(3);
                onlineDot.setFill(Color.web("#00b894"));

                Label name = new Label(u);
                name.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 14px;");

                row.getChildren().addAll(avatar, onlineDot, name);

                row.setOnMouseEntered(e -> row.setStyle("-fx-cursor: hand; -fx-background-radius: 6; -fx-background-color: white;"));
                row.setOnMouseExited(e -> row.setStyle("-fx-cursor: hand; -fx-background-radius: 6;"));
                row.setOnMouseClicked(e -> handleOnlineUserClick(u, row));
                onlineBox.getChildren().add(row);
            }

            updateChatHeaderOnlineDot(users);
            refreshFriendsList();
        });
    }

    private void updateChatHeaderOnlineDot(List<String> onlineUsersList) {
        if (chatHeaderStatusDot == null) return;
        if (!currentRoom.startsWith("dm_")) {
            chatHeaderStatusDot.setVisible(false);
            return;
        }
        String otherUser = extractOtherDMUser(currentRoom);
        boolean isOnline = onlineUsersList != null && onlineUsersList.contains(otherUser);
        chatHeaderStatusDot.setVisible(isOnline);
    }

    private String extractOtherDMUser(String roomName) {
        String[] parts = roomName.split("_", 3);
        if (parts.length < 3) return "";
        return parts[1].equals(username) ? parts[2] : parts[1];
    }

    private void handleOnlineUserClick(String targetUser, HBox anchor) {
        if (targetUser.equals(username)) return;
        if (blockedSet.contains(targetUser)) return;

        if (friendSet.contains(targetUser)) {
            openDM(targetUser);
        } else {
            showFriendRequestPopup(targetUser, anchor);
        }
    }

    private void showFriendRequestPopup(String targetUser, HBox anchor) {
        Popup popup = new Popup();
        popup.setAutoHide(true);

        VBox box = new VBox(6);
        box.setStyle("-fx-background-color: white; -fx-border-color: " + ChatTheme.LILAC_6
                + "; -fx-border-radius: 10; -fx-background-radius: 10; -fx-padding: 14; "
                + "-fx-effect: dropshadow(gaussian, rgba(122,111,148,0.3), 12, 0.2, 0, 3);");
        box.setPrefWidth(220);

        Label info = new Label("You are not friends with\n" + targetUser + ".");
        info.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 14px;");

        Label sendReqBtn = new Label("Send a friend request?");
        sendReqBtn.setStyle("-fx-text-fill: " + ChatTheme.LILAC_6 + "; -fx-underline: true; -fx-cursor: hand; -fx-font-size: 14px; -fx-font-weight: bold;");
        sendReqBtn.setOnMouseClicked(e -> {
            if (client != null) client.sendFriendRequest(targetUser);
            popup.hide();
        });

        box.getChildren().addAll(info, sendReqBtn);
        popup.getContent().add(box);

        var bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds != null) {
            popup.show(mainStage, bounds.getMinX(), bounds.getMaxY() + 4);
        } else {
            popup.show(mainStage);
        }
    }

    // ══════════════════════════════════════════════════
    // DM / ROOM SWITCHING
    // ══════════════════════════════════════════════════
    private void openDM(String otherUser) {
        if (otherUser.equals(username)) return;

        String roomName = username.compareTo(otherUser) < 0
                ? "dm_" + username + "_" + otherUser
                : "dm_" + otherUser + "_" + username;

        if (roomName.equals(currentRoom)) return;

        currentRoom = roomName;
        clearChat();
        addDMToSidebar(otherUser);
        refreshActiveRoomHighlight();
        updateChatTitle("💬 " + otherUser);
        clearUnread(roomName);

        if (client != null) client.sendRoomJoin(roomName);
    }

    private void switchRoom(String room) {
        if (room.equals(currentRoom)) return;
        currentRoom = room;
        clearChat();
        refreshActiveRoomHighlight();
        updateChatTitle(room.equals("global") ? "🌍 Global" : room);
        clearUnread(room);
        if (chatHeaderStatusDot != null) chatHeaderStatusDot.setVisible(false);
        if (client != null) client.sendRoomJoin(room);
    }

    private void updateChatTitle(String text) {
        if (chatTitleLabel != null) {
            chatTitleLabel.setText(text);
        }
    }

    private void addDMToSidebar(String user) {
        String roomKey = username.compareTo(user) < 0 ? "dm_" + username + "_" + user : "dm_" + user + "_" + username;
        if (roomRowsMap.containsKey(roomKey)) return;

        HBox row = buildRoomRow(roomKey, "💬", user, peerAvatars.get(user));
        dmRowsByUsername.put(user, row);
        dmBox.getChildren().add(row);
    }

    // Обновява avatar-а на вече построен DM ред, когато данни за него
    // пристигнат/се променят СЛЕД като редът вече е бил построен (напр.
    // партньорът е бил offline при login-а ни, после се логва — идва нов
    // avatar_directory push). Преди avatar-ът се "запечатваше" само веднъж
    // при построяването на реда и никога не се опресняваше.
    private void refreshDmAvatar(String user) {
        HBox row = dmRowsByUsername.get(user);
        if (row == null || row.getChildren().isEmpty()) return;
        String avatarId = peerAvatars.get(user);
        if (avatarId == null) return;
        Node freshAvatar = buildAvatarNode(avatarId, user, 18, "#00b894");
        row.getChildren().set(0, freshAvatar);
    }

    private void refreshAllDmAvatars() {
        for (String user : dmRowsByUsername.keySet()) refreshDmAvatar(user);
    }

    // ══════════════════════════════════════════════════
    // UNREAD BADGES (Priority 4)
    // ══════════════════════════════════════════════════
    private void incrementUnread(String roomKey) {
        if (roomKey.equals(currentRoom)) return;
        int count = unreadCounts.getOrDefault(roomKey, 0) + 1;
        unreadCounts.put(roomKey, count);
        applyUnreadBadge(roomKey, count);
    }

    private void clearUnread(String roomKey) {
        unreadCounts.remove(roomKey);
        applyUnreadBadge(roomKey, 0);
    }

    private void applyUnreadBadge(String roomKey, int count) {
        HBox row = roomRowsMap.get(roomKey);
        if (row == null || row.getChildren().size() < 3) return;
        Node last = row.getChildren().get(row.getChildren().size() - 1);
        if (!(last instanceof Label badge)) return;

        if (count > 0) {
            badge.setText(String.valueOf(count));
            badge.setVisible(true);
            badge.setManaged(true);
        } else {
            badge.setVisible(false);
            badge.setManaged(false);
        }
    }

    // ══════════════════════════════════════════════════
    // NETWORKING
    // ══════════════════════════════════════════════════
    // client вече е свързан и автентикиран ПРЕДИ openChat() да бъде
    // извикан (виж Client.login() в joinButton handler-а) — тук само
    // wire-ваме input/sendButton към него. Преди този метод сам отваряше
    // ВТОРА socket връзка (new Client(username, password, ...)), което
    // заедно с login button-а правеше 2 connection-а за всеки login.
    private void connectToServer(TextField input, Button sendButton) {
        try {
            sendButton.setOnAction(e -> sendMessage(input));
            input.setOnAction(e -> sendMessage(input));
        } catch (Exception e) {
            Platform.runLater(() -> chatBox.getChildren().add(new Label("❌ Cannot connect to server")));
        }
    }

    // Обработва всяко съобщение, дошло от сървъра по постоянната връзка
    // (извиква се от Client listener-thread-а, НЕ от FX Application Thread —
    // всяко UI обновление вътре минава през Platform.runLater, освен
    // случаите с директни *-методи, които САМИ вече правят runLater вътре).
    private void handleServerMessage(String jsonMessage) {
        Message msg;
        try {
            msg = gson.fromJson(jsonMessage, Message.class);
        } catch (Exception ex) {
            return;
        }
        if (msg == null) return;

        switch (msg.type != null ? msg.type : "") {

                    case "online_users" -> updateOnlineUsers(msg.text);
                    case "avatar_directory" -> applyAvatarDirectory(msg.text);
                    case "friend_list" -> updateFriendList(msg.text);
                    case "pending_requests" -> updatePendingRequests(msg.text);
                    case "user_search_result" -> handleSearchResults(msg.text);
                    case "theme_update" -> applyThemeUpdate(msg.text);
                    case "blocked_list" -> applyBlockedList(msg.text);
                    case "dm_conversations" -> applyDmConversations(msg.text);
                    case "profile_info" -> applyProfileInfo(msg.text);

                    case "username_changed" -> Platform.runLater(() -> {
                        this.username = msg.text;
                        client.updateUsername(msg.text);
                        showInlineNotice("✅ Username changed to " + msg.text);
                    });

                    case "account_deleted" -> Platform.runLater(() -> {
                        disconnectSocket();
                        navigateTo(ViewState.LOGIN);
                        loginStatusLabel.setText("Account deleted. Goodbye! 👋");
                    });

                    case "system" -> Platform.runLater(() ->
                            chatBox.getChildren().add(animatedMessage(createMessage(jsonMessage))));

                    case "dm" -> {
                        String otherUser = msg.user.equals(username) ? msg.receiver : msg.user;
                        Platform.runLater(() -> {
                            addDMToSidebar(otherUser);
                            String dmRoomKey = username.compareTo(otherUser) < 0
                                    ? "dm_" + username + "_" + otherUser : "dm_" + otherUser + "_" + username;

                            if (msg.room != null && msg.room.equals(currentRoom)) {
                                chatBox.getChildren().add(animatedMessage(createMessage(jsonMessage)));
                            } else if (!msg.user.equals(username)) {
                                incrementUnread(dmRoomKey);
                            }
                        });
                    }

                    default -> Platform.runLater(() -> {
                        if (msg.room != null && msg.room.equals(currentRoom)) {
                            chatBox.getChildren().add(animatedMessage(createMessage(jsonMessage)));
                        } else if (msg.room != null && !"error".equals(msg.type)) {
                            incrementUnread(msg.room);
                        }
                    });
        }
    }

    // Малък helper само за локални UI потвърждения (не минава по мрежата)
    private void showInlineNotice(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-style: italic; -fx-font-size: 13px; -fx-padding: 4;");
        chatBox.getChildren().add(l);
    }

    // Прилага avatar directory (username -> avatarId за online потребители).
    // Това решава "само аз виждам своя avatar" — обновяваме локалния кеш и
    // презареждаме online списъка, за да покаже правилните avatar-и веднага.
    private void applyAvatarDirectory(String jsonText) {
        Type mapType = new TypeToken<Map<String, String>>() {}.getType();
        Map<String, String> directory = gson.fromJson(jsonText, mapType);

        Platform.runLater(() -> {
            // FIX: преди тук правехме peerAvatars.clear() ПРЕДИ putAll — но
            // avatar_directory съдържа avatar-и САМО за ONLINE потребители, а
            // peerAvatars пази и avatar-ите на OFFLINE DM партньори (дошли
            // отделно през dm_conversations push). Clear() тук изтриваше тия
            // offline записи при всяко online/offline събитие на когото и да е
            // друг потребител. Сега само мъргваме — не трием нищо.
            if (directory != null) peerAvatars.putAll(directory);
            refreshAllDmAvatars();
            refreshFriendsList();
        });
    }

    // Прилага DM списъка от реална история (push при login). Решава
    // "чатовете изчезват при login, ако другия не е online" — addDMToSidebar
    // вече се извиква веднъж тук за ВСЕКИ реален DM партньор, независимо от
    // online статус, вместо да зависим единствено от reactive добавяне.
    //
    // FIX: payload-ът вече носи и avatar-ите на партньорите (виж
    // ClientHandler.pushDmConversations) — не разчитаме повече само на
    // avatar_directory (който съдържа avatar-и САМО за online потребители).
    // Мъргваме тия avatar-и в peerAvatars ПРЕДИ да построим редовете, за да
    // не се роди ред с празен avatar заради timing/online-статус.
    private void applyDmConversations(String jsonText) {
        DmConversationsPayload payload = gson.fromJson(jsonText, DmConversationsPayload.class);

        Platform.runLater(() -> {
            if (payload == null) return;
            if (payload.avatars != null) peerAvatars.putAll(payload.avatars);
            if (payload.partners != null) {
                for (String partner : payload.partners) {
                    addDMToSidebar(partner);
                }
            }
            refreshAllDmAvatars();
            refreshFriendsList();
        });
    }

    private static class DmConversationsPayload {
        List<String> partners;
        Map<String, String> avatars;
    }

    private void applyBlockedList(String jsonText) {
        Type listType = new TypeToken<List<String>>() {}.getType();
        List<String> blocked = gson.fromJson(jsonText, listType);
        Platform.runLater(() -> {
            blockedSet.clear();
            if (blocked != null) blockedSet.addAll(blocked);
            refreshBlockedListUI();
        });
    }

    private VBox blockedListBoxRef;

    private void refreshBlockedListUI() {
        if (blockedListBoxRef == null) return;
        blockedListBoxRef.getChildren().clear();

        if (blockedSet.isEmpty()) {
            Label none = new Label("No blocked users");
            none.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 13px;");
            blockedListBoxRef.getChildren().add(none);
            return;
        }

        for (String blockedUser : blockedSet) {
            Label nameLabel = new Label(blockedUser);
            nameLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 14px;");
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            Button unblockBtn = new Button("Unblock");
            unblockBtn.setStyle("-fx-background-color: " + ChatTheme.LILAC_6 + "; -fx-text-fill: white; "
                    + "-fx-font-size: 12px; -fx-background-radius: 6; -fx-padding: 3 8; -fx-cursor: hand;");
            unblockBtn.setOnAction(e -> { if (client != null) client.sendUnblockUser(blockedUser); });

            HBox row = new HBox(8, nameLabel, unblockBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(5));
            row.setStyle("-fx-background-color: white; -fx-background-radius: 6;");
            blockedListBoxRef.getChildren().add(row);
        }
    }

    private void applyProfileInfo(String jsonText) {
        ProfileInfo info;
        try {
            info = gson.fromJson(jsonText, ProfileInfo.class);
        } catch (Exception e) {
            return;
        }
        if (info == null) return;

        myAvatarId = info.avatarId;
        showOnlineStatus = info.showOnlineStatus;

        Platform.runLater(this::refreshProfileTabUI);
    }

    private static class ProfileInfo {
        String username;
        String avatarId;
        boolean showOnlineStatus;
    }

    private Runnable profileTabRefreshHook;

    private void refreshProfileTabUI() {
        if (profileTabRefreshHook != null) profileTabRefreshHook.run();
    }

    // ══════════════════════════════════════════════════
    // SEND MESSAGE
    // ══════════════════════════════════════════════════
    private void sendMessage(TextField input) {
        String text = input.getText().trim();
        if (text.isEmpty() || client == null) return;

        if (currentRoom.startsWith("dm_")) {
            String receiver = extractOtherDMUser(currentRoom);
            client.sendDirectMessage(receiver, text, currentRoom);
        } else {
            client.sendChatMessage(currentRoom, text);
        }
        input.clear();
    }

    // ══════════════════════════════════════════════════
    // MESSAGE RENDERING (Priority 5: avatars навсякъде, Priority 8: fade-in)
    // ══════════════════════════════════════════════════
    private Node animatedMessage(Node node) {
        node.setOpacity(0);
        node.setTranslateY(8);

        FadeTransition fade = new FadeTransition(Duration.millis(220), node);
        fade.setFromValue(0);
        fade.setToValue(1);

        TranslateTransition slide = new TranslateTransition(Duration.millis(220), node);
        slide.setFromY(8);
        slide.setToY(0);

        fade.play();
        slide.play();
        return node;
    }

    private HBox createMessage(String json) {
        Message msg = gson.fromJson(json, Message.class);

        String sender   = msg.user  != null ? msg.user  : "Unknown";
        String text     = msg.text  != null ? msg.text  : "";
        String colorStr = msg.color != null ? msg.color : "#808080";
        String time     = msg.timestamp;

        if ("system".equals(msg.type) || "leave".equals(msg.type) || "room_join".equals(msg.type)) {
            Label sysLabel = new Label(text.isEmpty() ? "➡️ " + sender + " joined" : text);
            sysLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-style: italic; -fx-font-size: 14px; -fx-padding: 6;");
            HBox sysRow = new HBox(sysLabel);
            sysRow.setAlignment(Pos.CENTER);
            HBox.setHgrow(sysRow, Priority.ALWAYS);
            sysRow.setMaxWidth(Double.MAX_VALUE);
            return sysRow;
        }

        boolean isMe = sender.equals(username);

        // ГЛАВЕН ФИКС: avatar-ът пътува директно в съобщението (msg.avatarId),
        // зададен от сървъра в момента на изпращане — затова работи дори ако
        // подателят вече не е online, и не зависи от локален кеш на тоя клиент.
        String avatarIdForThisMessage = isMe ? myAvatarId : msg.avatarId;
        Node avatar = buildAvatarNode(avatarIdForThisMessage, sender, 19, colorStr);

        Label nameLabel = new Label(isMe ? "You" : sender);
        nameLabel.setStyle((isMe ? "-fx-text-fill: " + ChatTheme.TEXT_MUTED + ";" : "-fx-text-fill: " + colorStr + ";")
                + " -fx-font-size: 13px; -fx-font-weight: bold;");

        ChatTheme.BubbleTheme theme = ChatTheme.getBubbleThemeById(currentBubbleThemeId);

        Label msgLabel = new Label(text);
        msgLabel.setWrapText(true);

        if (chatScrollPane != null) {
            msgLabel.maxWidthProperty().bind(chatScrollPane.widthProperty().multiply(0.65));
        } else {
            msgLabel.setMaxWidth(320);
        }
        msgLabel.setMinWidth(Region.USE_PREF_SIZE);

        if (isMe) {
            msgLabel.setStyle("-fx-padding: 11 16; -fx-background-radius: 16; -fx-font-size: 15px; "
                    + "-fx-background-color: " + theme.toFxBackground() + "; -fx-text-fill: " + theme.textColor + ";");
            chatBubbleBackgrounds.add(msgLabel);
        } else {
            msgLabel.setStyle("-fx-padding: 11 16; -fx-background-radius: 16; -fx-font-size: 15px; "
                    + "-fx-background-color: white; -fx-text-fill: " + ChatTheme.TEXT_DARK + ";");
        }

        Label timeLabel = new Label(time != null ? time : "");
        timeLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + ChatTheme.TEXT_MUTED + ";");

        VBox content = new VBox(3, nameLabel, msgLabel, timeLabel);

        HBox row = new HBox(10);
        row.setMaxWidth(Double.MAX_VALUE);
        if (isMe) {
            content.setAlignment(Pos.TOP_RIGHT);
            row.setAlignment(Pos.TOP_RIGHT);
            row.getChildren().addAll(content, avatar);
        } else {
            content.setAlignment(Pos.TOP_LEFT);
            row.setAlignment(Pos.TOP_LEFT);
            row.getChildren().addAll(avatar, content);
        }

        return row;
    }

    // ══════════════════════════════════════════════════
    // SETTINGS TAB (Priority 3 + 7: tabbed settings page)
    // ══════════════════════════════════════════════════
    private VBox buildSettingsTabContent() {
        // Заменяме стандартния TabPane (системен сив стил, падаща стрелка
        // при overflow) с ръчни pill-бутони — изглеждат консистентно с
        // останалата пастелна палитра, същия модел като bottom nav-а.
        ScrollPane profilePane = buildProfileSettingsPane();
        ScrollPane privacyPane = buildPrivacySettingsPane();
        ScrollPane socialPane = buildSocialSettingsPane();
        ScrollPane dangerPane = buildDangerZonePane();

        StackPane contentSwitcher = new StackPane(profilePane, privacyPane, socialPane, dangerPane);
        VBox.setVgrow(contentSwitcher, Priority.ALWAYS);

        record SettingsSection(String label, ScrollPane pane) {}
        List<SettingsSection> sections = List.of(
                new SettingsSection("Profile", profilePane),
                new SettingsSection("Privacy", privacyPane),
                new SettingsSection("Social", socialPane),
                new SettingsSection("Danger", dangerPane)
        );

        for (ScrollPane p : List.of(privacyPane, socialPane, dangerPane)) {
            p.setVisible(false);
            p.setManaged(false);
        }

        FlowPane pillBar = new FlowPane(6, 6);
        pillBar.setPadding(new Insets(10, 10, 6, 10));

        Map<String, Button> pillButtons = new LinkedHashMap<>();
        for (SettingsSection section : sections) {
            Button pill = new Button(section.label());
            pillButtons.put(section.label(), pill);
            pill.setOnAction(e -> {
                for (SettingsSection s : sections) {
                    boolean active = s.label().equals(section.label());
                    s.pane().setVisible(active);
                    s.pane().setManaged(active);
                }
                refreshSettingsPillStyles(pillButtons, section.label());
            });
            pillBar.getChildren().add(pill);
        }
        refreshSettingsPillStyles(pillButtons, "Profile");

        VBox container = new VBox(pillBar, contentSwitcher);
        VBox.setVgrow(contentSwitcher, Priority.ALWAYS);
        return container;
    }

    // Стилизира pill бутоните на Settings таб лентата — активният е плътен
    // лилав (като "Save Username" бутона от референцията), останалите бели.
    private void refreshSettingsPillStyles(Map<String, Button> pills, String activeLabel) {
        pills.forEach((label, btn) -> {
            boolean active = label.equals(activeLabel);
            btn.setStyle("-fx-font-size: 12px; -fx-padding: 7 14; -fx-background-radius: 18; -fx-cursor: hand; "
                    + (active
                        ? "-fx-background-color: " + ChatTheme.LILAC_6 + "; -fx-text-fill: white; -fx-font-weight: bold;"
                        : "-fx-background-color: white; -fx-text-fill: " + ChatTheme.TEXT_DARK + ";"));
        });
    }

    private ScrollPane wrapInScroll(VBox content) {
        content.setPadding(new Insets(16));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        return scroll;
    }

    private ScrollPane buildProfileSettingsPane() {
        Label sectionTitle = new Label("PROFILE");
        sectionTitle.setStyle(settingsSectionTitleStyle());

        Label usernameLabel = new Label("Username");
        usernameLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + ChatTheme.TEXT_MUTED + ";");

        TextField usernameField = new TextField(username);
        usernameField.setStyle(authFieldStyle());

        Button saveUsernameBtn = new Button("Save Username");
        saveUsernameBtn.setStyle(primaryButtonStyle());
        Label usernameStatusLabel = new Label();
        usernameStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + ChatTheme.TEXT_MUTED + ";");

        saveUsernameBtn.setOnAction(e -> {
            String newName = usernameField.getText().trim();
            if (newName.isEmpty()) return;
            if (client != null) client.sendChangeUsername(newName);
            usernameStatusLabel.setText("Saving...");
        });

        Label avatarSectionLabel = new Label("Choose an avatar");
        avatarSectionLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 13px; -fx-font-weight: bold;");

        FlowPane avatarGrid = new FlowPane(10, 10);
        List<Node> avatarSwatches = new ArrayList<>();

        for (AvatarCatalog.AvatarDef def : AvatarCatalog.ALL_AVATARS) {
            Node avatarPreview = AvatarCatalog.build(def.id, 24);
            VBox box = new VBox(4, avatarPreview);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(6));
            box.setUserData(def.id);
            box.setStyle("-fx-background-radius: 12; -fx-cursor: hand;");
            box.setOnMouseClicked(e -> {
                if (client != null) client.sendSetAvatar(def.id);
            });
            avatarSwatches.add(box);
            avatarGrid.getChildren().add(box);
        }

        Label noneMark = new Label("—");
        VBox noneBox = new VBox(4, noneMark);
        noneBox.setAlignment(Pos.CENTER);
        noneBox.setPadding(new Insets(6));
        noneBox.setUserData(null);
        noneBox.setStyle("-fx-background-radius: 12; -fx-cursor: hand; -fx-border-color: " + ChatTheme.TEXT_MUTED + "; -fx-border-radius: 12;");
        noneBox.setOnMouseClicked(e -> { if (client != null) client.sendSetAvatar(""); });
        avatarSwatches.add(noneBox);
        avatarGrid.getChildren().add(noneBox);

        Runnable refreshAvatarSelection = () -> {
            for (Node n : avatarSwatches) {
                if (!(n instanceof VBox box)) continue;
                boolean selected = Objects.equals(myAvatarId, box.getUserData());
                box.setStyle("-fx-background-radius: 12; -fx-cursor: hand; -fx-padding: 6; "
                        + (selected ? "-fx-border-color: " + ChatTheme.LILAC_6 + "; -fx-border-width: 2; -fx-border-radius: 12;"
                                    : "-fx-border-color: transparent; -fx-border-width: 2;"));
            }
        };
        refreshAvatarSelection.run();
        this.profileTabRefreshHook = refreshAvatarSelection;

        Label futureNote = new Label("📌 Uploading a custom avatar from your device is coming in a future update.");
        futureNote.setWrapText(true);
        futureNote.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 12px; -fx-font-style: italic;");

        VBox content = new VBox(12,
                sectionTitle,
                usernameLabel, usernameField, saveUsernameBtn, usernameStatusLabel,
                new Separator(),
                avatarSectionLabel, avatarGrid, futureNote
        );
        return wrapInScroll(content);
    }

    private String settingsSectionTitleStyle() {
        return "-fx-text-fill: " + ChatTheme.LILAC_6 + "; -fx-font-size: 15px; -fx-font-weight: bold;";
    }

    private ScrollPane buildPrivacySettingsPane() {
        Label sectionTitle = new Label("PRIVACY");
        sectionTitle.setStyle(settingsSectionTitleStyle());

        Label toggleLabel = new Label("Show Online Status");
        toggleLabel.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 15px;");

        CheckBox onlineToggle = new CheckBox();
        onlineToggle.setSelected(showOnlineStatus);
        onlineToggle.setOnAction(e -> {
            boolean visible = onlineToggle.isSelected();
            if (client != null) client.sendSetOnlineVisibility(visible);
        });

        HBox toggleRow = new HBox(10, toggleLabel, onlineToggle);
        toggleRow.setAlignment(Pos.CENTER_LEFT);

        Label explanation = new Label("When disabled, other users won't see you in the online list, even while you're connected.");
        explanation.setWrapText(true);
        explanation.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 13px;");

        Label futureNote = new Label("📌 \"Last Seen\" display is planned for a future update.");
        futureNote.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 12px; -fx-font-style: italic;");

        VBox content = new VBox(12, sectionTitle, toggleRow, explanation, futureNote);
        return wrapInScroll(content);
    }

    private Node buildBackgroundThemeSwatch(ChatTheme.BackgroundTheme theme, List<Node> allSwatches) {
        Region preview = new Region();
        preview.setPrefSize(46, 30);
        preview.setStyle(theme.toCss() + " -fx-background-radius: 10;");

        Label nameLabel = new Label(theme.displayName);
        nameLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + ChatTheme.TEXT_MUTED + ";");

        VBox box = new VBox(3, preview, nameLabel);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(4));
        box.setUserData(theme.id);
        box.setStyle("-fx-background-radius: 10; -fx-cursor: hand;");

        box.setOnMouseClicked(e -> {
            currentBackgroundThemeId = theme.id;
            refreshBackgroundSwatchSelection(allSwatches);
            applyBackgroundPreview();
            saveThemeChoice();
        });

        allSwatches.add(box);
        return box;
    }

    private void refreshBackgroundSwatchSelection(List<Node> swatches) {
        for (Node n : swatches) {
            if (!(n instanceof VBox box)) continue;
            boolean selected = currentBackgroundThemeId.equals(box.getUserData());
            box.setStyle("-fx-background-radius: 10; -fx-cursor: hand; -fx-padding: 4; "
                    + (selected ? "-fx-border-color: " + ChatTheme.LILAC_6 + "; -fx-border-width: 2; -fx-border-radius: 10;"
                                : "-fx-border-color: transparent; -fx-border-width: 2;"));
        }
    }

    private Node buildBubbleThemeSwatch(ChatTheme.BubbleTheme theme, List<Node> allSwatches) {
        Region preview = new Region();
        preview.setPrefSize(46, 30);
        preview.setStyle("-fx-background-color: " + theme.toFxBackground() + "; -fx-background-radius: 10;");

        Label nameLabel = new Label(theme.displayName);
        nameLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + ChatTheme.TEXT_MUTED + ";");

        VBox box = new VBox(3, preview, nameLabel);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(4));
        box.setUserData(theme.id);
        box.setStyle("-fx-background-radius: 10; -fx-cursor: hand;");

        box.setOnMouseClicked(e -> {
            currentBubbleThemeId = theme.id;
            refreshBubbleSwatchSelection(allSwatches);
            refreshAllBubblesInChat();
            saveThemeChoice();
        });

        allSwatches.add(box);
        return box;
    }

    private void refreshBubbleSwatchSelection(List<Node> swatches) {
        for (Node n : swatches) {
            if (!(n instanceof VBox box)) continue;
            boolean selected = currentBubbleThemeId.equals(box.getUserData());
            box.setStyle("-fx-background-radius: 10; -fx-cursor: hand; -fx-padding: 4; "
                    + (selected ? "-fx-border-color: " + ChatTheme.LILAC_6 + "; -fx-border-width: 2; -fx-border-radius: 10;"
                                : "-fx-border-color: transparent; -fx-border-width: 2;"));
        }
    }

    // UI THEME swatch — избира accent цвят за ляв панел / bottom-nav /
    // chat header (виж applyUiThemeColors). Различно от bubble/background
    // swatch-овете по-горе: тук не пазим готова палитра, а извеждаме нужните
    // нюанси програмно от единствения избран hex цвят.
    private Node buildUiThemeSwatch(ChatTheme.UiTheme theme) {
        Region preview = new Region();
        preview.setPrefSize(30, 30);
        preview.setStyle("-fx-background-color: " + theme.accent + "; -fx-background-radius: 15;");

        Label nameLabel = new Label(theme.displayName);
        nameLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: " + ChatTheme.TEXT_MUTED + ";");

        VBox box = new VBox(3, preview, nameLabel);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(4));
        box.setUserData(theme.id);
        box.setStyle("-fx-background-radius: 10; -fx-cursor: hand;");

        box.setOnMouseClicked(e -> {
            currentUiThemeId = theme.id;
            applyUiThemeColors(theme.accent);
            refreshUiThemeVisuals();
            refreshUiThemeSwatchSelection();
            saveThemeChoice();
        });

        uiThemeSwatchNodes.add(box);
        return box;
    }

    private void refreshUiThemeSwatchSelection() {
        for (Node n : uiThemeSwatchNodes) {
            if (!(n instanceof VBox box)) continue;
            boolean selected = currentUiThemeId.equals(box.getUserData());
            box.setStyle("-fx-background-radius: 10; -fx-cursor: hand; -fx-padding: 4; "
                    + (selected ? "-fx-border-color: " + ChatTheme.TEXT_DARK + "; -fx-border-width: 2; -fx-border-radius: 10;"
                                : "-fx-border-color: transparent; -fx-border-width: 2;"));
        }
    }

    private ScrollPane buildSocialSettingsPane() {
        Label friendsSectionTitle = new Label("FRIENDS");
        friendsSectionTitle.setStyle(settingsSectionTitleStyle());
        Label friendsHint = new Label("Manage your friends from the Chats tab — click any chat to message them.");
        friendsHint.setWrapText(true);
        friendsHint.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 13px;");

        Label blockedSectionTitle = new Label("BLOCKED USERS");
        blockedSectionTitle.setStyle(settingsSectionTitleStyle());

        VBox blockedListBox = new VBox(6);
        this.blockedListBoxRef = blockedListBox;
        refreshBlockedListUI();

        Label blockHint = new Label("To block someone, type their username below.");
        blockHint.setWrapText(true);
        blockHint.setStyle("-fx-text-fill: " + ChatTheme.TEXT_MUTED + "; -fx-font-size: 13px;");

        TextField blockSearchField = new TextField();
        blockSearchField.setPromptText("Username to block...");
        blockSearchField.setStyle(authFieldStyle());

        Button blockBtn = new Button("Block User");
        blockBtn.setStyle("-fx-background-color: " + ChatTheme.DANGER_SOFT + "; -fx-text-fill: " + ChatTheme.DANGER_SOFT_TEXT + "; -fx-font-weight: bold; "
                + "-fx-background-radius: 10; -fx-padding: 8 14; -fx-font-size: 14px; -fx-cursor: hand;");
        blockBtn.setOnAction(e -> {
            String target = blockSearchField.getText().trim();
            if (!target.isEmpty() && client != null) {
                client.sendBlockUser(target);
                blockSearchField.clear();
            }
        });

        HBox blockRow = new HBox(8, blockSearchField, blockBtn);
        blockRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12,
                friendsSectionTitle, friendsHint,
                new Separator(),
                blockedSectionTitle, blockedListBox,
                blockHint, blockRow
        );
        return wrapInScroll(content);
    }

    private ScrollPane buildDangerZonePane() {
        Label sectionTitle = new Label("⚠️ DANGER ZONE");
        sectionTitle.setStyle("-fx-text-fill: " + ChatTheme.DANGER_SOFT_TEXT + "; -fx-font-size: 15px; -fx-font-weight: bold;");

        Label warning = new Label("Deleting your account is permanent and cannot be undone. "
                + "Your chat history will remain visible to others, but your profile will be gone.");
        warning.setWrapText(true);
        warning.setStyle("-fx-text-fill: " + ChatTheme.TEXT_DARK + "; -fx-font-size: 13px;");

        PasswordField confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText("Confirm your password...");
        confirmPasswordField.setStyle(authFieldStyle());

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + ChatTheme.DANGER_SOFT_TEXT + ";");

        Button deleteBtn = new Button("Delete My Account");
        deleteBtn.setStyle("-fx-background-color: " + ChatTheme.DANGER_SOFT + "; -fx-text-fill: " + ChatTheme.DANGER_SOFT_TEXT + "; -fx-font-weight: bold; "
                + "-fx-background-radius: 10; -fx-padding: 10 18; -fx-font-size: 15px; -fx-cursor: hand;");

        deleteBtn.setOnAction(e -> {
            String pass = confirmPasswordField.getText();
            if (pass.isEmpty()) {
                statusLabel.setText("Please enter your password to confirm.");
                return;
            }
            if (client != null) {
                client.sendDeleteAccount(pass);
                statusLabel.setText("Processing...");
            }
        });

        VBox dangerBox = new VBox(10, sectionTitle, warning, confirmPasswordField, statusLabel, deleteBtn);
        dangerBox.setStyle("-fx-background-color: " + ChatTheme.DANGER_SOFT_BG + "; -fx-background-radius: 12; -fx-padding: 16; "
                + "-fx-border-color: " + ChatTheme.DANGER_SOFT + "; -fx-border-width: 1.5; -fx-border-radius: 12;");

        VBox content = new VBox(dangerBox);
        return wrapInScroll(content);
    }

    // ══════════════════════════════════════════════════
    // UTILS
    // ══════════════════════════════════════════════════
    private void clearChat() {
        chatBox.getChildren().clear();
        chatBox.setSpacing(10);
        chatBox.setStyle("-fx-padding: 10;");
        chatBubbleBackgrounds.clear();
    }

    private void disconnectSocket() {
        if (client != null) {
            client.closeEverything();
        }
    }

    @Override
    public void stop() {
        disconnectSocket();
    }

    public static void main(String[] args) {
        launch(args);
    }
}