package com.app.auth.login.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.MainApp;
import com.app.auth.login.services.LoginService;
import com.app.common.definitions.ViewPaths;
import com.app.common.helpers.CssLoader;
import com.app.common.helpers.SpringContextHolder;
import com.app.common.helpers.ViewLoader;
import com.app.common.models.User;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.modules.datasync.DataSyncRunner;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.modules.settingspopup.helpers.SettingsPopupHelper;
import com.app.common.services.UserService;
import com.app.common.services.UserSettingService;

import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

@Component
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    @FXML
    private TextField username;
    @FXML
    private PasswordField password;
    @FXML
    private Label message;
    @FXML
    private Button btnSettings;

    private final Session session;
    private final UserService userService;
    private final AppUpdateController appUpdateController;
    private final UserSettingService userSettingService;
    private final LoginService loginService; // Triggers background sync after login

    private SettingsPopupHelper settingsPopupHelper;

    public LoginController(Session session, UserService userService, AppUpdateController appUpdateController,
            UserSettingService userSettingService, LoginService loginService) {
        this.session = session;
        this.userService = userService;
        this.appUpdateController = appUpdateController;
        this.userSettingService = userSettingService;
        this.loginService = loginService;
    }

    @FXML
    public void initialize() {
        appUpdateController.setOnStatusChange(null);
        appUpdateController.checkOnStartup();

        hideError();

        settingsPopupHelper = new SettingsPopupHelper(
                "login",
                btnSettings,
                SettingsPopupHelper.PopupAnchorY.TOP,
                null,
                this::reloadUI,
                appUpdateController::onCheckUpdateManual,
                null);
        settingsPopupHelper.initialize();

        username.textProperty().addListener((obs, oldVal, newVal) -> hideError());
        password.textProperty().addListener((obs, oldVal, newVal) -> hideError());

        username.setOnAction(e -> handleLogin());
        password.setOnAction(e -> handleLogin());
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

        switch (response.result()) {
            case SUCCESS:
                User user = response.user();
                log.info("User '{}' logged in successfully", usernameText);

                // Initialize session for the authenticated user
                session.setUser(user);

                // Apply user-specific runtime settings
                userSettingService.applyRuntimeSettings(user.getId());

                // Trigger background sync without blocking UI
                loginService.onLoginSuccess();

                // Start a fresh device-tracking session after successful login.
                DataSyncRunner dataSyncRunner = SpringContextHolder.getBean(DataSyncRunner.class);
                dataSyncRunner.startDeviceTracker();

                // Navigate to main screen
                MainApp.showAdmin();
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

    private void showError(String key) {
        message.setText(I18n.get(key));
        message.setVisible(true);
    }

    private void hideError() {
        message.setVisible(false);
    }

    @FXML
    private void openSettingsPopup() {
        settingsPopupHelper.togglePopup();
    }

    private void reloadUI() {
        try {
            ViewLoader viewLoader = SpringContextHolder.getBean(ViewLoader.class);
            var result = viewLoader.loadView(ViewPaths.LOGIN);
            if (result != null) {
                Parent root = (Parent) result.node();
                MainApp.getScene().setRoot(root);
                CssLoader.applyLogin(MainApp.getScene());
            }
        } catch (Exception e) {
            log.error("Failed to reload login UI", e);
        }
    }
}
