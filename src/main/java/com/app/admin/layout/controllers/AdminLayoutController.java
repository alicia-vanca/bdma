package com.app.admin.layout.controllers;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.Objects;

import com.app.auth.login.controllers.LoginController;
import com.app.common.definitions.enums.NavigationTarget;
import com.app.common.modules.preloginsettingspopup.helpers.PreLoginSettingsPopupHelper;
import com.app.guest.controllers.GuestDashboardController;
import com.app.guest.services.NavigationIntentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.MainApp;
import com.app.admin.layout.services.StorageUnavailableEventHandler;
import com.app.admin.settingsdialog.controllers.AdminSettingsDialogController;
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.admin.settingsdialog.services.RestoreService;
import com.app.auth.totp.services.DevOtpGuardService;
import com.app.common.definitions.ViewPaths;
import com.app.common.definitions.enums.FolderType;
import com.app.common.dtos.DeviceSummary;
import com.app.common.dtos.DeviceValidationResult;
import com.app.common.dtos.SyncContext;
import com.app.common.events.DeviceEvent;
import com.app.common.events.FailureSummaryRequestedEvent;
import com.app.common.helpers.AlertHelper;
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
import com.app.common.modules.media.services.MediaViewerService;
import com.app.common.modules.session.Session;
import com.app.common.services.AppNoticeService;
import com.app.common.services.DeviceMiniStatus;
import com.app.common.services.DeviceTracker;
import com.app.common.services.DeviceValidationService;
import com.app.common.services.DeviceListState;
import com.app.common.utils.FileUtil;
import com.app.dev.settingsdialog.controllers.DevSettingsDialogController;
import com.app.user.settingsdialog.controllers.UserSettingsDialogController;
import com.app.user.userdetail.controllers.UserInfoController;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
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
    private static final String STYLE_CLASS_WARNING = "-fx-text-fill: -warning-color;";

    // I18n keys
    private static final String I18N_SETTINGS_TITLE = "settings.title";
    private static final String I18N_DEVICE_SYNC_QUEUED = "device.sync.queued";
    private static final String I18N_DEVICE_SYNC_QUEUE_FAILED = "device.sync.queue_failed";
    private static final String I18N_STATUS_STORAGE_DRIVE_NOT_FOUND = "status.storage.drive.not_found";
    private static final String I18N_COMMON_YES = "common.yes";
    private static final String I18N_COMMON_NO = "common.no";

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
    private final MediaViewerService mediaViewerService;
    private final DevOtpGuardService devOtpGuardService;
    private final NavigationIntentService navigationIntentService;
    private final DeviceListState deviceListState;

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
    private Button btnImportPatch;
    @FXML
    private Button btnCreatePatch;
    @FXML
    private VBox noticeContainer;
    @FXML
    private HBox statusBar;
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
    @FXML
    private Button btnLogout;
    @FXML
    private Button btnLogin;

    private DashboardController currentDashboardController;
    private GuestDashboardController guestDashboardController;
    private PreLoginSettingsPopupHelper settingsPopupHelper;
    private final Map<String, Alert> activeAlertsByHardwareId = new HashMap<>();
    private final Set<String> pendingStorageSyncs = new HashSet<>();
    private final Queue<FailureSummaryRequestedEvent> pendingFailureSummaries = new ArrayDeque<>();
    private boolean failureSummaryVisible;

    public AdminLayoutController(ViewLoader viewLoader,
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
            StorageUnavailableEventHandler storageUnavailableEventHandler,
            MediaViewerService mediaViewerService,
            DevOtpGuardService devOtpGuardService,
            NavigationIntentService navigationIntentService,
            DeviceListState deviceListState) {
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
        this.mediaViewerService = mediaViewerService;
        this.devOtpGuardService = devOtpGuardService;
        this.navigationIntentService = navigationIntentService;
        this.deviceListState = deviceListState;
    }

    @Override
    protected StackPane getContentArea() {
        return contentArea;
    }

    @Override
    protected List<Button> getMenuButtons() {
        if (session.isDev()) {
            return List.of(btnImportPatch, btnCreatePatch);
        }
        return List.of(btnDashboard, btnUser);
    }

    @FXML
    public void initialize() {
        configureRoleVisibility();

        if (session.isGuest()) {
            currentDashboardController = null;
            appUpdateController.setOnStatusChange(null);
            appUpdateController.checkOnStartup();
        } else {
            guestDashboardController = null;
        }

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

        labelGreeting.setText(!session.isGuest() ?
                I18n.get("top.hello", session.getUser().getUsername()) : I18n.get("top.hello.guest"));

        appNoticeService.bindNoticeContainer(noticeContainer);

        deviceMiniStatus.setOnProgressChanged(this::refreshDashboardIfActive);

        devOtpGuardService.start(this::logout);
        setIconNte();
        openDefaultTab();
        if (!session.isDev()) {
            refreshStorageStatus();
        }
        updateAuthButtons();
    }

    private void updateAuthButtons() {
        boolean guest = session.isGuest();

        btnLogin.setVisible(guest);
        btnLogin.setManaged(guest);

        btnLogout.setVisible(!guest);
        btnLogout.setManaged(!guest);
    }

    public void setIconNte() {
        Image image = new Image(
                Objects.requireNonNull(getClass()
                                .getResource("/image/NTE_Logo.png"))
                        .toExternalForm());
        nteIcon.setImage(image);
        nteIcon.setFitWidth(150);
        nteIcon.setFitHeight(150);
        nteIcon.setPreserveRatio(true);
    }

    private void configureRoleVisibility() {
        boolean isDev = session.isDev();
        setVisibleManaged(btnDashboard, !isDev);
        setVisibleManaged(btnUser, !isDev);
        setVisibleManaged(btnImportPatch, isDev);
        setVisibleManaged(btnCreatePatch, isDev);
        setVisibleManaged(btnSettings, true);
        setVisibleManaged(statusBar, !isDev);
    }

    private void setVisibleManaged(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    @FXML
    public void goDashboard() {
        if (session.isGuest()) {
            navigationIntentService.setPendingTarget(NavigationTarget.DASHBOARD);
            showLoginPopup();
            return;
        }
        if (session.isDev()) {
            goImportPatch();
            return;
        }
        var result = loadViewWithController(ViewPaths.ADMIN_DASHBOARD, DashboardController.class);
        if (result != null) {
            currentDashboardController = result.controller();
            currentDashboardController.setOnRequestValidate(this::handleRequestValidate);
            currentDashboardController.setOnRequestSync(this::handleRequestSync);
            dataSyncService.setOnUserAutoCreated(username -> Platform.runLater(() -> {
                if (currentDashboardController != null) {
                    currentDashboardController.onUserAutoCreated();
                }
            }));
            setContent(result.node());
        }
        setActiveButton(getMenuButtons(), btnDashboard);
        Platform.runLater(this::showPendingUnvalidatedDevices);
    }

    private void handleRequestValidate(DeviceSummary summary) {
        if (summary == null || summary.getValidationResult() == null) {
            showNoticeError(I18n.get("device.validation.failed"));
            return;
        }
        if (session.isGuest()) {
            return;
        }
        if (!session.isAdmin()) {
            showContactAdminToSaveDeviceDialog(summary.getValidationResult());
            return;
        }
        showSaveDeviceConfirmation(summary.getValidationResult());
    }

    private void handleRequestSync(DeviceSummary summary) {
        if (summary == null) {
            showNoticeError(I18n.get(I18N_DEVICE_SYNC_QUEUE_FAILED));
            return;
        }
        // Don't show sync dialog if device is no longer connected
        if (!summary.isConnected()) {
            return;
        }
        if (session.isGuest()) {
            return;
        }
        showSyncConfirmation(summary.getHardwareId(), summary.getDeviceName(), summary.getCameraId());
    }

    @FXML
    private void goUser() {
        if (session.isGuest()) {
            navigationIntentService.setPendingTarget(NavigationTarget.USER);
            showLoginPopup();
            return;
        }
        if (session.isDev()) {
            goImportPatch();
            return;
        }
        if (currentDashboardController != null) {
            currentDashboardController.resetFileSelectionState();
        }
        // Reuse the same tab entry point and only change loaded content by role.
        setActiveButton(getMenuButtons(), btnUser);
        if (session.isAdmin()) {
            setContent(loadView(ViewPaths.USER_LIST));
        } else {
            openMyProfile();
        }
    }

    @FXML
    private void goImportPatch() {
        if (!session.isDev()) {
            goDashboard();
            return;
        }
        currentDashboardController = null;
        setContent(loadView(ViewPaths.IMPORT_PATCH_PAGE));
        setActiveButton(getMenuButtons(), btnImportPatch);
    }

    @FXML
    private void goCreatePatch() {
        if (!session.isDev()) {
            goDashboard();
            return;
        }
        if (!session.isCreatePatchUnlocked()) {
            boolean unlocked = devOtpGuardService.promptCreatePatchUnlock();
            if (!unlocked) {
                return;
            }
        }
        currentDashboardController = null;
        setContent(loadView(ViewPaths.CREATE_PATCH_PAGE));
        setActiveButton(getMenuButtons(), btnCreatePatch);
    }

    @FXML
    public void showLoginPopup() {
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

    @FXML
    public void logout() {
        log.info("User {} is logging out", session.getUser().getUsername());
        devOtpGuardService.stop();
        mediaViewerService.close();
        if (currentDashboardController != null) {
            currentDashboardController.closeQueueDialog();
            currentDashboardController.setOnRequestValidate(null);
            currentDashboardController.setOnRequestValidate(null);
        }

        failureSummaryVisible = false;

        session.clear();
        MainApp.showAdmin();
    }

    public void showGuestDashboard() {
        var result = loadViewWithController(ViewPaths.GUEST_DASHBOARD, GuestDashboardController.class);
        if (result != null) {
            guestDashboardController = result.controller();
            setContent(result.node());
        }
    }

    @FXML
    private void openSettingsPopup() {
        if (session.isGuest()) {
            settingsPopupHelper.togglePopup();
            return;
        }
        if (session.isDev()) {
            openDevSettingsDialog();
            return;
        }
        if (!session.isAdmin()) {
            openUserSettingsDialog();
            return;
        }

        openAdminSettingsDialog();
    }

    private void openAdminSettingsDialog() {
        DialogHelper.Dialog<AdminSettingsDialogController> dialog = DialogHelper.createDialog(
                ViewPaths.ADMIN_SETTINGS_DIALOG,
                "⚙ " + I18n.get(I18N_SETTINGS_TITLE));
        dialog.controller().setOnLanguageChangedAction(this::reloadUI);
        configureSettingsDialogStage(dialog.stage());
    }

    private void openUserSettingsDialog() {
        DialogHelper.Dialog<UserSettingsDialogController> dialog = DialogHelper.createDialog(
                ViewPaths.USER_SETTINGS_DIALOG,
                "⚙ " + I18n.get(I18N_SETTINGS_TITLE));
        dialog.controller().setOnLanguageChangedAction(this::reloadUI);
        configureSettingsDialogStage(dialog.stage());
    }

    private void openDevSettingsDialog() {
        DialogHelper.Dialog<DevSettingsDialogController> dialog = DialogHelper.createDialog(
                ViewPaths.DEV_SETTINGS_DIALOG,
                "⚙ " + I18n.get(I18N_SETTINGS_TITLE));
        dialog.controller().setOnLanguageChangedAction(this::reloadUI);
        configureSettingsDialogStage(dialog.stage());
    }

    private void configureSettingsDialogStage(Stage stage) {
        stage.setResizable(false);
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        stage.setMinWidth(650);
        stage.setMaxHeight(screen.getHeight() * 0.80);
        stage.setMaxWidth(screen.getWidth() * 0.50);
        stage.showAndWait();
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
        appNoticeService.showSuccess(text);
    }

    @Override
    public void showNoticeError(String text) {
        appNoticeService.showError(text);
    }

    /**
     * Queues failure-summary dialogs so sync/export results are shown one at a
     * time instead of stacking multiple modal windows.
     */
    @EventListener
    public void onFailureSummaryRequested(FailureSummaryRequestedEvent event) {
        if (event == null || event.getRows().isEmpty() || session.getUser() == null) {
            return;
        }
        Platform.runLater(() -> {
            if (session.getUser() == null) {
                return;
            }
            pendingFailureSummaries.add(event);
            showNextFailureSummaryIfIdle();
        });
    }

    private void showNextFailureSummaryIfIdle() {
        if (session.getUser() == null) {
            pendingFailureSummaries.clear();
            return;
        }
        if (failureSummaryVisible) {
            return;
        }

        FailureSummaryRequestedEvent event = pendingFailureSummaries.poll();
        if (event == null) {
            return;
        }

        failureSummaryVisible = true;
        try {
            AlertHelper.DialogText dialogText = new AlertHelper.DialogText(
                    event.getTitle(), event.getHeader(), event.getContent());
            AlertHelper.TableColumns columns = new AlertHelper.TableColumns(
                    event.getFirstColumnName(), "fileName",
                    event.getSecondColumnName(), "reason");
            ButtonType retryButton = new ButtonType(I18n.get("common.retry"), ButtonBar.ButtonData.OK_DONE);
            ButtonType closeButton = new ButtonType(I18n.get("common.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
            AlertHelper.showAlertWithTableNow(dialogText, event.getRows(), columns, retryButton, closeButton,
                    event.getPrimaryAction());
        } finally {
            failureSummaryVisible = false;
            // Continue draining queued summaries after the current modal closes.
            showNextFailureSummaryIfIdle();
        }
    }

    // Handle device connection/disconnection events from DeviceTracker
    @EventListener
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
            return;
        }

        if (session.isAdmin()) {
            refreshDashboardIfActive();
            showSaveDeviceConfirmation(result);
        }
    }

    private void showContactAdminToSaveDeviceDialog(DeviceValidationResult result) {
        Alert info = AlertHelper.createInformation(
                I18n.get("device.save.contact_admin.title"),
                I18n.get("device.save.contact_admin.header"),
                I18n.get("device.save.contact_admin.content",
                        result.getCameraId(),
                        result.getMatchedModelName()));
        ButtonType closeButton = new ButtonType(I18n.get("common.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertHelper.setButtons(info, closeButton);
        info.showAndWait();
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

        ButtonType yesButton = new ButtonType(I18n.get(I18N_COMMON_YES), ButtonBar.ButtonData.YES);
        ButtonType noButton = new ButtonType(I18n.get(I18N_COMMON_NO), ButtonBar.ButtonData.NO);
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
        registerDeviceForSync(cameraId);
        refreshDashboardIfActive();
    }

    private void showSyncConfirmation(String hardwareId, String deviceName, String cameraId) {
        // Check if camera is already in sync queue.
        if (deviceSyncQueue.isInQueue(cameraId)) {
            showNoticeSuccess(I18n.get(I18N_DEVICE_SYNC_QUEUED, deviceName));
            return;
        }

        boolean autoDelete = adminSettingsService.getAutoDelete();
        String contentKey = autoDelete ? "device.sync.content.autodelete" : "device.sync.content.keep";

        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("device.sync.title"),
                I18n.get("device.sync.header", deviceName),
                I18n.get(contentKey));

        ButtonType yesButton = new ButtonType(I18n.get(I18N_COMMON_YES), ButtonBar.ButtonData.YES);
        ButtonType noButton = new ButtonType(I18n.get(I18N_COMMON_NO), ButtonBar.ButtonData.NO);
        AlertHelper.setButtons(confirm, yesButton, noButton);

        registerAlert(hardwareId, confirm);
        try {
            Optional<ButtonType> chosen = confirm.showAndWait();
            if (chosen.isEmpty() || chosen.get() != yesButton) {
                return;
            }

            // Move from pending storage retry list to the active sync queue when present.
            pendingStorageSyncs.remove(cameraId);
            registerDeviceForSync(cameraId);
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

        boolean queued = deviceSyncQueue.add(new SyncContext(folderManagerService.getSyncDir(),
                autoDelete, deviceName, hardwareId, cameraId));
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

    private void openDefaultTab() {
        if (session.isGuest()) {
            showGuestDashboard();
            return;
        }
        if (session.isDev()) {
            goImportPatch();
            return;
        }
        NavigationTarget target = navigationIntentService.consumePendingTarget();
        Platform.runLater(() -> {
            if (target == NavigationTarget.USER) {
                goUser();
            } else {
                goDashboard();
            }
        });
    }

    @Override
    protected Button getButtonForModule(String fxml) {
        // Map each module's FXML path to its sidebar nav button so reloadUI() can
        // restore the correct active-button highlight after a language change reload.
        return switch (fxml) {
            case ViewPaths.USER_LIST, ViewPaths.USER_INFO -> btnUser;
            case ViewPaths.IMPORT_PATCH_PAGE -> btnImportPatch;
            case ViewPaths.CREATE_PATCH_PAGE -> btnCreatePatch;
            default -> null;
        };
    }

    private void refreshDashboardIfActive() {
        if (currentDashboardController != null) {
            currentDashboardController.refresh();
        } else if (guestDashboardController != null) {
            guestDashboardController.refresh();
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
        if (session.isDev()) {
            return;
        }
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

    private void showPendingUnvalidatedDevices() {
        deviceListState.getDeviceItems().stream()
                .filter(summary -> summary.getStatus() == DeviceSummary.Status.UNVALIDATED)
                .map(DeviceSummary::getValidationResult)
                .filter(Objects::nonNull)
                .forEach(session.isAdmin() ? this::showSaveDeviceConfirmation : this::showContactAdminToSaveDeviceDialog);
    }
}
