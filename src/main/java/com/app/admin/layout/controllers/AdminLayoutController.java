package com.app.admin.layout.controllers;

import com.app.MainApp;
import com.app.admin.usermanagement.controllers.UserEditFormController;
import com.app.common.definitions.ViewPaths;
import com.app.common.dtos.DeviceSummary;
import com.app.common.dtos.DeviceValidationResult;
import com.app.common.dtos.SyncContext;
import com.app.common.helpers.AlertHelper;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.ViewLoader;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.modules.datasync.DataSyncRunner;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.modules.settingspopup.helpers.SettingsPopupHelper;
import com.app.common.services.*;
import com.app.user.userdetail.controllers.UserInfoController;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@SuppressWarnings({"squid:S2209", "unused"})
@Component
public class AdminLayoutController extends BaseLayoutController {

    private static final Logger log = LoggerFactory.getLogger(AdminLayoutController.class);

    private final AppUpdateController appUpdateController;
    private final UserSettingService userSettingService;
    private final Session session;
    private final DeviceValidationService deviceValidationService;
    private final AppNoticeService appNoticeService;
    private final DeviceSyncQueue deviceSyncQueue;
    private final DeviceTracker deviceTracker;
    private final DataSyncRunner syncRunner;
    private final SyncProgressTracker syncProgressTracker;

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
    private VBox noticeContainer;

    private SettingsPopupHelper settingsPopupHelper;
    private final Set<String> connectedSerialsSnapshot = new HashSet<>();
    private ScheduledExecutorService deviceWatchExecutor;
    private volatile boolean adbMissingLogged;
    private DashboardController currentDashboardController;

    public AdminLayoutController(ViewLoader viewLoader,
                                 AppUpdateController appUpdateController,
                                 UserSettingService userSettingService,
                                 Session session,
                                 DeviceValidationService deviceValidationService,
                                 AppNoticeService appNoticeService,
                                 DeviceSyncQueue deviceSyncQueue,
                                 SyncProgressTracker syncProgressTracker,
                                 DeviceTracker deviceTracker,
                                 DataSyncRunner syncRunner) {
        super(viewLoader);
        this.appUpdateController = appUpdateController;
        this.userSettingService = userSettingService;
        this.session = session;
        this.deviceValidationService = deviceValidationService;
        this.appNoticeService = appNoticeService;
        this.deviceSyncQueue = deviceSyncQueue;
        this.deviceTracker = deviceTracker;
        this.syncRunner = syncRunner;
        this.syncProgressTracker = syncProgressTracker;
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
        appUpdateController.setOnCheckStart(() -> btnSettings.setDisable(true));
        appUpdateController.setOnCheckEnd(() -> btnSettings.setDisable(false));
        appUpdateController.setOnStatusChange(msg -> log.info("Update status: {}", msg));

        // Set greeting early because this template is shared by both admin and
        // non-admin users.
        labelGreeting.setText(I18n.get("top.hello", session.getUser().getUsername()));

        appNoticeService.bindNoticeContainer(noticeContainer);

        if (!session.isAdmin()) {
            settingsPopupHelper = new SettingsPopupHelper(
                    "user-layout",
                    btnSettings,
                    SettingsPopupHelper.PopupAnchorY.BOTTOM,
                    userSettingService,
                    this::reloadUI,
                    appUpdateController::onCheckUpdateManual,
                    this::onUserInfo);
            settingsPopupHelper.initialize();
        }

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
            syncProgressTracker.setOnProgressChanged(this::refreshDashboardIfActive);
            triggerInitialDeviceCheck();
        } else {
            // Reload ngôn ngữ: chỉ update lại ref dashboard controller mới
            deviceTracker.setOnDeviceChanged(this::refreshDashboardIfActive);
            syncProgressTracker.setOnProgressChanged(this::refreshDashboardIfActive);
        }

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
        DialogHelper.Dialog<UserEditFormController> dialog = DialogHelper.createDialog(
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
        deviceSyncQueue.clearAll();
        syncProgressTracker.clearAll();
        syncRunner.stop();
        session.clear();
        MainApp.showLogin();
    }

    @FXML
    private void openSettingsPopup() {
        if (!session.isAdmin() && settingsPopupHelper != null) {
            settingsPopupHelper.togglePopup();
            return;
        }
        Stage stage = DialogHelper.createDialogStage(ViewPaths.ADMIN_SETTINGS_DIALOG, I18n.get("settings.title"));
        stage.setResizable(false);
        stage.showAndWait();
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
        appNoticeService.showSuccess(text);
    }

    @Override
    public void showNoticeError(String text) {
        appNoticeService.showError(text);
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
            showSyncConfirmation(result.getSerial(), resolveValidatedDeviceName(result));
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
                I18n.get(
                        "device.save.content",
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
        deviceSyncQueue.add(result.getSerial(), new SyncContext(session.getUser().getUsername(), session.isAdmin()));
        showNoticeSuccess(I18n.get("device.saved.success", saved.getDeviceName()));
        refreshDashboardIfActive();
    }

    // Prefer the persisted device name when it exists, otherwise fall back to the
    // validated account/device identifiers the UI already shows and saves.
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
        // Map each module's FXML path to its sidebar nav button so reloadUI() can
        // restore the correct active-button highlight after a language change reload.
        return switch (fxml) {
            case ViewPaths.USER_LIST, ViewPaths.USER_INFO -> btnUser;
            default -> null;
        };
    }

    private void refreshDashboardIfActive() {
        if (currentDashboardController != null) {
            currentDashboardController.refresh();
        }
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
        deviceSyncQueue.add(summary.getSerial(), new SyncContext(session.getUser().getUsername(), session.isAdmin()));
        showNoticeSuccess(I18n.get("device.sync.re-queued", summary.getDisplayName()));
    }

    private void showSyncConfirmation(String serial, String deviceName) {
        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("device.sync.title"),
                I18n.get("device.sync.header"),
                I18n.get("device.sync.content", deviceName));

        ButtonType yesButton = new ButtonType(I18n.get("common.yes"), ButtonBar.ButtonData.YES);
        ButtonType noButton = new ButtonType(I18n.get("common.no"), ButtonBar.ButtonData.NO);
        AlertHelper.setButtons(confirm, yesButton, noButton);

        Optional<ButtonType> chosen = confirm.showAndWait();
        if (chosen.isEmpty() || chosen.get() != yesButton) {
            return;
        }

        deviceSyncQueue.add(serial, new SyncContext(session.getUser().getUsername(), session.isAdmin()));

        showNoticeSuccess(I18n.get("device.sync.re-queued", deviceName));
        refreshDashboardIfActive();
    }
}
