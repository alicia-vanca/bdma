package com.app.admin.controller;

import com.app.MainApp;
import com.app.common.i18n.I18n;
import com.app.common.session.Session;
import com.app.common.ui.*;
import com.app.setting.service.UserSettingService;
import com.app.update.controller.UpdateController;
import com.app.user.controller.UserFormController;
import com.app.user.controller.UserInfoController;
import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@SuppressWarnings("squid:S2209")
@Component
public class AdminLayoutController extends BaseLayoutController {

    private static final Logger log = LoggerFactory.getLogger(AdminLayoutController.class);
    private static final String MESSAGE_ERROR = "message-error";
    private static final String MESSAGE_SUCCESS = "message-success";

    private final UpdateController updateController;
    private final UserSettingService userSettingService;
    private final Session session;

    @FXML
    private StackPane contentArea;
    @FXML
    private Label labelGreeting;
    @FXML
    private Button btnSettings;
    @FXML
    private Button btnDashboard;
    @FXML
    private Button btnUser;
    @FXML
    private Button btnAppSetting;
    @FXML
    private Button btnStorageSetting;
    @FXML
    private Button btnDataBackupSetting;
    @FXML
    private Label noticeLabel;
    @FXML
    private VBox appSettingMenu;
    @FXML
    private StackPane root;

    private SettingsPopupHelper settingsPopupHelper;
    private final PauseTransition hideNoticeTransition = new PauseTransition(Duration.seconds(3));

    public AdminLayoutController(ViewLoader viewLoader, UpdateController updateController, UserSettingService userSettingService, Session session) {
        super(viewLoader);
        this.updateController = updateController;
        this.userSettingService = userSettingService;
        this.session = session;
    }

    @Override
    protected StackPane getContentArea() {
        return contentArea;
    }

    @Override
    protected List<Button> getMenuButtons() {
        // Dashboard is visible to all roles, so always include it in the active-state
        // list.
        return List.of(btnDashboard, btnUser, btnAppSetting);
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
        labelGreeting.setText(I18n.get("top.hello", session.getUser().getUsername()));

        settingsPopupHelper = new SettingsPopupHelper(
                "admin",
                btnSettings,
                userSettingService,
                this::reloadUI,
                this::onCheckUpdateManual,
                this::onUserInfo);
        settingsPopupHelper.initialize();

        appSettingMenu.minWidthProperty().bind(btnAppSetting.widthProperty());
        appSettingMenu.prefWidthProperty().bind(btnAppSetting.widthProperty());
        appSettingMenu.maxWidthProperty().bind(btnAppSetting.widthProperty());
        btnStorageSetting.minWidthProperty().bind(appSettingMenu.widthProperty());
        btnStorageSetting.prefWidthProperty().bind(appSettingMenu.widthProperty());
        btnStorageSetting.maxWidthProperty().bind(appSettingMenu.widthProperty());
        btnDataBackupSetting.minWidthProperty().bind(appSettingMenu.widthProperty());
        btnDataBackupSetting.prefWidthProperty().bind(appSettingMenu.widthProperty());
        btnDataBackupSetting.maxWidthProperty().bind(appSettingMenu.widthProperty());
        appSettingMenu.setManaged(false);
        appSettingMenu.setMouseTransparent(true);

        root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            Object target = e.getTarget();
            if (target instanceof Node node
                    && !isWithin(node, btnAppSetting)
                    && !isWithin(node, appSettingMenu)) {
                hideAppSettingMenu();
            }
        });

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
        if (session.isAdmin()) {
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
        dialog.controller().prepareForAccount(session.getUser());
        dialog.controller().setOnSuccess(() -> showNoticeSuccess(I18n.get("user.account.password.updated.success")));
        dialog.controller().setOnNoChange(() -> showNoticeSuccess(I18n.get("user.update.nochange")));
        dialog.stage().setResizable(false);
        dialog.stage().showAndWait();
    }

    @FXML
    public void logout() {
        session.clear();
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

    @FXML
    public void toggleAppSettingMenu(ActionEvent event) {
        toggleAppSettingMenu();
    }

    private void toggleAppSettingMenu() {
        boolean isVisible = appSettingMenu.isVisible();
        if (!isVisible) {
            appSettingMenu.applyCss();
            appSettingMenu.autosize();
            positionAppSettingMenu();
        }

        appSettingMenu.setVisible(!isVisible);
        appSettingMenu.setMouseTransparent(isVisible);
        if (!isVisible) {
            appSettingMenu.toFront();
        }
    }

    @FXML
    public void goStorageSetting(ActionEvent event) {
        if (!session.isAdmin())
            return;
        hideAppSettingMenu();
        setContent(loadView(ViewPaths.STORAGE_SETTING));
        setActiveButton(getMenuButtons(), btnAppSetting);
    }

    @FXML
    public void goDataBackupSetting(ActionEvent event) {
        if (!session.isAdmin())
            return;
        hideAppSettingMenu();
        setContent(loadView(ViewPaths.DATA_BACKUP_SETTING));
        setActiveButton(getMenuButtons(), btnAppSetting);
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
        controller.setShowBack(session.isAdmin());
        controller.setUser(session.getUser());

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
        boolean isAdmin = session.isAdmin();
        btnAppSetting.setVisible(isAdmin);
        btnAppSetting.setManaged(isAdmin);
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
            case ViewPaths.STORAGE_SETTING, ViewPaths.DATA_BACKUP_SETTING -> btnAppSetting;
            default -> null;
        };
    }

    private void hideAppSettingMenu() {
        appSettingMenu.setVisible(false);
        appSettingMenu.setMouseTransparent(true);
    }

    private void positionAppSettingMenu() {
        Point2D buttonBottomLeft = btnAppSetting.localToScene(0, btnAppSetting.getHeight());
        Point2D rootPoint = root.sceneToLocal(buttonBottomLeft);
        appSettingMenu.relocate(rootPoint.getX(), rootPoint.getY() + 4);
    }

    private boolean isWithin(Node node, Node container) {
        Node current = node;
        while (current != null) {
            if (current == container) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
