package com.app.admin.layout.controllers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.MainApp;
import com.app.admin.settingsdialog.controllers.AdminSettingsDialogController;
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.admin.usermanagement.controllers.UserEditFormController;
import com.app.common.definitions.ViewPaths;
import com.app.common.dtos.DeviceEvent;
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
import com.app.common.modules.datasync.events.DeviceSyncCompletedEvent;
import com.app.common.modules.databackup.events.FileBackupCompletedEvent;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.modules.settingspopup.helpers.SettingsPopupHelper;
import com.app.common.services.AppNoticeService;
import com.app.common.services.DeviceTracker;
import com.app.common.services.DeviceValidationService;
import com.app.common.services.SyncProgressTracker;
import com.app.common.services.UserSettingService;
import com.app.user.userdetail.controllers.UserInfoController;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

@SuppressWarnings({ "squid:S2209", "unused" })
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
    private final AdminSettingsDialogService adminSettingsService;

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
    private DashboardController currentDashboardController;
    private final Map<String, Alert> activeAlertsByHardwareId = new HashMap<>();
    // Stored so the same reference can be passed to removeListener on logout.
    private java.util.function.Consumer<DeviceEvent> deviceEventListener;

    public AdminLayoutController(ViewLoader viewLoader,
            AppUpdateController appUpdateController,
            UserSettingService userSettingService,
            Session session,
            DeviceValidationService deviceValidationService,
            AppNoticeService appNoticeService,
            DeviceSyncQueue deviceSyncQueue,
            SyncProgressTracker syncProgressTracker,
            DeviceTracker deviceTracker,
            DataSyncRunner syncRunner,
            AdminSettingsDialogService adminSettingsService) {
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
        this.adminSettingsService = adminSettingsService;
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

        // Register for device events; also fires CONNECTED for devices already
        // connected so login-time devices are not missed.
        if (deviceEventListener == null) {
            deviceEventListener = this::onDeviceEvent;
            deviceTracker.addListener(deviceEventListener);
        }

        syncProgressTracker.setOnProgressChanged(this::refreshDashboardIfActive);

        openDefaultTab();
    }

    @FXML
    public void goDashboard() {
        var result = loadViewWithController(ViewPaths.ADMIN_DASHBOARD, DashboardController.class);
        if (result != null) {
            currentDashboardController = result.controller();
            currentDashboardController.setOnRequestValidate(this::handleRequestValidate);
            currentDashboardController.setOnRequestSync(this::handleRequestSync);
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
        log.info("User {} is logging out", session.getUser().getUsername());
        if (currentDashboardController != null) {
            currentDashboardController.resetState();
        }
        if (deviceEventListener != null) {
            deviceTracker.removeListener(deviceEventListener);
            deviceEventListener = null;
        }
        deviceSyncQueue.clearAll();
        syncProgressTracker.clearAll();
        syncRunner.shutdown();
        session.clear();
        MainApp.showLogin();
    }

    @FXML
    private void openSettingsPopup() {
        if (!session.isAdmin() && settingsPopupHelper != null) {
            settingsPopupHelper.togglePopup();
            return;
        }
        DialogHelper.Dialog<AdminSettingsDialogController> dialog = DialogHelper.createDialog(
                ViewPaths.ADMIN_SETTINGS_DIALOG,
                I18n.get("settings.title"));
        dialog.controller().setOnLanguageChangedAction(this::reloadUI);
        Stage stage = dialog.stage();
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

    // Route device events: validate connections and clean up queue on disconnect.
    private void onDeviceEvent(DeviceEvent event) {
        if (event.type() == DeviceEvent.EventType.CONNECTED) {
            Platform.runLater(() -> handleValidatedResult(event.validationResult()));
        } else if (event.type() == DeviceEvent.EventType.DISCONNECTED) {
            deviceSyncQueue.remove(event.hardwareId());
            Platform.runLater(() -> closeSyncConfirmationForHardwareId(event.hardwareId()));
        }
    }

    // Programmatically dismiss any open sync-confirmation dialog for a device that
    // disconnected so the user is not left waiting on a stale prompt.
    private void closeSyncConfirmationForHardwareId(String hardwareId) {
        Alert alert = activeAlertsByHardwareId.remove(hardwareId);
        if (alert == null) {
            return;
        }

        alert.setResult(ButtonType.CANCEL);
        DialogPane pane = alert.getDialogPane();
        if (pane != null && pane.getScene() != null && pane.getScene().getWindow() != null) {
            pane.getScene().getWindow().hide();
        }
    }

    private void registerAlert(String hardwareId, Alert alert) {
        activeAlertsByHardwareId.put(hardwareId, alert);
    }

    private void unregisterAlert(String hardwareId, Alert alert) {
        activeAlertsByHardwareId.remove(hardwareId, alert);
    }

    // Skip invalid or unrecognized connections; only process confirmed valid
    // results.
    private void handleValidatedResult(DeviceValidationResult result) {
        if (result == null || !result.isValid()) {
            return;
        }

        String deviceName = result.getAccountUserId();
        if (result.isAlreadySaved()) {
            // Update last_seen_at and enqueue sync for already-registered devices.
            deviceValidationService.saveValidatedDevice(
                    deviceName,
                    result.getHardwareId(),
                    result.getMatchedWhitelistId());
            showNoticeSuccess(I18n.get("device.connected.saved", deviceName));
            showSyncConfirmation(result.getHardwareId(), deviceName);
            return;
        }

        if (session.isAdmin()) {
            refreshDashboardIfActive();
            showSaveDeviceConfirmation(result);
        }
    }

    private void showSaveDeviceConfirmation(DeviceValidationResult result) {
        if (result == null || !result.isValid()) {
            showNoticeError(I18n.get("device.validation.failed"));
            return;
        }

        String deviceName = result.getAccountUserId();
        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("device.save.title"),
                I18n.get("device.save.header"),
                I18n.get(
                        "device.save.content",
                        deviceName,
                        result.getMatchedModelName()));

        ButtonType yesButton = new ButtonType(I18n.get("common.yes"), ButtonBar.ButtonData.YES);
        ButtonType noButton = new ButtonType(I18n.get("common.no"), ButtonBar.ButtonData.NO);
        AlertHelper.setButtons(confirm, yesButton, noButton);

        registerAlert(result.getHardwareId(), confirm);
        Optional<ButtonType> chosen;
        try {
            chosen = confirm.showAndWait();
        } finally {
            unregisterAlert(result.getHardwareId(), confirm);
        }
        if (chosen.isEmpty() || chosen.get() != yesButton) {
            return;
        }

        ValidatedDevice saved = deviceValidationService.saveValidatedDevice(
                deviceName,
                result.getHardwareId(),
                result.getMatchedWhitelistId());
        deviceTracker.markKnownAsSaved(result);
        if (currentDashboardController != null) {
            currentDashboardController.markDeviceSaved(result, saved.getDeviceName());
        }
        showNoticeSuccess(I18n.get("device.saved.success", saved.getDeviceName()));
        showSyncConfirmation(result.getHardwareId(), saved.getDeviceName());
        refreshDashboardIfActive();
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

    @EventListener
    public void onSyncCompleted(DeviceSyncCompletedEvent event) {
        if (currentDashboardController != null) {
            currentDashboardController.onSyncCompleted();
        }
    }

    @EventListener
    public void onBackupCompleted(FileBackupCompletedEvent event) {
        if (currentDashboardController != null) {
            currentDashboardController.onBackupCompleted();
        }
    }

    private void handleRequestValidate(DeviceSummary summary) {
        if (summary == null || summary.getValidationResult() == null) {
            showNoticeError(I18n.get("device.validation.failed"));
            return;
        }
        showSaveDeviceConfirmation(summary.getValidationResult());
    }

    private void handleRequestSync(DeviceSummary summary) {
        SyncProgressTracker.SyncProgress progress = syncProgressTracker.getProgress(summary.getHardwareId());
        if (progress != null) {
            SyncProgressTracker.SyncStatus status = progress.status();
            if (status == SyncProgressTracker.SyncStatus.QUEUED
                    || status == SyncProgressTracker.SyncStatus.SYNCING) {
                showNoticeError(I18n.get("device.sync.already.queued", summary.getDisplayName()));
                return;
            }
        }
        showSyncConfirmation(summary.getHardwareId(), summary.getDisplayName());
    }

    private void showSyncConfirmation(String hardwareId, String deviceName) {
        boolean autoDelete = adminSettingsService.getAutoDelete();
        String contentKey = autoDelete ? "device.sync.content.autodelete" : "device.sync.content.keep";

        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("device.sync.title"),
                I18n.get("device.sync.header", deviceName),
                I18n.get(contentKey));

        ButtonType yesButton = new ButtonType(I18n.get("common.yes"), ButtonBar.ButtonData.YES);
        ButtonType noButton = new ButtonType(I18n.get("common.no"), ButtonBar.ButtonData.NO);
        AlertHelper.setButtons(confirm, yesButton, noButton);

        registerAlert(hardwareId, confirm);
        try {
            Optional<ButtonType> chosen = confirm.showAndWait();
            if (chosen.isEmpty() || chosen.get() != yesButton) {
                return;
            }

            boolean queued = deviceSyncQueue.add(hardwareId,
                    new SyncContext(session.getUser().getUsername(), session.isAdmin()));
            if (queued) {
                showNoticeSuccess(I18n.get("device.sync.queued", deviceName));
            }
            refreshDashboardIfActive();
        } finally {
            unregisterAlert(hardwareId, confirm);
        }
    }
}
