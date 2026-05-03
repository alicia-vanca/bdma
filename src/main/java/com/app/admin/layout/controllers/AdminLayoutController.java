package com.app.admin.layout.controllers;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.MainApp;
import com.app.admin.settingsdialog.controllers.AdminSettingsDialogController;
import com.app.admin.usermanagement.controllers.UserEditFormController;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.ViewPaths;
import com.app.common.definitions.enums.FolderType;
import com.app.common.dtos.DeviceSummary;
import com.app.common.dtos.DeviceValidationResult;
import com.app.common.dtos.SyncContext;
import com.app.common.events.DeviceEvent;
import com.app.common.helpers.AlertHelper;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.ViewLoader;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.modules.databackup.events.FileBackupCompletedEvent;
import com.app.common.modules.datasync.DataSyncRunner;
import com.app.common.modules.datasync.events.FileSyncCompletedEvent;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.foldermanager.events.StorageIssueReason;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.foldermanager.services.StorageHealthMonitor;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.modules.settingspopup.helpers.SettingsPopupHelper;
import com.app.common.services.AppConfigService;
import com.app.common.services.AppNoticeService;
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
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

@Component
public class AdminLayoutController extends BaseLayoutController {

    private static final Logger log = LoggerFactory.getLogger(AdminLayoutController.class);
    private static final int STORAGE_OK = 0;
    private static final int STORAGE_CRITICAL = 1;
    private static final int STORAGE_UNAVAILABLE = 2;

    private final AppUpdateController appUpdateController;
    private final UserSettingService userSettingService;
    private final Session session;
    private final DeviceValidationService deviceValidationService;
    private final AppNoticeService appNoticeService;
    private final DeviceSyncQueue deviceSyncQueue;
    private final DataSyncRunner syncRunner;
    private final SyncProgressTracker syncProgressTracker;
    private final FolderManagerService folderManagerService;
    private final AppConfigService appConfigService;
    private final ApplicationEventPublisher publisher;
    private final StorageHealthMonitor storageHealthMonitor;

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
    @FXML
    private HBox warningStrip;
    @FXML
    private Label lblStatusWarning;
    @FXML
    private ProgressBar pbDataStorage;
    @FXML
    private ProgressBar pbBackupStorage;
    @FXML
    private Label lblDataStorageUsage;
    @FXML
    private Label lblBackupStorageUsage;
    @FXML
    private Label lblDriveConflictWarning;
    @FXML
    private HBox driveConflictRow;
    @FXML
    private Label lblDataStorageTitle;
    @FXML
    private Label lblDataStoragePercent;
    @FXML
    private Label lblBackupStorageTitle;
    @FXML
    private Label lblBackupStoragePercent;

    private SettingsPopupHelper settingsPopupHelper;
    private DashboardController currentDashboardController;
    private final Map<String, Alert> activeAlertsByHardwareId = new HashMap<>();
    private final Map<String, String> pendingStorageSyncs = new HashMap<>();
    private boolean storageDialogVisible;
    private volatile boolean saveStorageBlocked;

    private record FolderSelectionResult(boolean saved, String rejectionMessage) {
    }

    public AdminLayoutController(ViewLoader viewLoader,
            AppUpdateController appUpdateController,
            UserSettingService userSettingService,
            Session session,
            DeviceValidationService deviceValidationService,
            AppNoticeService appNoticeService,
            DeviceSyncQueue deviceSyncQueue,
            SyncProgressTracker syncProgressTracker,
            DataSyncRunner syncRunner,
            FolderManagerService folderManagerService,
            AppConfigService appConfigService,
            ApplicationEventPublisher publisher,
            StorageHealthMonitor storageHealthMonitor) {
        super(viewLoader);
        this.appUpdateController = appUpdateController;
        this.userSettingService = userSettingService;
        this.session = session;
        this.deviceValidationService = deviceValidationService;
        this.appNoticeService = appNoticeService;
        this.deviceSyncQueue = deviceSyncQueue;
        this.syncRunner = syncRunner;
        this.syncProgressTracker = syncProgressTracker;
        this.folderManagerService = folderManagerService;
        this.appConfigService = appConfigService;
        this.publisher = publisher;
        this.storageHealthMonitor = storageHealthMonitor;
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

        syncProgressTracker.setOnProgressChanged(this::refreshDashboardIfActive);

        openDefaultTab();
        storageHealthMonitor.checkNow();
        refreshStorageStatus();
    }

