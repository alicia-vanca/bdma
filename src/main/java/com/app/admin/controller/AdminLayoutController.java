package com.app.admin.controller;

import com.app.MainApp;
import com.app.common.i18n.I18n;
import com.app.common.session.Session;
import com.app.common.ui.BaseLayoutController;
import com.app.common.ui.DialogHelper;
import com.app.common.ui.SettingsPopupHelper;
import com.app.common.ui.ViewLoader;
import com.app.common.ui.ViewPaths;
import com.app.update.controller.UpdateController;
import com.app.user.controller.UserFormController;
import com.app.user.controller.UserInfoController;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AdminLayoutController extends BaseLayoutController {

    private static final Logger log = LoggerFactory.getLogger(AdminLayoutController.class);
    private static final String MESSAGE_ERROR = "message-error";
    private static final String MESSAGE_SUCCESS = "message-success";

    private final UpdateController updateController;

    @FXML
    private StackPane contentArea;
    @FXML
    private Label labelGreeting;
    @FXML
    private Button btnSettings;
    @FXML
    private Button btnLogout;
    @FXML
    private Button btnDashboard;
    @FXML
    private Button btnUser;
    @FXML
    private Label noticeLabel;

    private SettingsPopupHelper settingsPopupHelper;
    private final PauseTransition hideNoticeTransition = new PauseTransition(Duration.seconds(3));

    public AdminLayoutController(ViewLoader viewLoader, UpdateController updateController) {
        super(viewLoader);
        this.updateController = updateController;
    }

    @Override
    protected StackPane getContentArea() {
        return contentArea;
    }

    @Override
    protected List<Button> getMenuButtons() {
        // Dashboard is visible to all roles, so always include it in the active-state
        // list.
        return List.of(btnDashboard, btnUser);
    }

    @FXML
    public void initialize() {
        // Keep header buttons responsive while update checks are running.
        updateController.setOnCheckStart(() -> btnSettings.setDisable(true));
        updateController.setOnCheckEnd(() -> btnSettings.setDisable(false));
        updateController.setOnStatusChange(msg -> log.info("Update status: {}", msg));

        noticeLabel.setVisible(false);
        hideNoticeTransition.setOnFinished(e -> noticeLabel.setVisible(false));

        // Set greeting early because this template is shared by both admin and
        // non-admin users.
        labelGreeting.setText(I18n.get("top.hello", Session.getUser().getUsername()));

        settingsPopupHelper = new SettingsPopupHelper(
                "admin",
                btnSettings,
                this::reloadUI,
                this::onCheckUpdateManual,
                this::onUserInfo);
        settingsPopupHelper.initialize();

        configureTabsByRole();
        openDefaultTab();
    }

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
        // Open account settings as a dialog from the header action so users can
        // inspect identity info and change password without leaving the current page.
        DialogHelper.DialogResult<UserFormController> dialog = DialogHelper.openWithController(
                ViewPaths.USER_ACCOUNT_DIALOG,
                I18n.get("user.account.title"));
        dialog.controller().prepareForAccount(Session.getUser());
        dialog.controller().setOnSuccess(() -> showNoticeSuccess(I18n.get("user.account.password.updated.success")));
        dialog.controller().setOnNoChange(() -> showNoticeSuccess(I18n.get("user.update.nochange")));
        dialog.stage().setResizable(false);
        dialog.stage().showAndWait();
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

    @FXML
    private void openSettingsPopup() {
        settingsPopupHelper.togglePopup();
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

    @Override
    public void showNoticeSuccess(String text) {
        noticeLabel.setText(text);
        noticeLabel.getStyleClass().removeAll(MESSAGE_ERROR, MESSAGE_SUCCESS);
        noticeLabel.getStyleClass().add(MESSAGE_SUCCESS);
        noticeLabel.setVisible(true);
        noticeLabel.toFront();
        hideNoticeTransition.stop();
        hideNoticeTransition.playFromStart();
    }

    @Override
    public void showNoticeError(String text) {
        noticeLabel.setText(text);
        noticeLabel.getStyleClass().removeAll(MESSAGE_ERROR, MESSAGE_SUCCESS);
        noticeLabel.getStyleClass().add(MESSAGE_ERROR);
        noticeLabel.setVisible(true);
        noticeLabel.toFront();
        hideNoticeTransition.stop();
        hideNoticeTransition.playFromStart();
    }

    private void configureTabsByRole() {
        // No tabs are hidden — content differs by role, not visibility.
    }

    private void openDefaultTab() {
        goDashboard();
    }

    @Override
    protected Button getButtonForModule(String fxml) {
        // Map each module's FXML path to its sidebar nav button so reloadUI() can
        // restore the correct active-button highlight after a language change reload.
        return switch (fxml) {
            case ViewPaths.USER_LIST, ViewPaths.USER_INFO -> btnUser;
            default -> null;
        };
    }
}
