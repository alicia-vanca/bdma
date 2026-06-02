package com.app.guest.controllers;

import com.app.admin.layout.services.StorageUnavailableEventHandler;
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.admin.settingsdialog.services.RestoreService;
import com.app.auth.login.controllers.LoginController;
import com.app.common.definitions.ViewPaths;
import com.app.common.definitions.enums.FolderType;
import com.app.common.dtos.DeviceValidationResult;
import com.app.common.dtos.SyncContext;
import com.app.common.events.DeviceEvent;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.ViewLoader;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.modules.databackup.events.FileBackupCompletedEvent;
import com.app.common.modules.datasync.events.FileSyncCompletedEvent;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.datasync.services.DataSyncService;
import com.app.common.modules.foldermanager.events.StorageRecoveryCompletedEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.preloginsettingspopup.helpers.PreLoginSettingsPopupHelper;
import com.app.common.modules.session.Session;
import com.app.common.services.AppNoticeService;
import com.app.common.services.DeviceMiniStatus;
import com.app.common.services.DeviceTracker;
import com.app.common.services.DeviceValidationService;
import com.app.common.utils.FileUtil;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

@Component
public class GuestLayoutController extends BaseLayoutController {

    private static final Logger log = LoggerFactory.getLogger(GuestLayoutController.class);
    private static final int STORAGE_OK = 0;
    private static final int STORAGE_CRITICAL = 1;
    private static final int STORAGE_UNAVAILABLE = 2;
    private static final String STATUS_SYNC_DRIVE = "status.syncDrive";
    private static final String STATUS_BACKUP_DRIVE = "status.backupDrive";
    private static final String CSS_STORAGE_WARN = "storage-warn";
    private static final String CSS_STORAGE_CRITICAL = "storage-critical";
    private static final String STYLE_CLASS_WARNING = "-fx-text-fill: -warning-color;";

    // I18n keys
    private static final String I18N_DEVICE_SYNC_QUEUED = "device.sync.queued";
    private static final String I18N_DEVICE_SYNC_QUEUE_FAILED = "device.sync.queue_failed";
    private static final String I18N_STATUS_STORAGE_DRIVE_NOT_FOUND = "status.storage.drive.not_found";

    private final AppUpdateController appUpdateController;
    private final Session session;
    private final DeviceValidationService deviceValidationService;
    private final AppNoticeService appNoticeService;
    private final DeviceSyncQueue deviceSyncQueue;
    private final DeviceTracker deviceTracker;
    private final DeviceMiniStatus deviceMiniStatus;
    private final FolderManagerService folderManagerService;
    private final RestoreService restoreService;
    private final DataSyncService dataSyncService;
    private final AdminSettingsDialogService adminSettingsService;
    private final StorageUnavailableEventHandler storageUnavailableEventHandler;

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
    private Label lblCombinedWarning;
    @FXML
    private ProgressBar pbDataStorage;
    @FXML
    private ProgressBar pbBackupStorage;
    @FXML
    private Label lblDataStorageUsage;
    @FXML
    private Label lblBackupStorageUsage;
    @FXML
    private Label lblDataStorageTitle;
    @FXML
    private Label lblDataStoragePercent;
    @FXML
    private Label lblBackupStorageTitle;
    @FXML
    private Label lblBackupStoragePercent;
    @FXML
    private ImageView nteIcon;

    private GuestDashboardController currentDashboardController;
    private PreLoginSettingsPopupHelper settingsPopupHelper;
    private final Map<String, Alert> activeAlertsByHardwareId = new HashMap<>();
    private final Set<String> pendingStorageSyncs = new HashSet<>();

    public GuestLayoutController(ViewLoader viewLoader,
                                 AppUpdateController appUpdateController,
                                 Session session,
                                 DeviceValidationService deviceValidationService,
                                 AppNoticeService appNoticeService,
                                 DeviceSyncQueue deviceSyncQueue,
                                 DeviceTracker deviceTracker,
                                 DeviceMiniStatus deviceMiniStatus,
                                 FolderManagerService folderManagerService,
                                 RestoreService restoreService,
                                 DataSyncService dataSyncService,
                                 AdminSettingsDialogService adminSettingsService,
                                 StorageUnavailableEventHandler storageUnavailableEventHandler) {
        super(viewLoader);
        this.appUpdateController = appUpdateController;
        this.session = session;
        this.deviceValidationService = deviceValidationService;
        this.appNoticeService = appNoticeService;
        this.deviceSyncQueue = deviceSyncQueue;
        this.deviceTracker = deviceTracker;
        this.deviceMiniStatus = deviceMiniStatus;
        this.folderManagerService = folderManagerService;
        this.restoreService = restoreService;
        this.dataSyncService = dataSyncService;
        this.adminSettingsService = adminSettingsService;
        this.storageUnavailableEventHandler = storageUnavailableEventHandler;
    }