    @FXML
    public void goDashboard() {
        var result = loadViewWithController(ViewPaths.ADMIN_DASHBOARD, DashboardController.class);
        if (result != null) {
            currentDashboardController = result.controller();
            currentDashboardController.setOnRequestValidate(this::handleRequestValidate);
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
        deviceSyncQueue.clearAll();
        syncProgressTracker.clearAll();

        // Reset tracked device state for the current session. The next login will
        // start from a fresh device scan.
        syncRunner.resetForLogout();

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

    // Handle device connection/disconnection events from DeviceTracker
    @EventListener
    public void onDeviceEvent(DeviceEvent event) {
        // Ignore events before user login
        if (session.getUser() == null) {
            return;
        }

        if (event.type() == DeviceEvent.EventType.CONNECTED) {
            Platform.runLater(() -> handleValidatedResult(event.validationResult()));
        } else if (event.type() == DeviceEvent.EventType.DISCONNECTED) {
            deviceSyncQueue.remove(event.hardwareId());
            pendingStorageSyncs.remove(event.hardwareId());
            Platform.runLater(() -> closeValidationDialogForHardwareId(event.hardwareId()));
        }
    }

    // Programmatically dismiss any open validation dialog for a device that
    // disconnected so the user is not left waiting on a stale prompt.
    private void closeValidationDialogForHardwareId(String hardwareId) {
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

        String cameraId = result.getCameraId();
        if (result.isAlreadySaved()) {
            // Update last_seen_at and enqueue sync for already-registered devices.
            deviceValidationService.saveValidatedDevice(
                    cameraId,
                    result.getHardwareId(),
                    result.getMatchedWhitelistId());

            showNoticeSuccess(I18n.get("device.connected.saved", result.getDeviceName()));
            autoSyncDevice(result.getHardwareId(), result.getDeviceName());
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

        String cameraId = result.getCameraId();
        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("device.save.title"),
                I18n.get("device.save.header"),
                I18n.get(
                        "device.save.content",
                        cameraId,
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
                cameraId,
                result.getHardwareId(),
                result.getMatchedWhitelistId());
        if (currentDashboardController != null) {
            currentDashboardController.markDeviceSaved(result, saved.getDeviceName());
        }
        showNoticeSuccess(I18n.get("device.saved.success", saved.getDeviceName()));
        autoSyncDevice(result.getHardwareId(), saved.getDeviceName());
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
    public void onFileSyncCompleted(FileSyncCompletedEvent event) {
        if (currentDashboardController != null) {
            currentDashboardController.onFileSyncCompleted(event.getSyncedPath());
        }
        refreshStorageStatus();
    }

    @EventListener(FileBackupCompletedEvent.class)
    public void onFileBackupCompleted() {
        if (currentDashboardController != null) {
            currentDashboardController.onFileBackupCompleted();
        }
        refreshStorageStatus();
    }

    private void handleRequestValidate(DeviceSummary summary) {
        if (summary == null || summary.getValidationResult() == null) {
            showNoticeError(I18n.get("device.validation.failed"));
            return;
        }
        showSaveDeviceConfirmation(summary.getValidationResult());
    }

    // Auto-sync device without confirmation dialog
    private void autoSyncDevice(String hardwareId, String deviceName) {
        if (saveStorageBlocked) {
            pendingStorageSyncs.put(hardwareId, deviceName);
            if (session.isAdmin()) {
                publisher.publishEvent(new StorageUnavailableEvent(FolderType.SAVE, StorageIssueReason.LOW_SPACE));
            } else {
                showNoticeError(I18n.get("storage.unavailable.user.contact_admin"));
            }
            return;
        }

        if (!folderManagerService.isDataDirAccessible()) {
            pendingStorageSyncs.put(hardwareId, deviceName);
            if (session.isAdmin()) {
                publisher.publishEvent(
                        new StorageUnavailableEvent(FolderType.SAVE, StorageIssueReason.DRIVE_UNAVAILABLE));
            } else {
                showNoticeError(I18n.get("storage.unavailable.user.contact_admin"));
            }
            return;
        }

        String isAutoDeleteStr = appConfigService.getConfigValue(AppConstants.KEY_IS_AUTO_DELETE_AFTER_SYNC);
        boolean autoDelete = "true".equalsIgnoreCase(isAutoDeleteStr);

        boolean queued = deviceSyncQueue.add(hardwareId,
                new SyncContext(session.getUser().getUsername(), session.isAdmin(),
                        folderManagerService.getDataDir(), autoDelete));
        if (queued) {
            showNoticeSuccess(I18n.get("device.sync.queued", deviceName));
        }
        refreshDashboardIfActive();
    }

    public void refreshStorageStatus() {
        Platform.runLater(() -> {
            File dataDir = folderManagerService.getDataDir();
            File backupDir = folderManagerService.getBackupDir();

            boolean sameParent = isSameParentFolder(dataDir, backupDir);

            int dataState = updateStorageBar("status.dataFolder", pbDataStorage, lblDataStorageTitle,
                    lblDataStoragePercent, lblDataStorageUsage, dataDir);
            int backupState = updateStorageBar("status.backupFolder", pbBackupStorage, lblBackupStorageTitle,
                    lblBackupStoragePercent, lblBackupStorageUsage, backupDir);

            lblDriveConflictWarning.setText(I18n.get("setting.storage.warn.same_drive"));
            driveConflictRow.setVisible(sameParent);
            driveConflictRow.setManaged(sameParent);

            updateStorageWarning(dataState, backupState);
        });
    }

    private boolean isSameParentFolder(File dataDir, File backupDir) {
        if (dataDir == null || backupDir == null) {
            return false;
        }
        Path dataRoot = Path.of(dataDir.getAbsolutePath()).getRoot();
        Path backupRoot = Path.of(backupDir.getAbsolutePath()).getRoot();
        if (dataRoot == null || backupRoot == null) {
            return false;
        }
        return dataRoot.toString().equalsIgnoreCase(backupRoot.toString());
    }

    private void updateStorageWarning(int dataState, int backupState) {
        if (dataState == STORAGE_OK && backupState == STORAGE_OK) {
            clearStatusWarning();
            return;
        }
        showStatusWarning(I18n.get(resolveStorageWarningKey(dataState, backupState)));
    }

    private String resolveStorageWarningKey(int dataState, int backupState) {
        if (dataState == STORAGE_UNAVAILABLE && backupState == STORAGE_UNAVAILABLE) {
            return "status.storage.both.unavailable";
        }
        if (dataState == STORAGE_UNAVAILABLE) {
            return "status.storage.data.unavailable";
        }
        if (backupState == STORAGE_UNAVAILABLE) {
            return "status.storage.backup.unavailable";
        }

        if (dataState == STORAGE_CRITICAL && backupState == STORAGE_CRITICAL) {
            return "status.storage.both.critical";
        }
        if (dataState == STORAGE_CRITICAL) {
            return "status.storage.data.critical";
        }
        return "status.storage.backup.critical";
    }

    private int updateStorageBar(String labelKey, ProgressBar pb,
            Label titleLbl, Label percentLbl, Label usageLbl, File folder) {
        File statsTarget = resolveStorageStatsTarget(folder);
        if (statsTarget == null || !statsTarget.exists()) {
            pb.setProgress(0);
            pb.getStyleClass().removeAll("storage-warn", "storage-critical");
            pb.getStyleClass().add("storage-critical");
            titleLbl.setText(I18n.get(labelKey));
            percentLbl.setText("⚠");
            usageLbl.setText(I18n.get("status.storage.drive.not_found"));
            return STORAGE_UNAVAILABLE;
        }

        long total = statsTarget.getTotalSpace();
        long free = statsTarget.getFreeSpace();

        if (total <= 0) {
            File root = statsTarget.toPath().getRoot() != null ? statsTarget.toPath().getRoot().toFile() : null;
            if (root != null && root.exists()) {
                total = root.getTotalSpace();
                free = root.getFreeSpace();
            }
        }

        if (total <= 0) {
            pb.setProgress(0);
            titleLbl.setText(I18n.get(labelKey));
            percentLbl.setText("—");
            usageLbl.setText("—");
            return STORAGE_OK;
        }

        long used = total - free;
        double ratio = (double) used / total;
        pb.setProgress(ratio);
        pb.getStyleClass().removeAll("storage-warn", "storage-critical");

        if (ratio >= 0.90) {
            pb.getStyleClass().add("storage-critical");
        } else if (ratio >= 0.75) {
            pb.getStyleClass().add("storage-warn");
        }

        String driveLetter = extractDriveLetter(statsTarget);
        String folderName = I18n.get(labelKey);
        if (driveLetter != null && !driveLetter.isEmpty()) {
            titleLbl.setText(String.format("%s (%s)", folderName, driveLetter));
        } else {
            titleLbl.setText(folderName);
        }
        percentLbl.setText(String.format("%.0f%%", ratio * 100));
        usageLbl.setText(String.format("%s / %s", formatBytes(used), formatBytes(total)));

        return ratio >= 0.90 ? STORAGE_CRITICAL : STORAGE_OK;
    }

    // Storage-protected folders can be renamed/hidden when locked, so the exact
    // configured path may not exist at UI refresh time. Use nearest existing path
    // (or drive root) to read volume metrics reliably.
    private File resolveStorageStatsTarget(File folder) {
        if (folder == null) {
            return null;
        }
        if (folder.exists()) {
            return folder;
        }

        File current = folder.getAbsoluteFile();
        while (current != null && !current.exists()) {
            current = current.getParentFile();
        }
        return current;
    }

    private String extractDriveLetter(File file) {
        if (file == null) {
            return null;
        }
        Path root = Path.of(file.getAbsolutePath()).getRoot();
        if (root == null) {
            return null;
        }
        return root.toString().replace("\\", "").stripTrailing();
    }

    private void showStatusWarning(String message) {
        lblStatusWarning.setText(message);
        warningStrip.setVisible(true);
        warningStrip.setManaged(true);
    }

    private void clearStatusWarning() {
        warningStrip.setVisible(false);
        warningStrip.setManaged(false);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L)
            return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)
            return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%d KB", bytes / 1_024);
    }

    @EventListener
    public void onStorageUnavailable(StorageUnavailableEvent event) {
        if (event == null) {
            return;
        }

        if (event.getTarget() == FolderType.SAVE) {
            saveStorageBlocked = true;
        }

        if (!session.isAdmin()) {
            showNoticeError(I18n.get("storage.unavailable.user.contact_admin"));
            return;
        }

        Platform.runLater(() -> showStorageUnavailableDialog(event));
    }

    @EventListener
    public void onStorageRestored(StorageRestoredEvent event) {
        if (event != null && event.getTarget() == FolderType.SAVE) {
            saveStorageBlocked = false;
            if (!pendingStorageSyncs.isEmpty()) {
                Map<String, String> toRetry = new HashMap<>(pendingStorageSyncs);
                pendingStorageSyncs.clear();
                Platform.runLater(() -> toRetry.forEach(this::autoSyncDevice));
            }
        }
        refreshStorageStatus();
    }

    private void showStorageUnavailableDialog(StorageUnavailableEvent event) {
        if (storageDialogVisible) {
            if (log.isDebugEnabled()) {
                log.debug("Storage unavailable dialog already visible. Skip opening duplicate for target={} reason={}",
                        event.getTarget(), event.getReason());
            }
            return;
        }
        storageDialogVisible = true;

        if (log.isDebugEnabled()) {
            log.debug("Opening storage unavailable dialog: target={} reason={} requiredBytes={}",
                    event.getTarget(), event.getReason(), event.getRequiredBytes());
        }

        String targetLabel = event.getTarget() == FolderType.SAVE
                ? I18n.get("status.dataFolder")
                : I18n.get("status.backupFolder");
        String header = I18n.get("storage.unavailable.dialog.title", targetLabel);
        String content = resolveStorageDialogContent(event);

        Alert alert = AlertHelper.createConfirmation(
                header,
                header,
                content);

        ButtonType chooseButton = new ButtonType(I18n.get("storage.unavailable.action.select_folder"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType laterButton = new ButtonType(I18n.get("storage.unavailable.action.later"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertHelper.setButtons(alert, chooseButton, laterButton);

        try {
            while (true) {
                Optional<ButtonType> chosen = alert.showAndWait();
                if (chosen.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Storage unavailable dialog dismissed without selecting folder. target={}",
                                event.getTarget());
                    }
                    return;
                }

                if (chosen.get() == laterButton) {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "User selected 'Later' on storage unavailable dialog. target={} reason={} requiredBytes={}",
                                event.getTarget(), event.getReason(), event.getRequiredBytes());
                    }
                    if (event.getTarget() == FolderType.SAVE) {
                        saveStorageBlocked = false;
                    }
                    publisher.publishEvent(new StorageRecoveryDeferredEvent(event.getTarget()));
                    return;
                }

                if (chosen.get() != chooseButton) {
                    if (log.isDebugEnabled()) {
                        log.debug("Storage unavailable dialog closed with unexpected action. target={} action={}",
                                event.getTarget(), chosen.get().getText());
                    }
                    return;
                }

                if (log.isDebugEnabled()) {
                    log.debug("User selected 'Select folder' on storage unavailable dialog. target={}",
                            event.getTarget());
                }

                FolderSelectionResult result = chooseAndSaveStorageFolder(event);
                if (result.saved()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Storage folder updated successfully from unavailable dialog. target={}",
                                event.getTarget());
                    }
                    if (event.getTarget() == FolderType.SAVE) {
                        saveStorageBlocked = false;
                        if (!pendingStorageSyncs.isEmpty()) {
                            Map<String, String> toRetry = new HashMap<>(pendingStorageSyncs);
                            pendingStorageSyncs.clear();
                            if (log.isDebugEnabled()) {
                                log.debug("Draining {} pending sync(s) after storage folder saved", toRetry.size());
                            }
                            toRetry.forEach(this::autoSyncDevice);
                        }
                    }
                    return;
                }

                if (result.rejectionMessage() != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Selected storage folder rejected. target={} message={}",
                                event.getTarget(), result.rejectionMessage());
                    }
                    showStorageSelectionError(result.rejectionMessage());
                } else if (log.isDebugEnabled()) {
                    log.debug("Folder selection canceled by user. target={}", event.getTarget());
                }
            }
        } finally {
            storageDialogVisible = false;
            if (log.isDebugEnabled()) {
                log.debug("Storage unavailable dialog closed. target={}", event.getTarget());
            }
            refreshStorageStatus();
        }
    }

    private String resolveStorageDialogContent(StorageUnavailableEvent event) {
        if (event.getReason() == StorageIssueReason.LOW_SPACE) {
            return I18n.get("storage.unavailable.dialog.low_space");
        }
        return I18n.get("storage.unavailable.dialog.drive_missing");
    }

    private FolderSelectionResult chooseAndSaveStorageFolder(StorageUnavailableEvent event) {
        FolderType target = event.getTarget();
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("setting.storage.chooser.title",
                target == FolderType.SAVE ? I18n.get("storage.type.save") : I18n.get("storage.type.backup")));

        File currentConfiguredPath = readConfiguredRootPath(target);
        if (currentConfiguredPath != null && currentConfiguredPath.exists()) {
            chooser.setInitialDirectory(currentConfiguredPath);
        }

        File selected = chooser.showDialog(MainApp.getPrimaryStage());
        if (selected == null) {
            if (log.isDebugEnabled()) {
                log.debug("Folder chooser canceled. target={}", target);
            }
            return new FolderSelectionResult(false, null);
        }

        if (log.isDebugEnabled()) {
            log.debug("Folder selected from chooser. target={} path={} requiredBytes={}",
                    target, selected.getAbsolutePath(), event.getRequiredBytes());
        }

        String rejection = validateSelectedStorageFolder(target, selected, event.getRequiredBytes());
        if (rejection != null) {
            return new FolderSelectionResult(false, rejection);
        }

        String key = target == FolderType.SAVE ? AppConstants.KEY_DATA_DIR : AppConstants.KEY_BACKUP_DIR;
        appConfigService.saveConfigValue(key, selected.getAbsolutePath());
        folderManagerService.init();
        showNoticeSuccess(I18n.get("storage.unavailable.saved"));
        storageHealthMonitor.checkNow();
        if (log.isInfoEnabled()) {
            log.info("Storage folder changed from unavailable dialog. target={} path={}",
                    target, selected.getAbsolutePath());
        }
        return new FolderSelectionResult(true, null);
    }

    private void showStorageSelectionError(String message) {
        if (log.isDebugEnabled()) {
            log.debug("Showing storage selection error dialog: {}", message);
        }
        Alert error = AlertHelper.create(Alert.AlertType.ERROR,
                I18n.get("setting.storage.error"),
                null,
                message);
        AlertHelper.setButtons(error, ButtonType.OK);
        error.showAndWait();
    }

    private File readConfiguredRootPath(FolderType target) {
        String key = target == FolderType.SAVE ? AppConstants.KEY_DATA_DIR : AppConstants.KEY_BACKUP_DIR;
        String path = appConfigService.getConfigValue(key);
        if (path == null || path.isBlank()) {
            return null;
        }
        return new File(path);
    }

    private String validateSelectedStorageFolder(FolderType target, File selectedRoot, long requiredBytes) {
        File managedDir = new File(selectedRoot,
                target == FolderType.SAVE ? AppConstants.DATA_FOLDER_NAME : AppConstants.BACKUP_FOLDER_NAME);

        if (!folderManagerService.isDirAccessible(managedDir)) {
            return I18n.get("storage.unavailable.dialog.drive_missing");
        }

        if (requiredBytes > 0 && !folderManagerService.hasSufficientSpace(managedDir, requiredBytes)) {
            return I18n.get("storage.unavailable.dialog.low_space");
        }

        try {
            folderManagerService.withSpecificDirPrepared(managedDir, () -> Boolean.TRUE);
        } catch (Exception e) {
            log.warn("Rejected storage folder selection for {}: {}", target, selectedRoot.getAbsolutePath(), e);
            return I18n.get("setting.storage.error");
        }

        return null;
    }
}
