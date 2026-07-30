package com.app.admin.layout.controllers;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import com.app.auth.login.controllers.LoginController;
import com.app.common.definitions.enums.DeviceEventType;
import com.app.common.definitions.enums.DeviceStatus;
import com.app.common.definitions.enums.NavigationTarget;
import com.app.common.modules.externalmediadecrypt.controllers.ExternalMediaDecryptController;
import com.app.common.modules.preloginsettingspopup.helpers.PreLoginSettingsPopupHelper;
import com.app.guest.controllers.GuestDashboardController;
import com.app.guest.services.NavigationIntentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.app.MainApp;
import com.app.admin.layout.services.StorageUnavailableEventHandler;
import com.app.admin.settingsdialog.controllers.AdminSettingsDialogController;
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.common.modules.datarestore.services.RestoreService;
import com.app.auth.totp.services.DevOtpGuardService;
import com.app.common.definitions.ViewPaths;
import com.app.common.definitions.enums.FolderType;
import com.app.common.modules.device.dtos.DeviceSummary;
import com.app.common.modules.device.dtos.DeviceValidationResult;
import com.app.common.dtos.SyncContext;
import com.app.common.modules.device.events.DeviceEvent;
import com.app.common.events.FailureSummaryRequestedEvent;
import com.app.common.helpers.AlertHelper;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.ViewLoader;
import com.app.common.helpers.WarningPopupHelper;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.admin.devicemanagement.services.DeviceManagementService;
import com.app.common.modules.databackup.events.FileBackupCompletedEvent;
import com.app.common.modules.datasync.events.FileSyncCompletedEvent;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.foldermanager.events.StorageRecoveryCompletedEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.foldermanager.services.StorageHealthMonitor;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.media.services.MediaViewerService;
import com.app.common.modules.session.Session;
import com.app.common.services.AppNoticeService;
import com.app.common.modules.device.services.DeviceTracker;
import com.app.common.modules.device.services.DeviceValidationService;
import com.app.common.modules.device.services.DeviceListState;
import com.app.common.utils.DateTimeUtil;
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
import javafx.util.Duration;

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
    private static final double WARNING_POPUP_GAP = 10;
    private static final Duration WARNING_POPUP_FADE_IN = Duration.millis(200);
    private static final Duration WARNING_POPUP_VISIBLE_DURATION = Duration.seconds(1.0);
    private static final Duration WARNING_POPUP_FADE_OUT = Duration.millis(200);

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
    private final FolderManagerService folderManagerService;
    private final StorageHealthMonitor storageHealthMonitor;
    private final RestoreService restoreService;
    private final AdminSettingsDialogService adminSettingsService;
    private final StorageUnavailableEventHandler storageUnavailableEventHandler;
    private final MediaViewerService mediaViewerService;
    private final DevOtpGuardService devOtpGuardService;
    private final NavigationIntentService navigationIntentService;
    private final DeviceListState deviceListState;
    private final DeviceManagementService deviceManagementService;

    @FXML
    private StackPane contentArea;
    @FXML
    private Label labelGreeting;
    @FXML
    private Label labelUsername;
    @FXML
    private HBox userProfileBox;
    @FXML
    private Button btnSettings;
    @FXML
    private Button btnDashboard;
    @FXML
    private Button btnUser;
    @FXML
    private Button btnDevice;
    @FXML
    private Button btnDecrypt;
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
    private Label lblAppVersion;
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
    private ImageView iconDashboard;
    @FXML
    private ImageView iconUser;
    @FXML
    private ImageView iconDevice;
    @FXML
    private ImageView iconDecrypt;
    @FXML
    private ImageView iconImportPatch;
    @FXML
    private ImageView iconCreatePatch;
    @FXML
    private ImageView iconSettings;
    @FXML
    private ImageView iconLogout;
    @FXML
    private ImageView iconLogin;
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
    private boolean shouldShowPendingValidateDevice = true;
    private WarningPopupHelper warningPopupHelper;

    public AdminLayoutController(ViewLoader viewLoader,
            AppUpdateController appUpdateController,
            Session session,
            @Lazy DeviceValidationService deviceValidationService,
            AppNoticeService appNoticeService,
            @Lazy DeviceSyncQueue deviceSyncQueue,
            @Lazy DeviceTracker deviceTracker,
            @Lazy FolderManagerService folderManagerService,
            @Lazy StorageHealthMonitor storageHealthMonitor,
            @Lazy RestoreService restoreService,
            @Lazy AdminSettingsDialogService adminSettingsService,
            @Lazy StorageUnavailableEventHandler storageUnavailableEventHandler,
            @Lazy MediaViewerService mediaViewerService,
            @Lazy DevOtpGuardService devOtpGuardService,
            NavigationIntentService navigationIntentService,
            DeviceListState deviceListState,
            @Lazy DeviceManagementService deviceManagementService) {
        super(viewLoader);
        this.appUpdateController = appUpdateController;
        this.session = session;
        this.deviceValidationService = deviceValidationService;
        this.appNoticeService = appNoticeService;
        this.deviceSyncQueue = deviceSyncQueue;
        this.deviceTracker = deviceTracker;
        this.folderManagerService = folderManagerService;
        this.storageHealthMonitor = storageHealthMonitor;
        this.restoreService = restoreService;
        this.adminSettingsService = adminSettingsService;
        this.storageUnavailableEventHandler = storageUnavailableEventHandler;
        this.mediaViewerService = mediaViewerService;
        this.devOtpGuardService = devOtpGuardService;
        this.navigationIntentService = navigationIntentService;
        this.deviceListState = deviceListState;
        this.deviceManagementService = deviceManagementService;
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
        if (session.isAdmin()) {
            return List.of(btnDashboard, btnUser, btnDevice, btnDecrypt);
        }
        if (session.isGuest()) {
            return List.of(btnDashboard, btnDecrypt);
        }
        return List.of(btnDashboard, btnUser, btnDecrypt);

    }

    @FXML
    public void initialize() {
        configureRoleVisibility();

        if (session.isGuest()) {
            currentDashboardController = null;
        } else {
            guestDashboardController = null;
        }

        // Keep header buttons responsive while update checks are running.
        appUpdateController.setOnCheckStart(() -> btnSettings.setDisable(true));
        appUpdateController.setOnCheckEnd(() -> btnSettings.setDisable(false));

        settingsPopupHelper = new PreLoginSettingsPopupHelper(
                "guest",
                btnSettings,
                PreLoginSettingsPopupHelper.PopupAnchorY.BOTTOM,
                null,
                this::reloadUI,
                appUpdateController::onCheckUpdateManual,
                null);
        settingsPopupHelper.initialize();

        if (session.isGuest()) {
            userProfileBox.setVisible(false);
            userProfileBox.setManaged(false);
        } else {
            labelGreeting.setText(I18n.get("top.hello.guest"));
            labelUsername.setText(session.getUser().getUsername());
        }

        appNoticeService.bindNoticeContainer(noticeContainer);
        configureWarningPopup();
        updateAppVersionLabel();

        devOtpGuardService.start(this::logout);
        setIconNte();
        setNavIcons();
        openDefaultTab();
        refreshStorageAfterLayoutLoad();
        updateAuthButtons();
    }

    private void refreshStorageAfterLayoutLoad() {
        if (session.isDev()) {
            return;
        }

        refreshStorageStatus();
        if (!session.isGuest()) {
            Platform.runLater(() -> storageHealthMonitor.checkNow(null));
        }
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

    private void setNavIcons() {
        setIcon(iconDashboard, "/image/control.png");
        setIcon(iconUser, "/image/user.png");
        setIcon(iconDevice, "/image/device.png");
        setIcon(iconDecrypt, "/image/decrypt.png");
        setIcon(iconImportPatch, "/image/import-patch.png");
        setIcon(iconCreatePatch, "/image/export-patch.png");
        setIcon(iconSettings, "/image/setting.png");
        setIcon(iconLogout, "/image/logout.png");
        setIcon(iconLogin, "/image/login.png");
    }

    private void setIcon(ImageView view, String resourcePath) {
        URL url = getClass().getResource(resourcePath);
        if (url == null) {
            log.warn("Icon resource not found: {}", resourcePath);
            return;
        }
        view.setImage(new Image(url.toExternalForm()));
        view.setFitWidth(40);
        view.setFitHeight(40);
        view.setPreserveRatio(true);
    }

    private void configureRoleVisibility() {
        boolean isDev = session.isDev();
        setVisibleManaged(btnDashboard, !isDev);
        setVisibleManaged(btnDecrypt, !isDev);
        setVisibleManaged(btnUser, !isDev && !session.isGuest());
        setVisibleManaged(btnDevice, !isDev && session.isAdmin());
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

    private void configureWarningPopup() {
        if (lblCombinedWarning == null) {
            return;
        }

        warningPopupHelper = new WarningPopupHelper(
                lblCombinedWarning,
                WARNING_POPUP_GAP,
                WARNING_POPUP_FADE_IN,
                WARNING_POPUP_VISIBLE_DURATION,
                WARNING_POPUP_FADE_OUT);
        warningPopupHelper.initialize();
    }

    private void updateAppVersionLabel() {
        Platform.runLater(() -> {
            if (lblAppVersion != null) {
                lblAppVersion.setText(I18n.get("setting.startWithWindows.checkversion",
                        AdminSettingsDialogController.getVersionCurrent()));
            }
        });
    }

    @FXML
    public void goDashboard() {
        if (session.isGuest()) {
            navigationIntentService.setPendingTarget(NavigationTarget.DASHBOARD);
            showGuestDashboard();
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
            setContent(result.node());
        }
        setActiveButton(getMenuButtons(), btnDashboard);
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
        showSyncConfirmation(summary.getHardwareId(), summary.getDeviceName(), summary.getCameraId());
    }

    @FXML
    private void goUser() {
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
    private void goDevice() {
        if (session.isGuest()) {
            showLoginPopup();
            return;
        }
        if (!session.isAdmin()) {
            goDashboard();
            return;
        }
        if (currentDashboardController != null) {
            currentDashboardController.resetFileSelectionState();
        }
        setActiveButton(getMenuButtons(), btnDevice);
        setContent(loadView(ViewPaths.DEVICE_LIST));
    }

    @FXML
    private void goDecrypt() {
        if (session.isGuest()) {
            navigationIntentService.setPendingTarget(NavigationTarget.EXTERNAL_MEDIA_DECRYPT);
        }
        if (currentDashboardController != null) {
            currentDashboardController.resetFileSelectionState();
        }
        setActiveButton(getMenuButtons(), btnDecrypt);
        var result = loadViewWithController(ViewPaths.EXTERNAL_MEDIA_DECRYPT, ExternalMediaDecryptController.class);
        if (result == null) {
            log.error("Failed to load encrypt-video view");
            return;
        }
        setContent(result.node());
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
                "BDMA");
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
            currentDashboardController.cleanup();
            currentDashboardController.setOnRequestValidate(null);
            currentDashboardController.setOnRequestSync(null);
        }
        failureSummaryVisible = false;
        shouldShowPendingValidateDevice = true;
        session.clear();
        MainApp.showAdmin();
    }

    public void showGuestDashboard() {
        var result = loadViewWithController(ViewPaths.GUEST_DASHBOARD, GuestDashboardController.class);
        if (result != null) {
            guestDashboardController = result.controller();
            guestDashboardController.setOnRequestSync(this::handleRequestSync);
            setContent(result.node());
        }
        setActiveButton(getMenuButtons(), btnDashboard);
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
                I18n.get(I18N_SETTINGS_TITLE));
        dialog.controller().setOnLanguageChangedAction(this::reloadUI);
        configureSettingsDialogStage(dialog.stage());
    }

    private void openUserSettingsDialog() {
        DialogHelper.Dialog<UserSettingsDialogController> dialog = DialogHelper.createDialog(
                ViewPaths.USER_SETTINGS_DIALOG,
                I18n.get(I18N_SETTINGS_TITLE));
        dialog.controller().setOnLanguageChangedAction(this::reloadUI);
        configureSettingsDialogStage(dialog.stage());
    }

    private void openDevSettingsDialog() {
        DialogHelper.Dialog<DevSettingsDialogController> dialog = DialogHelper.createDialog(
                ViewPaths.DEV_SETTINGS_DIALOG,
                I18n.get(I18N_SETTINGS_TITLE));
        dialog.controller().setOnLanguageChangedAction(this::reloadUI);
        configureSettingsDialogStage(dialog.stage());
    }

    private void configureSettingsDialogStage(Stage stage) {
        stage.setResizable(false);
        stage.setMinWidth(650);
        stage.setOnShowing(event -> {
            stage.setMaxWidth(Double.MAX_VALUE);
            stage.setMaxHeight(Double.MAX_VALUE);
        });
        stage.setOnShown(event -> Platform.runLater(() -> {
            Rectangle2D bounds = Screen.getScreensForRectangle(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()
            ).get(0).getVisualBounds();
            double maxH = bounds.getHeight() * 0.80;
            double maxW = bounds.getWidth() * 0.50;
            if (stage.getWidth() > maxW) stage.setWidth(maxW);
            if (stage.getHeight() > maxH) stage.setHeight(maxH);
            stage.setMaxWidth(maxW);
            stage.setMaxHeight(maxH);
        }));
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
        if (event == null || event.getRows().isEmpty()) {
            return;
        }
        Platform.runLater(() -> {
            pendingFailureSummaries.add(event);
            showNextFailureSummaryIfIdle();
        });
    }

    private void showNextFailureSummaryIfIdle() {
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
        if (event.type() == DeviceEventType.CONNECTED) {
            Platform.runLater(() -> handleValidatedResult(event.validationResult()));
        } else if (event.type() == DeviceEventType.DISCONNECTED) {
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

            if (!deviceManagementService.isActive(cameraId)) {
                handleDeactivatedDeviceConnection(result);
                return;
            }

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

    private void handleDeactivatedDeviceConnection(DeviceValidationResult result) {
        deviceListState.setDeviceActive(result.getCameraId(), false);
        if (!session.isAdmin()) {
            return;
        }

        Optional<ValidatedDevice> savedDevice = deviceValidationService.findValidatedDevice(result.getCameraId());
        if (savedDevice.isEmpty()) {
            return;
        }

        ValidatedDevice device = savedDevice.get();
        var latestDeactivation = deviceManagementService.findLatestDeactivation(device.getId());
        String deactivatedAt = latestDeactivation
                .map(history -> DateTimeUtil.formatSqliteDateTimeForDisplay(
                        history.getChangedAt(),
                        I18n.get("common.unknown")))
                .orElse(I18n.get("common.unknown"));
        String changedBy = latestDeactivation
                .flatMap(deviceManagementService::findOtherDeactivationAdmin)
                .map(username -> " " + I18n.get("device.deactivated.by", username))
                .orElse("");

        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("device.deactivated.connected.title"),
                I18n.get("device.deactivated.connected.header", device.getDeviceName()),
                I18n.get("device.deactivated.connected.content", result.getCameraId(),
                        deactivatedAt) + changedBy);
        ButtonType reactivateButton = new ButtonType(I18n.get("device.action.reactivate"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType keepButton = new ButtonType(I18n.get("device.action.keepDeactivated"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertHelper.setButtons(confirm, reactivateButton, keepButton);

        registerAlert(result.getHardwareId(), confirm);
        Optional<ButtonType> chosen;
        try {
            chosen = confirm.showAndWait();
        } finally {
            unregisterAlert(result.getHardwareId(), confirm);
        }
        if (chosen.isEmpty() || chosen.get() != reactivateButton) {
            return;
        }

        try {
            deviceManagementService.reactivate(device.getId());
            deviceListState.setDeviceActive(result.getCameraId(), true);
            refreshDashboardIfActive();
            registerDeviceForSync(result.getCameraId());
        } catch (Exception ex) {
            log.error("Failed to reactivate deactivated device {} after connection", result.getCameraId(), ex);
            showNoticeError(I18n.get("device.toggle.error"));
        }
    }

    private void showContactAdminToSaveDeviceDialog(DeviceValidationResult result) {
        Alert info = AlertHelper.createInformation(
                I18n.get("device.save.contact_admin.title"),
                I18n.get("device.save.contact_admin.header"),
                I18n.get("device.save.contact_admin.content", result.getCameraId()));
        ButtonType closeButton = new ButtonType(I18n.get("common.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertHelper.setButtons(info, closeButton);
        info.showAndWait();
    }

    public void showSaveDeviceConfirmation(DeviceValidationResult result) {
        if (result == null || !result.isValid()) {
            showNoticeError(I18n.get("device.validation.failed"));
            return;
        }

        String cameraId = result.getCameraId();
        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("device.save.title"),
                I18n.get("device.save.header"),
                I18n.get("device.save.content", cameraId));

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
        deviceListState.markDeviceSaved(result, saved.getDeviceName());
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
        if (!device.isActive()) {
            log.warn("Cannot queue sync because camera {} is deactivated", cameraId);
            showNoticeError(I18n.get(I18N_DEVICE_SYNC_QUEUE_FAILED));
            return;
        }
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
        boolean deleteEmptyDateFolders = autoDelete && adminSettingsService.getDeleteEmptyDateFolders();

        boolean queued = deviceSyncQueue.add(new SyncContext(folderManagerService.getSyncDir(),
                autoDelete, deleteEmptyDateFolders, deviceName, hardwareId, cameraId));
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
        NavigationTarget target = navigationIntentService.consumePendingTarget();
        if (target == NavigationTarget.EXTERNAL_MEDIA_DECRYPT && !session.isDev()) {
            goDecrypt();
        } else {
            goDashboard();
        }
        Platform.runLater(this::showPendingUnvalidatedDevices);
    }

    @Override
    protected Button getButtonForModule(String fxml) {
        // Map each module's FXML path to its sidebar nav button so reloadUI() can
        // restore the correct active-button highlight after a language change reload.
        return switch (fxml) {
            case ViewPaths.USER_LIST, ViewPaths.USER_INFO -> btnUser;
            case ViewPaths.DEVICE_LIST -> btnDevice;
            case ViewPaths.EXTERNAL_MEDIA_DECRYPT -> btnDecrypt;
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
        refreshStorageStatus();
    }

    @EventListener(FileBackupCompletedEvent.class)
    public void onFileBackupCompleted() {
        refreshStorageStatus();
    }

    public void refreshStorageStatus() {
        if (session.isDev()) {
            return;
        }
        Platform.runLater(() -> {
            File syncDir = folderManagerService.getSyncDir();
            File backupDir = folderManagerService.getBackupDir();

            boolean sameParent = isSameParentFolder(syncDir, backupDir);

            String warningText = sameParent ? I18n.get("setting.storage.warn.same_drive") : "";

            int syncState = updateStorageBar(STATUS_SYNC_DRIVE, pbDataStorage,
                    lblDataStorageTitle, lblDataStoragePercent, lblDataStorageUsage, syncDir);
            int backupState = updateStorageBar(STATUS_BACKUP_DRIVE, pbBackupStorage,
                    lblBackupStorageTitle, lblBackupStoragePercent, lblBackupStorageUsage, backupDir);
            for (String storageWarning : resolveStorageWarningKeys(syncState, backupState)) {
                String msg = I18n.get(storageWarning);
                warningText = warningText.isEmpty() ? msg : warningText + " | " + msg;
            }

            boolean hasWarning = !warningText.isEmpty();
            if (warningPopupHelper != null) {
                warningPopupHelper.setText(warningText);
            }

            warningStrip.getStyleClass().removeAll("status-warning-strip-active");
            if (hasWarning) {
                warningStrip.getStyleClass().add("status-warning-strip-active");
            }
        });
    }

    private boolean isSameParentFolder(File syncDir, File backupDir) {
        if (syncDir == null || backupDir == null) {
            return false;
        }
        Path syncRoot = Path.of(syncDir.getAbsolutePath()).getRoot();
        Path backupRoot = Path.of(backupDir.getAbsolutePath()).getRoot();
        if (syncRoot == null || backupRoot == null) {
            return false;
        }
        return syncRoot.toString().equalsIgnoreCase(backupRoot.toString());
    }

    private List<String> resolveStorageWarningKeys(int syncState, int backupState) {
        List<String> warnings = new ArrayList<>();

        if (syncState == STORAGE_OK && backupState == STORAGE_OK) {
            return warnings;
        }

        if (syncState == STORAGE_UNAVAILABLE && backupState == STORAGE_UNAVAILABLE) {
            warnings.add("status.storage.both.unavailable");
        } else if (syncState == STORAGE_UNAVAILABLE) {
            warnings.add("status.storage.syncDrive.unavailable");
        } else if (backupState == STORAGE_UNAVAILABLE) {
            warnings.add("status.storage.backupDrive.unavailable");
        }
        if (syncState == STORAGE_CRITICAL && backupState == STORAGE_CRITICAL) {
            warnings.add("status.storage.both.critical");
        } else if (syncState == STORAGE_CRITICAL) {
            warnings.add("status.storage.syncDrive.critical");
        } else if (backupState == STORAGE_CRITICAL) {
            warnings.add("status.storage.backupDrive.critical");
        }

        return warnings;
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
                currentDashboardController.mergeSavedDevices();
                currentDashboardController.refresh();
            }
            refreshStorageStatus();
        });
    }

    private void showPendingUnvalidatedDevices() {
        if (!shouldShowPendingValidateDevice || session.isGuest()) {
            return;
        }

        new ArrayList<>(deviceListState.getDeviceFxItems()).stream()
                .filter(summary -> summary.getStatus() == DeviceStatus.UNVALIDATED)
                .map(DeviceSummary::getValidationResult)
                .filter(Objects::nonNull)
                .forEach(session.isAdmin() ? this::showSaveDeviceConfirmation
                        : this::showContactAdminToSaveDeviceDialog);
        shouldShowPendingValidateDevice = false;
    }

}

