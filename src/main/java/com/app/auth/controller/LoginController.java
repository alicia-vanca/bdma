package com.app.auth.controller;

import com.app.MainApp;
import com.app.common.css.CssLoader;
import com.app.common.helper.SpringContextHolder;
import com.app.common.i18n.I18n;
import com.app.common.session.Session;
import com.app.common.ui.ViewLoader;
import com.app.common.ui.ViewPaths;
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

import java.util.List;

@Component
public class LoginController {

    private static final String THEME_BTN_ACTIVE = "active-button";

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    @FXML
    private TextField username;
    @FXML
    private PasswordField password;
    @FXML
    private Label message;
    @FXML
    private Button btnEnglish;
    @FXML
    private Button btnVietnamese;

    private final UserService userService;
    private final UpdateController updateController;

    public LoginController(UserService userService, UpdateController updateController) {
        this.userService = userService;
        this.updateController = updateController;
    }

    protected List<Button> getMenuButtons() {
        return List.of(btnEnglish, btnVietnamese);
    }

    @FXML
    public void initialize() {
        updateController.setOnStatusChange(null);
        updateController.checkOnStartup();

        hideError();

        String lang = I18n.getLocale().getLanguage();
        setActiveButton("vi".equals(lang) ? btnVietnamese : btnEnglish);

        username.textProperty().addListener((obs, oldVal, newVal) -> hideError());
        password.textProperty().addListener((obs, oldVal, newVal) -> hideError());

        username.setOnAction(e -> handleLogin());
        password.setOnAction(e -> handleLogin());
    }

    @FXML
    private void handleLogin() {
        String usernameText = username.getText();
        String passwordText = password.getText();

        // 🔥 validation
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
            MainApp.showAdmin();
        } else {
            log.warn("Failed login attempt for username '{}'", usernameText);
            showError("login.error.invalid");
        }
    }

    // ── helper ─────────────────────────────────────────────

    private void showError(String key) {
        message.setText(I18n.get(key));
        message.setVisible(true);
        message.setManaged(true);
    }

    private void hideError() {
        message.setVisible(false);
        message.setManaged(false);
    }

    @FXML
    private void switchToEnglish() {
        I18n.setLocale(java.util.Locale.forLanguageTag("en"));
        reloadUI();
        setActiveButton(btnEnglish);
    }

    @FXML
    private void switchToVietnamese() {
        I18n.setLocale(java.util.Locale.forLanguageTag("vi"));
        reloadUI();
        setActiveButton(btnVietnamese);
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

    protected void setActiveButton(Button active) {
        getMenuButtons().forEach(b -> b.getStyleClass().remove(THEME_BTN_ACTIVE));
        if (!active.getStyleClass().contains(THEME_BTN_ACTIVE)) {
            active.getStyleClass().add(THEME_BTN_ACTIVE);
        }
    }
}