    @Override
    protected StackPane getContentArea() {
        return contentArea;
    }

    @Override
    protected List<Button> getMenuButtons() {
        return List.of(btnDashboard, btnUser);
    }

    @FXML
    public void initialize() {
        // Keep header buttons responsive while update checks are running.
        appUpdateController.setOnCheckStart(() -> btnSettings.setDisable(true));
        appUpdateController.setOnCheckEnd(() -> btnSettings.setDisable(false));
        appUpdateController.setOnStatusChange(msg -> log.info("Update status: {}", msg));

        settingsPopupHelper = new PreLoginSettingsPopupHelper(
                "guest",
                btnSettings,
                PreLoginSettingsPopupHelper.PopupAnchorY.TOP,
                null,
                this::reloadUI,
                appUpdateController::onCheckUpdateManual,
                null);
        settingsPopupHelper.initialize();

        labelGreeting.setText(I18n.get("top.hello.guest"));

        appNoticeService.bindNoticeContainer(noticeContainer);

        deviceMiniStatus.setOnProgressChanged(this::refreshDashboardIfActive);
        setIconNte();
        showDashboard();
        refreshStorageStatus();
    }
    public void setIconNte(){
        Image image = new Image(
                Objects.requireNonNull(getClass()
                        .getResource("/image/NTE_Logo.png"))
                        .toExternalForm()
        );
        nteIcon.setImage(image);
        nteIcon.setFitWidth(150);
        nteIcon.setFitHeight(150);
        nteIcon.setPreserveRatio(true);
    }

    @FXML
    public void goLogin() {
        DialogHelper.Dialog<LoginController> dialog = DialogHelper.createDialog(
                ViewPaths.LOGIN,
                "BDMA"
        );
        Stage stage = dialog.stage();
        stage.setResizable(false);
        stage.setWidth(480);
        stage.setHeight(420);
        stage.showAndWait();
    }

    public void showDashboard() {
        var result = loadViewWithController(ViewPaths.GUEST_DASHBOARD, GuestDashboardController.class);
        if (result != null) {
            currentDashboardController = result.controller();
            dataSyncService.setOnUserAutoCreated(username -> currentDashboardController.onUserAutoCreated());
            setContent(result.node());
        }
    }

    @FXML
    private void openSettingsPopup() {
        settingsPopupHelper.togglePopup();
    }

    @Override
    public void showNoticeSuccess(String text) {
        appNoticeService.showSuccess(text);
    }

    @Override
    public void showNoticeError(String text) {
        appNoticeService.showError(text);
    }

    @EventListener(condition = "@session.user == null")
    public void onDeviceEvent(DeviceEvent event) {
        if (event.type() == DeviceEvent.EventType.CONNECTED) {
            Platform.runLater(() -> handleValidatedResult(event.validationResult()));
        } else if (event.type() == DeviceEvent.EventType.DISCONNECTED) {
            removeDeviceSyncQueue(event);
            removePendingStorageSync(event);
            Platform.runLater(() -> closeDialogForHardwareId(event.hardwareId()));
        }
    }

    private void removeDeviceSyncQueue(DeviceEvent event) {
        resolveCameraId(event).ifPresent(deviceSyncQueue::remove);
    }

    private void removePendingStorageSync(DeviceEvent event) {
        resolveCameraId(event).ifPresent(pendingStorageSyncs::remove);
    }

    private Optional<String> resolveCameraId(DeviceEvent event) {
        DeviceValidationResult result = event.validationResult();
        if (result == null || result.getCameraId() == null) {
            return Optional.empty();
        }
        return Optional.of(result.getCameraId());
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
            if (!restoreService.isRunning()) {
                registerDeviceForSync(cameraId);
            }
        }
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
    public void registerDeviceForSync(String cameraId) {
        Optional<ValidatedDevice> savedDevice = deviceValidationService.findValidatedDevice(cameraId);
        if (savedDevice.isEmpty()) {
            log.warn("Cannot queue sync because camera {} is not registered", cameraId);
            showNoticeError(I18n.get(I18N_DEVICE_SYNC_QUEUE_FAILED));
            return;
        }

        ValidatedDevice device = savedDevice.get();
        String hardwareId = device.getHardwareId();
        String deviceName = device.getDeviceName();
        if (!isDeviceConnectedForSync(hardwareId, cameraId)) {
            return;
        }

        if (storageUnavailableEventHandler.isStorageBlocked(FolderType.SYNC)) {
            log.debug("Sync drive is pending recovery, adding camera {} to pending list", cameraId);
            pendingStorageSyncs.add(cameraId);
            return;
        }

        boolean autoDelete = adminSettingsService.getAutoDelete();

        boolean queued = deviceSyncQueue.add(new SyncContext(null, false,
                folderManagerService.getSyncDir(), autoDelete, deviceName, hardwareId, cameraId));
        // Only show success notice if device wasn't already in queue
        if (queued) {
            showNoticeSuccess(I18n.get(I18N_DEVICE_SYNC_QUEUED, deviceName));
        }
        refreshDashboardIfActive();
    }

