package com.app.admin.controller;

import com.app.MainApp;
import com.app.common.i18n.I18n;
import com.app.common.session.Session;
import com.app.common.theme.ThemeManager;
import com.app.common.ui.BaseLayoutController;
import com.app.common.ui.ViewLoader;
import com.app.common.ui.ViewPaths;
import com.app.update.controller.UpdateController;
import com.app.user.controller.UserInfoController;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AdminLayoutController extends BaseLayoutController {

    private static final String THEME_BTN_ACTIVE = "theme-btn-active";

    private static final Logger log = LoggerFactory.getLogger(AdminLayoutController.class);

    private final UpdateController updateController;

    @FXML
    private StackPane contentArea;
    @FXML
    private Button btnCheckUpdate;
    @FXML
    private Label labelUpdateStatus;
    @FXML
    private MenuButton userMenu;
    @FXML
    private Button btnDashboard;
    @FXML
    private Button btnUser;
    @FXML
    private Button btnEnglish;
    @FXML
    private Button btnVietnamese;
    @FXML
    private Button btnLightTheme;
    @FXML
    private Button btnDarkTheme;

    public AdminLayoutController(ViewLoader viewLoader, UpdateController updateController) {
        super(viewLoader);
        this.updateController = updateController;
    }

    // ── BaseLayoutController impl ───────────────────────────────────────────

    @Override
    protected StackPane getContentArea() {
        return contentArea;
    }

    protected List<Button> getMenuButtons() {
        // Dashboard is visible to all roles, so always include it in the active-state
        // list.
        return List.of(btnDashboard, btnUser);
    }

    protected List<Button> getLangButtons() {
        return List.of(btnEnglish, btnVietnamese);
    }

    // ── Init ────────────────────────────────────────────────────────────────

    @FXML
    public void initialize() {
        // Wire update callbacks once so header controls always reflect updater state.
        updateController.setOnCheckStart(() -> btnCheckUpdate.setDisable(true));
        updateController.setOnCheckEnd(() -> btnCheckUpdate.setDisable(false));
        updateController.setOnStatusChange(msg -> labelUpdateStatus.setText(msg));

        // Set greeting early because this template is shared by both admin and
        // non-admin users.
        userMenu.setText(I18n.get("top.hello", Session.getUser().getUsername()));

        // Process role-specific tab visibility before choosing default content.
        configureTabsByRole();
        openDefaultTab();

        // Preserve active language marker after UI init/reload.
        String lang = I18n.getLocale().getLanguage();
        setActiveButton(getLangButtons(), "vi".equals(lang) ? btnVietnamese : btnEnglish);

        // Keep theme toggle state consistent with persisted theme.
        updateThemeButtons();
    }

    // ── Menu handlers ───────────────────────────────────────────────────────

    @FXML
    public void goDashboard() {
        // Dashboard is accessible to all roles — just load it directly.
        setContent(loadView(ViewPaths.ADMIN_DASHBOARD));
        setActiveButton(getMenuButtons(), btnDashboard);
    }

    @FXML
    private void goUser() {
        // Reuse the same tab entry point and only change loaded content by role.
        setActiveButton(getMenuButtons(), btnUser);
        if (Session.isAdmin()) {
            setContent(loadView(ViewPaths.USER_LIST));
        } else {
            openMyProfile();
        }
    }

    @FXML
    private void onUserInfo() {
        // Route "Information" menu to the same user/profile tab behavior for
        // consistency.
        setActiveButton(getMenuButtons(), btnUser);
        openMyProfile();
    }

    @FXML
    public void logout() {
        Session.clear();
        MainApp.showLogin();
    }

    @FXML
    public void onCheckUpdateManual() {
        updateController.onCheckUpdateManual();
    }

    private void openMyProfile() {

        // Request typed controller result to keep navigation casting checked and
        // explicit.
        var result = loadViewWithController(ViewPaths.USER_INFO, UserInfoController.class);
        if (result == null) {
            log.error("Failed to load user-info view");
            return;
        }

        UserInfoController controller = result.controller();
        // Hide Back only for regular users — admins navigate back via the tab row.
        controller.setShowBack(Session.isAdmin());
        controller.setUser(Session.getUser());

        setContent(result.node());
    }

    // Both tabs are visible to all roles; goUser() loads different content
    // depending on role.
    private void configureTabsByRole() {
        // No tabs are hidden — content differs by role, not visibility.
    }

    // All roles land on the dashboard by default; delegate to goDashboard to avoid
    // duplicating logic.
    private void openDefaultTab() {
        goDashboard();
    }

    @FXML
    private void switchToEnglish() {
        // Reload layout after language switch so shared header tabs and body text
        // update together.
        I18n.setLocale(java.util.Locale.forLanguageTag("en"));
        reloadUI();
        setActiveButton(getLangButtons(), btnEnglish);
    }

    @FXML
    private void switchToVietnamese() {
        // Reload layout after language switch so shared header tabs and body text
        // update together.
        I18n.setLocale(java.util.Locale.forLanguageTag("vi"));
        reloadUI();
        setActiveButton(getLangButtons(), btnVietnamese);
    }

    @FXML
    private void switchToLightTheme() {
        // Apply theme immediately to the current shared shell and active body module.
        ThemeManager.setTheme(ThemeManager.THEME_LIGHT);
        ThemeManager.apply(MainApp.getScene());
        updateThemeButtons();
    }

    @FXML
    private void switchToDarkTheme() {
        // Apply theme immediately to the current shared shell and active body module.
        ThemeManager.setTheme(ThemeManager.THEME_DARK);
        ThemeManager.apply(MainApp.getScene());
        updateThemeButtons();
    }

    private void updateThemeButtons() {
        // Only one theme toggle should be active so current mode is obvious to users.
        boolean isDark = ThemeManager.THEME_DARK.equals(ThemeManager.getTheme());
        btnDarkTheme.getStyleClass().removeAll(THEME_BTN_ACTIVE);
        btnLightTheme.getStyleClass().removeAll(THEME_BTN_ACTIVE);
        if (isDark) {
            btnDarkTheme.getStyleClass().add(THEME_BTN_ACTIVE);
        } else {
            btnLightTheme.getStyleClass().add(THEME_BTN_ACTIVE);
        }
    }
}