package com.app.auth.controller;

import com.app.MainApp;
import com.app.common.css.CssLoader;
import com.app.common.helper.SpringContextHolder;
import com.app.common.i18n.I18n;
import com.app.common.session.Session;
import com.app.common.ui.SettingsPopupHelper;
import com.app.common.ui.ViewLoader;
import com.app.common.ui.ViewPaths;
import com.app.setting.service.UserSettingService;
import com.app.update.controller.UpdateController;
import com.app.user.model.User;
import com.app.user.service.UserService;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    private final UserService userService;
    private final UpdateController updateController;
    private final UserSettingService userSettingService;

    private SettingsPopupHelper settingsPopupHelper;

    public LoginController(UserService userService,
                           UpdateController updateController,
                           UserSettingService userSettingService) {
        this.userService = userService;
        this.updateController = updateController;
        this.userSettingService = userSettingService;
    }

    @FXML
    public void initialize() {
        updateController.setOnStatusChange(null);
        updateController.checkOnStartup();

        hideError();

        settingsPopupHelper = new SettingsPopupHelper(
                "login",
                btnSettings,
                null,
                this::reloadUI,
                updateController::onCheckUpdateManual,
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

        User user = userService.login(usernameText, passwordText);

        if (user != null) {
            log.info("User '{}' logged in successfully", usernameText);
            Session.setUser(user);
            userSettingService.applyRuntimeSettings(user.getId());
            MainApp.showAdmin();
        } else {
            log.warn("Failed login attempt for username '{}'", usernameText);
            showError("login.error.invalid");
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
            var result = viewLoader.loadWithController(ViewPaths.LOGIN, null);
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
