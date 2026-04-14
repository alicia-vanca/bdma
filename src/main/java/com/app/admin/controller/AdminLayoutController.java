package com.app.admin.controller;

import com.app.MainApp;
import com.app.common.i18n.I18n;
import com.app.common.session.Session;
import com.app.common.ui.*;
import com.app.device.model.DeviceSummary;
import com.app.device.model.DeviceValidationResult;
import com.app.device.model.ValidatedDevice;
import com.app.device.service.DeviceValidationService;
import com.app.setting.service.UserSettingService;
import com.app.sync.SyncRunner;
import com.app.sync.model.SyncContext;
import com.app.sync.queue.DeviceQueue;
import com.app.sync.tracker.DeviceTracker;
import com.app.sync.tracker.SyncProgressTracker;
import com.app.update.controller.UpdateController;
import com.app.user.controller.UserFormController;
import com.app.user.controller.UserInfoController;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("squid:S2209")
@Component
public class AdminLayoutController extends BaseLayoutController {

    private static final Logger log = LoggerFactory.getLogger(AdminLayoutController.class);
    private final UpdateController updateController;
    private final UserSettingService userSettingService;
    private final Session session;
    private final DeviceValidationService deviceValidationService;
    private final DeviceQueue deviceQueue;
    private final DeviceTracker deviceTracker;
    private final SyncRunner syncRunner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
    private VBox noticeContainer;
    @FXML
    private VBox appSettingMenu;
    @FXML
    private StackPane root;

    private SettingsPopupHelper settingsPopupHelper;
    private NoticeStackHelper noticeHelper;
    private DashboardController currentDashboardController;
    private final SyncProgressTracker progressTracker;

    public AdminLayoutController(ViewLoader viewLoader,
                                 UpdateController updateController,
                                 UserSettingService userSettingService,
                                 Session session,
                                 DeviceValidationService deviceValidationService,
                                 DeviceQueue deviceQueue,
                                 DeviceTracker deviceTracker,
                                 SyncRunner syncRunner,
                                 SyncProgressTracker progressTracker) {
        super(viewLoader);
        this.updateController = updateController;
        this.userSettingService = userSettingService;
        this.session = session;
        this.deviceValidationService = deviceValidationService;
        this.deviceQueue = deviceQueue;
        this.deviceTracker = deviceTracker;
        this.syncRunner = syncRunner;
        this.progressTracker = progressTracker;
    }

    @Override
    protected StackPane getContentArea() {
        return contentArea;
    }

    @Override
    protected List<Button> getMenuButtons() {
        return List.of(btnDashboard, btnUser, btnAppSetting);
    }

    @FXML
    public void initialize() {
        updateController.setOnCheckStart(() -> btnSettings.setDisable(true));
        updateController.setOnCheckEnd(() -> btnSettings.setDisable(false));
        updateController.setOnStatusChange(msg -> log.info("Update status: {}", msg));

        labelGreeting.setText(I18n.get("top.hello", session.getUser().getUsername()));

        settingsPopupHelper = new SettingsPopupHelper(
                "admin",
                btnSettings,
                userSettingService,
                this::reloadUI,
                this::onCheckUpdateManual,
                this::onUserInfo);
        settingsPopupHelper.initialize();
        noticeHelper = new NoticeStackHelper(noticeContainer);

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

        if (deviceTracker.getOnDeviceChanged() == null) {
            deviceTracker.setOnDeviceChanged(this::refreshDashboardIfActive);
            deviceTracker.setOnNewSerial(serial -> {
                try {
                    DeviceValidationResult result = deviceValidationService.validateConnectedDevice(serial);
                    if (result.isValid()) {
                        Platform.runLater(() -> handleValidatedResult(result));
                    }
                } catch (Exception e) {
                    log.warn("Validation failed for serial {}: {}", serial, e.getMessage());
                }
            });
            progressTracker.setOnProgressChanged(this::refreshDashboardIfActive);
            triggerInitialDeviceCheck();
        } else {
            // Reload ngôn ngữ: chỉ update lại ref dashboard controller mới
            deviceTracker.setOnDeviceChanged(this::refreshDashboardIfActive);
            progressTracker.setOnProgressChanged(this::refreshDashboardIfActive);
        }

        configureTabsByRole();
        openDefaultTab();
    }

    @FXML
    public void goDashboard() {
        var result = loadViewWithController(ViewPaths.ADMIN_DASHBOARD, DashboardController.class);
        if (result != null) {
            currentDashboardController = result.controller();
            currentDashboardController.setOnValidate(this::handleUnvalidatedDevice);
            currentDashboardController.setOnSync(this::handleSync);
            setContent(result.node());
        }
        setActiveButton(getMenuButtons(), btnDashboard);
    }

    @FXML
    private void goUser() {
        setActiveButton(getMenuButtons(), btnUser);
        if (session.isAdmin()) {
            setContent(loadView(ViewPaths.USER_LIST));
        } else {
            openMyProfile();
        }
    }

    @FXML
    private void onUserInfo() {
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
        deviceTracker.setOnDeviceChanged(null);
        deviceTracker.setOnNewSerial(null);
        deviceTracker.shutdown();
        deviceQueue.clearAll();
        syncRunner.stop();
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
        if (!session.isAdmin()) return;
        hideAppSettingMenu();
        setContent(loadView(ViewPaths.STORAGE_SETTING));
        setActiveButton(getMenuButtons(), btnAppSetting);
    }

