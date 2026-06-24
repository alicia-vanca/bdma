package com.app.auth.login.controllers;

import com.app.MainApp;
import com.app.auth.totp.services.TotpPromptService;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.ViewPaths;
import com.app.common.definitions.enums.LoginResult;
import com.app.common.definitions.enums.Role;
import com.app.common.events.ThemeChangedEvent;
import com.app.common.helpers.DialogHelper;
import com.app.common.models.User;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.modules.theme.ThemeManager;
import com.app.common.repositories.RecentUsernameRepository;
import com.app.common.services.UserService;
import com.app.common.services.UserSettingService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Side;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    @FXML
    private TextField username;
    @FXML
    private PasswordField password;
    @FXML
    private TextField passwordVisible;
    @FXML
    private Button btnPeekPassword;
    @FXML
    private Label message;
    @FXML
    private Button btnLogin;

    private final Session session;
    private final UserService userService;
    private final UserSettingService userSettingService;
    private final RecentUsernameRepository recentUsernameRepository;
    private final TotpPromptService totpPromptService;

    private ImageView passwordIconView;

    public LoginController(Session session,
            UserService userService,
            UserSettingService userSettingService,
            RecentUsernameRepository recentUsernameRepository,
            TotpPromptService totpPromptService) {
        this.session = session;
        this.userService = userService;
        this.userSettingService = userSettingService;
        this.recentUsernameRepository = recentUsernameRepository;
        this.totpPromptService = totpPromptService;
    }

    @FXML
    public void initialize() {
        hideError();

        // Setup password peek functionality
        setupPasswordPeek();

        // Setup username dropdown with recent usernames
        setupUsernameDropdown();

        username.textProperty().addListener((obs, oldVal, newVal) -> hideError());
        password.textProperty().addListener((obs, oldVal, newVal) -> hideError());

        username.setOnAction(e -> handleLogin());
        password.setOnAction(e -> handleLogin());
    }

    /**
     * Sets up password peek button with hold-to-show functionality.
     * User can press and hold the button to temporarily reveal the password.
     */
    private void setupPasswordPeek() {
        passwordIconView = new ImageView();
        passwordIconView.setFitWidth(20);
        passwordIconView.setFitHeight(20);
        btnPeekPassword.setGraphic(passwordIconView);

        // Load initial icon based on current theme
        updatePasswordIcon(false);

        // Bind text fields together
        passwordVisible.textProperty().bindBidirectional(password.textProperty());

        // Show password on mouse press, hide on release
        btnPeekPassword.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            password.setVisible(false);
            password.setManaged(false);
            passwordVisible.setVisible(true);
            passwordVisible.setManaged(true);
            updatePasswordIcon(true);
        });

        btnPeekPassword.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            passwordVisible.setVisible(false);
            passwordVisible.setManaged(false);
            password.setVisible(true);
            password.setManaged(true);
            updatePasswordIcon(false);
        });

        // Handle case when mouse exits button while pressed
        btnPeekPassword.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (passwordVisible.isVisible()) {
                passwordVisible.setVisible(false);
                passwordVisible.setManaged(false);
                password.setVisible(true);
                password.setManaged(true);
                updatePasswordIcon(false);
            }
        });
    }

    /**
     * Sets up username dropdown showing recent usernames as context menu.
     * When user clicks on username field, displays a menu with recent usernames.
     * Menu is hidden when user starts typing.
     */
    private void setupUsernameDropdown() {
        ContextMenu contextMenu = new ContextMenu();

        username.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty()) {
                contextMenu.hide();
            }
        });

        username.setOnMouseClicked(e -> {
            List<String> recents = recentUsernameRepository.findRecent();
            contextMenu.getItems().clear();

            if (!recents.isEmpty()) {
                for (String recent : recents) {
                    MenuItem item = new MenuItem(recent);
                    item.setOnAction(action -> {
                        username.setText(recent);
                        contextMenu.hide();
                    });
                    contextMenu.getItems().add(item);
                }

                Platform.runLater(() -> {
                    Bounds bounds = username.localToScreen(username.getBoundsInLocal());
                    double width = bounds.getWidth();
                    contextMenu.setMinWidth(width);
                    contextMenu.setMaxWidth(width);
                    contextMenu.setPrefWidth(width);
                    contextMenu.show(username, Side.BOTTOM, -1, 0);
                });
            }
        });
    }

    /**
     * Updates the password peek icon based on current theme and visibility state.
     *
     * @param isOpen true if password is currently visible, false if hidden
     */
    private void updatePasswordIcon(boolean isOpen) {
        String theme = ThemeManager.getTheme();
        boolean isDark = AppConstants.THEME_DARK.equals(theme);

        String iconPath;
        if (isOpen) {
            iconPath = isDark ? "/image/eye-open-dark.png" : "/image/eye-open-light.png";
        } else {
            iconPath = isDark ? "/image/eye-closed-dark.png" : "/image/eye-closed-light.png";
        }

        Image icon = new Image(getClass().getResourceAsStream(iconPath));
        passwordIconView.setImage(icon);
    }

    @FXML
    private void handleLogin() {
        String usernameText = username.getText();
        String passwordText = password.getText();

        if (usernameText == null || usernameText.isBlank()) {
            showError("login.error.username.required");
            return;
        }

        if (passwordText == null || passwordText.isBlank()) {
            showError("login.error.password.required");
            return;
        }

        UserService.LoginResponse response = userService.loginWithStatus(usernameText, passwordText);

        if (response.result() == LoginResult.SUCCESS && response.user().getRole() == Role.DEV) {
            User devUser = response.user();
            session.setPendingDevUser(devUser);
            closeLoginWindow();
            Platform.runLater(() -> totpPromptService.prompt(
                    "totp.title",
                    "totp.title",
                    "common.back",
                    () -> completeSuccessfulLogin(usernameText, devUser),
                    () -> {
                        session.consumePendingDevUser();
                        showLoginDialog();
                    }));
            return;
        }

        switch (response.result()) {
            case SUCCESS:
                completeSuccessfulLogin(usernameText, response.user());
                break;

            case ACCOUNT_DEACTIVATED:
                log.warn("Login attempt for deactivated account: '{}'", usernameText);
                showError("login.error.account.deactivated");
                break;

            case INVALID_CREDENTIALS:
            default:
                log.warn("Failed login attempt for username '{}'", usernameText);
                showError("login.error.invalid");
                break;
        }
    }

    /**
     * Completes post-authentication setup after password-only or OTP-verified
     * login.
     *
     * @param usernameText username entered by the user
     * @param user         authenticated user to place in the active session
     */
    private void completeSuccessfulLogin(String usernameText, User user) {
        if (!session.isDev()) {
            recentUsernameRepository.upsert(usernameText.trim());
        }
        log.info("User '{}' logged in successfully", usernameText);

        // Initialize session for the authenticated user.
        session.setUser(user);

        // Apply user-specific runtime settings.
        userSettingService.applyRuntimeSettings(user.getId());

        MainApp.showAdmin();
        closeLoginWindow();
    }

    /**
     * Closes only the login dialog while leaving the guest/admin primary window
     * alive.
     */
    private void closeLoginWindow() {
        if (btnLogin.getScene() == null || btnLogin.getScene().getWindow() == null) {
            return;
        }
        Stage stage = (Stage) btnLogin.getScene().getWindow();
        stage.close();
    }

    /**
     * Reopens the login dialog after an OTP cancellation without replacing the
     * guest screen.
     */
    private void showLoginDialog() {
        DialogHelper.Dialog<LoginController> dialog = DialogHelper.createDialog(
                ViewPaths.LOGIN,
                "BDMA");
        Stage stage = dialog.stage();
        stage.setResizable(false);
        stage.setWidth(480);
        stage.setHeight(420);
        stage.showAndWait();
    }

    private void showError(String key) {
        message.setText(I18n.get(key));
        message.setVisible(true);
    }

    private void hideError() {
        message.setVisible(false);
    }

    /**
     * Listens for theme change events to update password peek icon without full UI
     * reload.
     * Called by Spring's event system when theme changes.
     *
     * @param event the theme change event containing the new theme
     */
    @EventListener
    public void onThemeChanged(ThemeChangedEvent event) {
        // Skip if controller not yet initialized
        if (passwordVisible == null || passwordIconView == null) {
            return;
        }
        boolean isPasswordVisible = passwordVisible.isVisible();
        updatePasswordIcon(isPasswordVisible);
    }
}