    /**
     * Checks DeviceTracker's managed state before queueing sync work. Retrying an
     * offline device must fail fast so stale queue rows do not move back to
     * processing.
     *
     * @param hardwareId hardware serial stored for the validated camera
     * @param cameraId   stable camera identifier used for user-facing logging
     * @return true when the tracker has confirmed the device is connected
     */
    private boolean isDeviceConnectedForSync(String hardwareId, String cameraId) {
        if (!deviceTracker.isConnected(hardwareId)) {
            log.warn("Cannot queue sync because camera {} hardware {} is disconnected", cameraId, hardwareId);
            showNoticeError(I18n.get(I18N_DEVICE_SYNC_QUEUE_FAILED));
            return false;
        }
        return true;
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
        Platform.runLater(() -> {
            if (currentDashboardController != null) {
                currentDashboardController.onFileSyncCompleted(event.getSyncedPath());
            }
            refreshStorageStatus();
        });
    }

    @EventListener(FileBackupCompletedEvent.class)
    public void onFileBackupCompleted() {
        Platform.runLater(() -> {
            if (currentDashboardController != null) {
                currentDashboardController.onFileBackupCompleted();
            }
            refreshStorageStatus();
        });
    }

    public void refreshStorageStatus() {
        Platform.runLater(() -> {
            File dataDir = folderManagerService.getSyncDir();
            File backupDir = folderManagerService.getBackupDir();

            boolean sameParent = isSameParentFolder(dataDir, backupDir);

            String warningText = sameParent ? I18n.get("setting.storage.warn.same_drive") : "";

            int dataState = updateStorageBar(STATUS_SYNC_DRIVE, pbDataStorage,
                    lblDataStorageTitle, lblDataStoragePercent, lblDataStorageUsage, dataDir);
            int backupState = updateStorageBar(STATUS_BACKUP_DRIVE, pbBackupStorage,
                    lblBackupStorageTitle, lblBackupStoragePercent, lblBackupStorageUsage, backupDir);

            String storageWarning = resolveStorageWarningKey(dataState, backupState);
            if (storageWarning != null) {
                String msg = I18n.get(storageWarning);
                warningText = warningText.isEmpty() ? msg : warningText + " | " + msg;
            }

            lblCombinedWarning.setText(warningText);
            lblCombinedWarning.setStyle(warningText.isEmpty() ? "" : STYLE_CLASS_WARNING);

            boolean hasWarning = !warningText.isEmpty();
            warningStrip.getStyleClass().removeAll("status-warning-strip-active");
            if (hasWarning) {
                warningStrip.getStyleClass().add("status-warning-strip-active");
            }
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

    private String resolveStorageWarningKey(int dataState, int backupState) {
        if (dataState == STORAGE_OK && backupState == STORAGE_OK) {
            return null;
        }
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

    private static String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L)
            return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)
            return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%d KB", bytes / 1_024);
    }

    private void drainPendingSyncs() {
        if (!pendingStorageSyncs.isEmpty()) {
            // Continue pending devices
            log.debug("Sync drive recovered, continuing {} pending device(s)", pendingStorageSyncs.size());
            Set<String> toRetry = new HashSet<>(pendingStorageSyncs);
            pendingStorageSyncs.clear();
            Platform.runLater(() -> toRetry.forEach(this::registerDeviceForSync));
        }
    }

    @EventListener
    public void onStorageRestored(StorageRestoredEvent event) {
        if (event != null && event.getTarget() == FolderType.SYNC) {
            drainPendingSyncs();
        }
        refreshStorageStatus();
    }

    @EventListener(StorageRecoveryCompletedEvent.class)
    public void onStorageRestoreCompleted() {
        if (session.getUser() == null) {
            return;
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