    @FXML
    public void goDataBackupSetting(ActionEvent event) {
        if (!session.isAdmin()) return;
        hideAppSettingMenu();
        setContent(loadView(ViewPaths.DATA_BACKUP_SETTING));
        setActiveButton(getMenuButtons(), btnAppSetting);
    }

    private void openMyProfile() {
        var result = loadViewWithController(ViewPaths.USER_INFO, UserInfoController.class);
        if (result == null) {
            log.error("Failed to load user-info view");
            return;
        }
        UserInfoController controller = result.controller();
        controller.setShowBack(session.isAdmin());
        controller.setUser(session.getUser());
        setContent(result.node());
    }

    @Override
    public void showNoticeSuccess(String text) {
        noticeHelper.showSuccess(text);
    }

    @Override
    public void showNoticeError(String text) {
        noticeHelper.showError(text);
    }

    private void configureTabsByRole() {
        boolean isAdmin = session.isAdmin();
        btnAppSetting.setVisible(isAdmin);
        btnAppSetting.setManaged(isAdmin);
    }

    private void handleValidatedResult(DeviceValidationResult result) {
        if (result.isAlreadySaved()) {
            ValidatedDevice existing = result.getDevice();
            if (existing != null) {
                deviceValidationService.saveValidatedDevice(
                        existing.getDeviceName(),
                        result.getHardwareId(),
                        result.getMatchedWhitelistId());
            }
            showNoticeSuccess(I18n.get("device.connected.saved", resolveValidatedDeviceName(result)));
            return;
        }

        if (session.isAdmin()) {
            showSaveDeviceConfirmation(result);
        }
    }

    private void showSaveDeviceConfirmation(DeviceValidationResult result) {
        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("device.save.title"),
                I18n.get("device.save.header"),
                I18n.get("device.save.content",
                        resolveValidatedDeviceName(result),
                        result.getMatchedModelName()));

        ButtonType yesButton = new ButtonType(I18n.get("common.yes"), ButtonBar.ButtonData.YES);
        ButtonType noButton = new ButtonType(I18n.get("common.no"), ButtonBar.ButtonData.NO);
        AlertHelper.setButtons(confirm, yesButton, noButton);

        Optional<ButtonType> chosen = confirm.showAndWait();
        if (chosen.isEmpty() || chosen.get() != yesButton) {
            return;
        }

        ValidatedDevice saved = deviceValidationService.saveValidatedDevice(
                result.getAccountUserId(),
                result.getHardwareId(),
                result.getMatchedWhitelistId());
        deviceQueue.add(result.getSerial(), new SyncContext(session.getUser().getUsername(), session.isAdmin()));
        showNoticeSuccess(I18n.get("device.saved.success", saved.getDeviceName()));
        refreshDashboardIfActive(); // thêm dòng này
    }

    private String resolveValidatedDeviceName(DeviceValidationResult result) {
        if (result.getDevice() != null && result.getDevice().getDeviceName() != null
                && !result.getDevice().getDeviceName().isBlank()) {
            return result.getDevice().getDeviceName();
        }
        if (result.getAccountUserId() != null && !result.getAccountUserId().isBlank()) {
            return result.getAccountUserId();
        }
        if (result.getHardwareId() != null && !result.getHardwareId().isBlank()) {
            return result.getHardwareId();
        }
        return result.getSerial();
    }

    private void openDefaultTab() {
        goDashboard();
    }

    @Override
    protected Button getButtonForModule(String fxml) {
        return switch (fxml) {
            case ViewPaths.USER_LIST, ViewPaths.USER_INFO -> btnUser;
            case ViewPaths.STORAGE_SETTING, ViewPaths.DATA_BACKUP_SETTING -> btnAppSetting;
            default -> null;
        };
    }

    private void refreshDashboardIfActive() {
        if (currentDashboardController != null) {
            currentDashboardController.refresh();
        }
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
            if (current == container) return true;
            current = current.getParent();
        }
        return false;
    }

    private void triggerInitialDeviceCheck() {
        executor.submit(() -> {
            try {
                List<String> serials = deviceValidationService.listConnectedSerials();
                serials.forEach(this::processDeviceSerialSafely);
            } catch (Exception e) {
                log.warn("Initial device check error: {}", e.getMessage());
            }
        });
    }

    private void processDeviceSerialSafely(String serial) {
        try {
            DeviceValidationResult result = deviceValidationService.validateConnectedDevice(serial);
            if (result.isValid()) {
                Platform.runLater(() -> handleValidatedResult(result));
            }
        } catch (Exception e) {
            log.warn("Initial check failed for serial {}: {}", serial, e.getMessage());
        }
    }

    private void handleUnvalidatedDevice(DeviceSummary summary) {
        try {
            DeviceValidationResult result = deviceValidationService.validateConnectedDevice(summary.getSerial());
            if (!result.isValid()) {
                showNoticeError(I18n.get("device.validation.failed"));
                return;
            }
            if (result.isAlreadySaved()) {
                showNoticeSuccess(I18n.get("device.connected.saved", resolveValidatedDeviceName(result)));
                return;
            }
            showSaveDeviceConfirmation(result);
        } catch (Exception e) {
            log.warn("Failed to validate device {}: {}", summary.getSerial(), e.getMessage());
            showNoticeError(I18n.get("device.validation.failed"));
        }
    }

    private void handleSync(DeviceSummary summary) {
        if (!summary.isConnected()) {
            return;
        }
        deviceQueue.add(summary.getSerial(), new SyncContext(session.getUser().getUsername(), session.isAdmin()));
        showNoticeSuccess(I18n.get("device.sync.re-queued", summary.getDisplayName()));
    }

}
