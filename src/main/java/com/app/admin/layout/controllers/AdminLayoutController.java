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
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.admin.settingsdialog.services.BackupSyncService;
import com.app.admin.usermanagement.controllers.UserEditFormController;
import com.app.common.definitions.ViewPaths;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
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
import com.app.common.modules.databackup.DataBackupRunner;
import com.app.common.modules.databackup.events.FileBackupCompletedEvent;
import com.app.common.modules.datasync.DataSyncRunner;
import com.app.common.modules.datasync.events.FileSyncCompletedEvent;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.foldermanager.events.StorageRecoveryCompletedEvent;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.foldermanager.services.StorageHealthMonitor;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.modules.settingspopup.helpers.SettingsPopupHelper;
import com.app.common.repositories.ValidatedDeviceRepository;
import com.app.common.services.AdbClient;
import com.app.common.services.AppNoticeService;
import com.app.common.services.DeviceValidationService;
import com.app.common.services.SyncProgressTracker;
import com.app.common.services.UserSettingService;
import com.app.common.utils.FileUtil;
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
    private static final String STATUS_SYNC_DRIVE = "status.syncDrive";
    private static final String STATUS_BACKUP_DRIVE = "status.backupDrive";
    private static final String CSS_STORAGE_WARN = "storage-warn";
    private static final String CSS_STORAGE_CRITICAL = "storage-critical";

    // I18n keys
    private static final String I18N_DEVICE_SYNC_QUEUED = "device.sync.queued";
    private static final String I18N_STATUS_STORAGE_DRIVE_NOT_FOUND = "status.storage.drive.not_found";
    private static final String I18N_STORAGE_LOW_SPACE_TITLE = "storage.unavailable.dialog.low_space.title";
    private static final String I18N_STORAGE_LOW_SPACE_USER_MESSAGE = "storage.unavailable.dialog.low_space.user.message";
    private static final String I18N_STORAGE_LOW_SPACE_ADMIN_MESSAGE = "storage.unavailable.dialog.low_space.admin.message";
    private static final String I18N_STORAGE_DRIVE_MISSING_TITLE = "storage.unavailable.dialog.drive_missing.title";
    private static final String I18N_STORAGE_DRIVE_MISSING_USER_MESSAGE = "storage.unavailable.dialog.drive_missing.user.message";
    private static final String I18N_STORAGE_DRIVE_MISSING_ADMIN_MESSAGE = "storage.unavailable.dialog.drive_missing.admin.message";
    private static final String I18N_SETTING_STORAGE_BTN_CHOOSE = "setting.storage.btn.choose";
    private static final String I18N_STORAGE_ACTION_LATER = "storage.unavailable.action.later";
    private static final String I18N_SETTING_STORAGE_CHOOSER_TITLE = "setting.storage.chooser.title";
    private static final String I18N_SETTING_STORAGE_SUCCESS = "setting.storage.success";
    private static final String I18N_SETTING_STORAGE_ERROR = "setting.storage.error";
    private static final String I18N_SETTING_STORAGE_LOW_SPACE = "setting.storage.low_space";
    private static final String I18N_SETTING_STORAGE_DRIVE_MISSING = "setting.storage.drive_missing";

    private final AppUpdateController appUpdateController;
    private final UserSettingService userSettingService;
    private final Session session;
    private final DeviceValidationService deviceValidationService;
    private final AppNoticeService appNoticeService;
    private final DeviceSyncQueue deviceSyncQueue;
    private final DataSyncRunner syncRunner;
    private final DataBackupRunner backupRunner;
    private final SyncProgressTracker syncProgressTracker;
    private final FolderManagerService folderManagerService;
    private final ApplicationEventPublisher publisher;
    private final StorageHealthMonitor storageHealthMonitor;
    private final BackupSyncService backupSyncService;
    private final AdbClient adbClient;
    private final ValidatedDeviceRepository validatedDeviceRepository;
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
    private boolean syncLowSpaceDialogVisible;
    private boolean backupLowSpaceDialogVisible;
    private boolean syncUnavailableDialogVisible;
    private boolean backupUnavailableDialogVisible;
    private volatile StorageIssueReason syncStorageBlocked;

    @SuppressWarnings("unused")
    // backupStorageBlocked is currently only set in StorageHealthMonitor and not
    // read, but this is intentional to keep track of backup storage state for
    // future use when we may need it.
    private volatile StorageIssueReason backupStorageBlocked;

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
            DataBackupRunner backupRunner,
            FolderManagerService folderManagerService,
            ApplicationEventPublisher publisher,
            StorageHealthMonitor storageHealthMonitor,
            BackupSyncService backupSyncService,
            AdbClient adbClient,
            ValidatedDeviceRepository validatedDeviceRepository,
            AdminSettingsDialogService adminSettingsService) {
        super(viewLoader);
        this.appUpdateController = appUpdateController;
        this.userSettingService = userSettingService;
        this.session = session;
        this.deviceValidationService = deviceValidationService;
        this.appNoticeService = appNoticeService;
        this.deviceSyncQueue = deviceSyncQueue;
        this.syncRunner = syncRunner;
        this.backupRunner = backupRunner;
        this.syncProgressTracker = syncProgressTracker;
        this.folderManagerService = folderManagerService;
        this.publisher = publisher;
        this.storageHealthMonitor = storageHealthMonitor;
        this.backupSyncService = backupSyncService;
        this.adbClient = adbClient;
        this.validatedDeviceRepository = validatedDeviceRepository;
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

        syncProgressTracker.setOnProgressChanged(this::refreshDashboardIfActive);

        openDefaultTab();
        refreshStorageStatus();
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

    private void handleRequestValidate(DeviceSummary summary) {
        if (summary == null || summary.getValidationResult() == null) {
            showNoticeError(I18n.get("device.validation.failed"));
            return;
        }
        showSaveDeviceConfirmation(summary.getValidationResult());
    }

    private void handleRequestSync(DeviceSummary summary) {
        if (summary == null) {
            showNoticeError(I18n.get("device.sync.failed"));
            return;
        }
        // Don't show sync dialog if device is no longer connected
        if (!summary.isConnected()) {
            return;
        }
        showSyncConfirmation(summary.getHardwareId(), summary.getDeviceName());
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
        backupSyncService.cancel();
        backupSyncService.clearLastFailures();
        deviceSyncQueue.clearAll();
        syncProgressTracker.clearAll();

        // Reset tracked device state for the current session. The next login will
        // start from a fresh device scan.
        backupRunner.resetForLogout();
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
            Platform.runLater(() -> closeDialogForHardwareId(event.hardwareId()));
        }
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
            if (!backupSyncService.isRunning()) {
                registerDeviceForSync(result.getHardwareId(), result.getDeviceName());
            }
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
        registerDeviceForSync(result.getHardwareId(), saved.getDeviceName());
        refreshDashboardIfActive();
    }

    private void showSyncConfirmation(String hardwareId, String deviceName) {
        // Check if device is already in sync queue
        if (deviceSyncQueue.isInQueue(hardwareId)) {
            showNoticeSuccess(I18n.get(I18N_DEVICE_SYNC_QUEUED, deviceName));
            return;
        }

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

            // Check if device is in pending queue
            boolean isInPending = pendingStorageSyncs.containsKey(hardwareId);
            if (isInPending) {
                // Move from pending to sync queue
                pendingStorageSyncs.remove(hardwareId);
            }
            registerDeviceForSync(hardwareId, deviceName);
            refreshDashboardIfActive();
        } finally {
            unregisterAlert(hardwareId, confirm);
        }
    }

    private void registerAlert(String hardwareId, Alert alert) {
        activeAlertsByHardwareId.put(hardwareId, alert);
    }

    private void unregisterAlert(String hardwareId, Alert alert) {
        activeAlertsByHardwareId.remove(hardwareId, alert);
    }

    // Programmatically dismiss any open dialog for a device that disconnected so
    // the user is not left waiting on a stale prompt.
    private void closeDialogForHardwareId(String hardwareId) {
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

    // Auto-sync device without confirmation dialog
    private void registerDeviceForSync(String hardwareId, String deviceName) {
        if (syncStorageBlocked != null) {
            log.debug("Sync drive is pending recovery, adding device {} to pending list", hardwareId);
            pendingStorageSyncs.put(hardwareId, deviceName);
            return;
        }

        boolean autoDelete = adminSettingsService.getAutoDelete();

        boolean queued = deviceSyncQueue.add(hardwareId,
                new SyncContext(session.getUser().getUsername(), session.isAdmin(),
                        folderManagerService.getDataDir(), autoDelete, deviceName, false));
        // Only show success notice if device wasn't already in queue
        if (queued) {
            showNoticeSuccess(I18n.get(I18N_DEVICE_SYNC_QUEUED, deviceName));
        }
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

    public void refreshStorageStatus() {
        Platform.runLater(() -> {
            File dataDir = folderManagerService.getDataDir();
            File backupDir = folderManagerService.getBackupDir();

            boolean sameParent = isSameParentFolder(dataDir, backupDir);

            lblDriveConflictWarning.setText(I18n.get("setting.storage.warn.same_drive"));
            driveConflictRow.setVisible(sameParent);
            driveConflictRow.setManaged(sameParent);

            int dataState = updateStorageBar(STATUS_SYNC_DRIVE, pbDataStorage, lblDataStorageTitle,
                    lblDataStoragePercent, lblDataStorageUsage, dataDir);
            int backupState = updateStorageBar(STATUS_BACKUP_DRIVE, pbBackupStorage, lblBackupStorageTitle,
                    lblBackupStoragePercent, lblBackupStorageUsage, backupDir);

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
            return "status.storage.syncDrive.unavailable";
        }
        if (backupState == STORAGE_UNAVAILABLE) {
            return "status.storage.backupDrive.unavailable";
        }

        if (dataState == STORAGE_CRITICAL && backupState == STORAGE_CRITICAL) {
            return "status.storage.both.critical";
        }
        if (dataState == STORAGE_CRITICAL) {
            return "status.storage.syncDrive.critical";
        }
        return "status.storage.backupDrive.critical";
    }

    private int updateStorageBar(String labelKey, ProgressBar pb,
            Label titleLbl, Label percentLbl, Label usageLbl, File folder) {
        if (pb == null || titleLbl == null || percentLbl == null || usageLbl == null) {
            return STORAGE_OK;
        }

        File statsTarget = resolveStorageStatsTarget(folder);
        if (statsTarget == null || !statsTarget.exists()) {
            setStorageUnavailableUI(pb, titleLbl, percentLbl, usageLbl, labelKey);
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
            setStorageUnknownUI(pb, titleLbl, percentLbl, usageLbl, labelKey);
            return STORAGE_OK;
        }

        long used = total - free;
        double ratio = (double) used / total;
        updateStorageUI(pb, titleLbl, percentLbl, usageLbl, labelKey, statsTarget, ratio);

        return ratio >= 0.90 ? STORAGE_CRITICAL : STORAGE_OK;
    }

    // Set UI state when storage drive is unavailable
    private void setStorageUnavailableUI(ProgressBar pb, Label titleLbl, Label percentLbl, Label usageLbl,
            String labelKey) {
        pb.setProgress(0);
        pb.getStyleClass().removeAll(CSS_STORAGE_WARN, CSS_STORAGE_CRITICAL);
        pb.getStyleClass().add(CSS_STORAGE_CRITICAL);
        titleLbl.setText(I18n.get(labelKey));
        percentLbl.setText("⚠");
        usageLbl.setText(I18n.get(I18N_STATUS_STORAGE_DRIVE_NOT_FOUND));
    }

    // Set UI state when storage metrics are unknown
    private void setStorageUnknownUI(ProgressBar pb, Label titleLbl, Label percentLbl, Label usageLbl,
            String labelKey) {
        pb.setProgress(0);
        titleLbl.setText(I18n.get(labelKey));
        percentLbl.setText("—");
        usageLbl.setText("—");
    }

    // Update storage UI with calculated metrics
    private void updateStorageUI(ProgressBar pb, Label titleLbl, Label percentLbl, Label usageLbl,
            String labelKey, File statsTarget, double ratio) {
        pb.setProgress(ratio);
        pb.getStyleClass().removeAll(CSS_STORAGE_WARN, CSS_STORAGE_CRITICAL);

        if (ratio >= 0.90) {
            pb.getStyleClass().add(CSS_STORAGE_CRITICAL);
        } else if (ratio >= 0.75) {
            pb.getStyleClass().add(CSS_STORAGE_WARN);
        }

        setStorageLabels(titleLbl, percentLbl, usageLbl, labelKey, statsTarget, ratio);
    }

    // Set storage label text values
    private void setStorageLabels(Label titleLbl, Label percentLbl, Label usageLbl,
            String labelKey, File statsTarget, double ratio) {
        String driveLetter = FileUtil.extractDriveLetter(statsTarget);
        String folderName = I18n.get(labelKey);
        if (driveLetter != null && !driveLetter.isEmpty()) {
            titleLbl.setText(String.format("%s (%s)", folderName, driveLetter));
        } else {
            titleLbl.setText(folderName);
        }
        percentLbl.setText(String.format("(%.0f%%)", ratio * 100));

        long total = statsTarget.getTotalSpace();
        long free = statsTarget.getFreeSpace();
        long used = total - free;
        usageLbl.setText(String.format("%s / %s", formatBytes(used), formatBytes(total)));
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

    private boolean isDialogStillOpen(StorageUnavailableEvent event) {
        boolean isLowSpace = event.getReason() == StorageIssueReason.LOW_SPACE;
        boolean isSyncTarget = event.getTarget() == FolderType.SYNC;

        if (isLowSpace) {
            return isSyncTarget ? backupLowSpaceDialogVisible : syncLowSpaceDialogVisible;
        } else {
            return isSyncTarget ? backupUnavailableDialogVisible : syncUnavailableDialogVisible;
        }
    }

    private void setDialogVisibleFlag(StorageUnavailableEvent event, boolean visible) {
        boolean isLowSpace = event.getReason() == StorageIssueReason.LOW_SPACE;
        boolean isSyncTarget = event.getTarget() == FolderType.SYNC;

        if (isLowSpace) {
            if (isSyncTarget) {
                syncLowSpaceDialogVisible = visible;
            } else {
                backupLowSpaceDialogVisible = visible;
            }
        } else {
            if (isSyncTarget) {
                syncUnavailableDialogVisible = visible;
            } else {
                backupUnavailableDialogVisible = visible;
            }
        }
    }

    private boolean isDialogAlreadyVisible(StorageUnavailableEvent event) {
        boolean isLowSpace = event.getReason() == StorageIssueReason.LOW_SPACE;
        boolean isSyncTarget = event.getTarget() == FolderType.SYNC;

        if (isLowSpace && isSyncTarget && syncLowSpaceDialogVisible) {
            logDebug("Sync low-space dialog already visible. Skip duplicate.");
            return true;
        }
        if (isLowSpace && !isSyncTarget && backupLowSpaceDialogVisible) {
            logDebug("Backup low-space dialog already visible. Skip duplicate.");
            return true;
        }
        if (!isLowSpace && isSyncTarget && syncUnavailableDialogVisible) {
            logDebug("Sync unavailable dialog already visible. Skip duplicate.");
            return true;
        }
        if (!isLowSpace && !isSyncTarget && backupUnavailableDialogVisible) {
            logDebug("Backup unavailable dialog already visible. Skip duplicate.");
            return true;
        }
        return false;
    }

    private boolean shouldQueueDialog(StorageUnavailableEvent event) {
        boolean isLowSpace = event.getReason() == StorageIssueReason.LOW_SPACE;
        boolean isSyncTarget = event.getTarget() == FolderType.SYNC;

        if (isLowSpace && isSyncTarget && backupLowSpaceDialogVisible) {
            logDebug("Backup drive low-space dialog is opening. Queueing sync low-space dialog.");
            return true;
        }
        if (isLowSpace && !isSyncTarget && syncLowSpaceDialogVisible) {
            logDebug("Sync drive low-space dialog is opening. Queueing backup low-space dialog.");
            return true;
        }
        if (!isLowSpace && isSyncTarget && backupUnavailableDialogVisible) {
            logDebug("Backup drive unavailable dialog is opening. Queueing sync unavailable dialog.");
            return true;
        }
        if (!isLowSpace && !isSyncTarget && syncUnavailableDialogVisible) {
            logDebug("Sync drive unavailable dialog is opening. Queueing backup unavailable dialog.");
            return true;
        }
        return false;
    }

    private void queueDialogUntilOtherCloses(StorageUnavailableEvent event) {
        new Thread(() -> {
            while (isDialogStillOpen(event)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    setDialogVisibleFlag(event, false);
                    return;
                }
            }
            Platform.runLater(() -> showStorageUnavailableDialog(event));
        }, "storage-dialog-queue").start();
    }

    @EventListener
    public void onStorageUnavailable(StorageUnavailableEvent event) {
        if (event == null) {
            return;
        }
        logDebug("StorageUnavailableEvent: target: {}, reason: {}", event.getTarget(), event.getReason());

        if (isDialogAlreadyVisible(event)) {
            return;
        }

        boolean shouldQueue = shouldQueueDialog(event);

        // Mark this dialog as visible before showing or queueing
        setDialogVisibleFlag(event, true);

        if (shouldQueue) {
            queueDialogUntilOtherCloses(event);
        } else {
            Platform.runLater(() -> showStorageUnavailableDialog(event));
        }
    }

    private void showStorageUnavailableDialog(StorageUnavailableEvent event) {
        logDebug("Opening storage-unavailable dialog: target={} reason={} requiredBytes={}",
                event.getTarget(), event.getReason(), event.getRequiredBytes());

        try {
            String targetDrive = "";
            if (event.getTarget() == FolderType.SYNC) {
                syncStorageBlocked = event.getReason();
                targetDrive = I18n.get(STATUS_SYNC_DRIVE);
            } else if (event.getTarget() == FolderType.BACKUP) {
                backupStorageBlocked = event.getReason();
                targetDrive = I18n.get(STATUS_BACKUP_DRIVE);
            }

            if (!session.isAdmin()) {
                // Non-admin users see alert and flag is reset after acknowledgment
                String header = "";
                String message = "";
                if (event.getReason() == StorageIssueReason.LOW_SPACE) {
                    header = I18n.get(I18N_STORAGE_LOW_SPACE_TITLE, targetDrive);
                    message = I18n.get(I18N_STORAGE_LOW_SPACE_USER_MESSAGE, targetDrive);
                } else if (event.getReason() == StorageIssueReason.DRIVE_UNAVAILABLE) {
                    header = I18n.get(I18N_STORAGE_DRIVE_MISSING_TITLE, targetDrive);
                    message = I18n.get(I18N_STORAGE_DRIVE_MISSING_USER_MESSAGE, targetDrive);
                }

                Alert alert = AlertHelper.createInformation(header, null, message);
                alert.showAndWait();
                publisher.publishEvent(new StorageRecoveryDeferredEvent(event.getTarget()));
            } else {

                Alert alert = createStorageUnavailableAlert(event);

                handleStorageDialogLoop(event, alert);
            }
        } finally {
            setDialogVisibleFlag(event, false);
            if (event.getTarget() == FolderType.SYNC) {
                syncStorageBlocked = null;
            } else if (event.getTarget() == FolderType.BACKUP) {
                backupStorageBlocked = null;
            }
            logDebug("Storage unavailable dialog closed. target={}", event.getTarget());
            refreshStorageStatus();
        }
    }

    private Alert createStorageUnavailableAlert(StorageUnavailableEvent event) {
        String targetDrive = switch (event.getTarget()) {
            case SYNC -> I18n.get(STATUS_SYNC_DRIVE);
            case BACKUP -> I18n.get(STATUS_BACKUP_DRIVE);
        };
        String header = "";
        String content = "";
        if (event.getReason() == StorageIssueReason.LOW_SPACE) {
            header = I18n.get(I18N_STORAGE_LOW_SPACE_TITLE, targetDrive);
            content = I18n.get(I18N_STORAGE_LOW_SPACE_ADMIN_MESSAGE, targetDrive);
        } else if (event.getReason() == StorageIssueReason.DRIVE_UNAVAILABLE) {
            header = I18n.get(I18N_STORAGE_DRIVE_MISSING_TITLE, targetDrive);
            content = I18n.get(I18N_STORAGE_DRIVE_MISSING_ADMIN_MESSAGE, targetDrive);
        }
        Alert alert = AlertHelper.createConfirmation(header, header, content);

        ButtonType chooseButton = new ButtonType(I18n.get(I18N_SETTING_STORAGE_BTN_CHOOSE),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType laterButton = new ButtonType(I18n.get(I18N_STORAGE_ACTION_LATER),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertHelper.setButtons(alert, chooseButton, laterButton);

        return alert;
    }

    private void handleStorageDialogLoop(StorageUnavailableEvent event, Alert alert) {
        while (true) {
            Optional<ButtonType> chosen = alert.showAndWait();

            if (chosen.isEmpty()) {
                logDebug("Storage-unavailable dialog dismissed without selecting folder. target={}", event.getTarget());
                return;
            }

            ButtonType selectedButton = chosen.get();

            if (isLaterButtonSelected(selectedButton)) {
                handleLaterSelection(event);
                return;
            }

            if (!isChooseButtonSelected(selectedButton)) {
                logDebug("Storage-unavailable dialog closed with unexpected action. target={} action={}",
                        event.getTarget(), selectedButton.getText());
                return;
            }

            logDebug("User selected 'Select folder' on storage-unavailable dialog. target={}", event.getTarget());

            if (handleFolderSelection(event)) {
                return;
            }
        }
    }

    private boolean isLaterButtonSelected(ButtonType selected) {
        return selected.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE;
    }

    private boolean isChooseButtonSelected(ButtonType selected) {
        return selected.getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    private void handleLaterSelection(StorageUnavailableEvent event) {
        logDebug("User selected 'Later' on storage-unavailable dialog. target={} reason={} requiredBytes={}",
                event.getTarget(), event.getReason(), event.getRequiredBytes());

        publisher.publishEvent(new StorageRecoveryDeferredEvent(event.getTarget()));
    }

    private boolean handleFolderSelection(StorageUnavailableEvent event) {
        FolderSelectionResult result = chooseAndSaveStorageFolder(event);

        if (result.saved()) {
            logDebug("Storage folder updated successfully from unavailable dialog. target={}", event.getTarget());
            handleSuccessfulFolderSave(event);
            return true;
        }

        if (result.rejectionMessage() != null) {
            logDebug("Selected storage folder rejected. target={} message={}", event.getTarget(),
                    result.rejectionMessage());
            showStorageSelectionError(result.rejectionMessage());
        } else {
            logDebug("Folder selection canceled by user. target={}", event.getTarget());
        }

        return false;
    }

    private void handleSuccessfulFolderSave(StorageUnavailableEvent event) {
        if (event.getTarget() == FolderType.SYNC) {
            syncStorageBlocked = null;
            drainPendingSyncs();
        } else if (event.getTarget() == FolderType.BACKUP) {
            backupStorageBlocked = null;
        }
    }

    private void drainPendingSyncs() {
        if (!pendingStorageSyncs.isEmpty()) {
            // Continue pending devices
            log.debug("Sync drive recoveried, continuing {} pending device(s)", pendingStorageSyncs.size());
            Map<String, String> toRetry = new HashMap<>(pendingStorageSyncs);
            pendingStorageSyncs.clear();
            logDebug("Draining {} pending sync(s) after storage folder saved", toRetry.size());
            Platform.runLater(() -> toRetry.forEach(this::registerDeviceForSync));
        }
    }

    @EventListener
    public void onStorageRestored(StorageRestoredEvent event) {
        if (event != null) {
            if (event.getTarget() == FolderType.SYNC) {
                syncStorageBlocked = null;
                drainPendingSyncs();
            } else if (event.getTarget() == FolderType.BACKUP) {
                backupStorageBlocked = null;
            }
        }
        refreshStorageStatus();
    }

    private void logDebug(String message, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug(message, args);
        }
    }

    private FolderSelectionResult chooseAndSaveStorageFolder(StorageUnavailableEvent event) {
        FolderType target = event.getTarget();
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get(I18N_SETTING_STORAGE_CHOOSER_TITLE,
                target.toLocalizedString()));

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

        adminSettingsService.saveFolder(target, selected.getAbsolutePath());
        folderManagerService.init(target);
        showNoticeSuccess(I18n.get(I18N_SETTING_STORAGE_SUCCESS));
        storageHealthMonitor.checkNow(target);
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
                I18n.get(I18N_SETTING_STORAGE_ERROR),
                null,
                message);
        AlertHelper.setButtons(error, ButtonType.OK);
        error.showAndWait();
    }

    private File readConfiguredRootPath(FolderType target) {
        String path = adminSettingsService.getFolderPath(target).orElse(null);
        if (path == null || path.isBlank()) {
            return null;
        }
        return new File(path);
    }

    private String validateSelectedStorageFolder(FolderType target, File selectedRoot, long requiredBytes) {
        String folderName = target.getPhysicalFolderName();
        File managedDir = new File(selectedRoot, folderName);

        if (!folderManagerService.isDirAccessible(managedDir)) {
            return I18n.get(I18N_SETTING_STORAGE_DRIVE_MISSING);
        }

        if (requiredBytes > 0 && !folderManagerService.hasSufficientSpace(managedDir, requiredBytes)) {
            return I18n.get(I18N_SETTING_STORAGE_LOW_SPACE);
        }

        try {
            folderManagerService.withSpecificDirPrepared(managedDir, () -> Boolean.TRUE);
        } catch (Exception e) {
            log.warn("Rejected storage folder selection for {}: {}", target, selectedRoot.getAbsolutePath(), e);
            return I18n.get(I18N_SETTING_STORAGE_ERROR);
        }

        return null;
    }

    private void autoSyncDeviceAfterRestore(String hardwareId) {
        if (syncStorageBlocked != null) {
            pendingStorageSyncs.put(hardwareId, "");
            return;
        }

        if (!folderManagerService.isDataDirAccessible()) {
            return;
        }

        boolean autoDelete = adminSettingsService.getAutoDelete();

        deviceSyncQueue.add(hardwareId,
                new SyncContext(session.getUser().getUsername(), session.isAdmin(),
                        folderManagerService.getDataDir(), autoDelete, "", true));
    }

    @EventListener
    public void onStorageRestoreCompleted(StorageRecoveryCompletedEvent event) {

        if (session.getUser() == null) {
            return;
        }

        boolean wasCancelled = backupSyncService.isCancelled();

        Thread.ofVirtual().start(() -> {
            try {
                List<String> connectedSerials = adbClient.listConnectedSerials();
                if (wasCancelled) {
                    connectedSerials.forEach(serial -> {
                        String deviceName = validatedDeviceRepository
                                .findByHardwareId(serial)
                                .map(ValidatedDevice::getDeviceName)
                                .orElse(serial);
                        Platform.runLater(() -> registerDeviceForSync(serial, deviceName));
                    });
                } else {
                    connectedSerials.forEach(this::autoSyncDeviceAfterRestore);
                }
            } catch (Exception e) {
                log.warn("Failed to queue devices after restore completed", e);
            }
        });
        try {
            List<String> connectedSerials = adbClient.listConnectedSerials();
            connectedSerials.forEach(this::autoSyncDeviceAfterRestore);
        } catch (Exception e) {
            log.warn("Failed to queue devices after restore", e);
        }

        Platform.runLater(() -> {
            if (currentDashboardController != null) {
                currentDashboardController.onFileBackupCompleted();
                currentDashboardController.mergeSavedDevices();
                currentDashboardController.refresh();
            }
            refreshStorageStatus();
        });
    }
}
