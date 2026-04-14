package com.app.admin.layout.controllers;

import com.app.MainApp;
import com.app.common.configs.LogContext;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.dtos.DeviceValidationResult;
import com.app.common.models.ValidatedDevice;
import com.app.common.services.DeviceValidationService;
import com.app.common.dtos.SyncContext;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.services.UserSettingService;
import com.app.common.definitions.ViewPaths;
import com.app.admin.usermanagement.controllers.UserEditFormController;
import com.app.common.modules.settingspopup.helpers.SettingsPopupHelper;
import com.app.common.helpers.AlertHelper;
import com.app.common.services.AppNoticeService;
import com.app.user.userdetail.controllers.UserInfoController;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.ViewLoader;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

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

    public AdminLayoutController(ViewLoader viewLoader,
            AppUpdateController appUpdateController,
            UserSettingService userSettingService,
            Session session,
            DeviceValidationService deviceValidationService,
            AppNoticeService appNoticeService,
            DeviceSyncQueue deviceSyncQueue) {
        super(viewLoader);
        this.appUpdateController = appUpdateController;
        this.userSettingService = userSettingService;
        this.session = session;
        this.deviceValidationService = deviceValidationService;
        this.appNoticeService = appNoticeService;
        this.deviceSyncQueue = deviceSyncQueue;
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

        startDeviceWatcher();
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
        stopDeviceWatcher();
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

    private void startDeviceWatcher() {
        if (deviceWatchExecutor != null && !deviceWatchExecutor.isShutdown()) {
            return;
        }

        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "camera-listener");
            t.setDaemon(true);
            return t;
        };

        deviceWatchExecutor = Executors.newSingleThreadScheduledExecutor(factory);
        deviceWatchExecutor.scheduleWithFixedDelay(this::pollDeviceConnections, 0, 3, TimeUnit.SECONDS);
    }

    private void stopDeviceWatcher() {
        if (deviceWatchExecutor == null) {
            return;
        }

        deviceWatchExecutor.shutdownNow();
        deviceWatchExecutor = null;
        connectedSerialsSnapshot.clear();
    }

    private void pollDeviceConnections() {
        LogContext.init();
        try {
            List<String> currentSerials = deviceValidationService.listConnectedSerials();
            Set<String> current = new HashSet<>(currentSerials);

            for (String serial : current) {
                if (!connectedSerialsSnapshot.contains(serial)) {
                    validateAndHandleConnectedSerial(serial);
                }
            }

            connectedSerialsSnapshot.clear();
            connectedSerialsSnapshot.addAll(current);
            adbMissingLogged = false;
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("ADB binary not found")) {
                if (!adbMissingLogged) {
                    adbMissingLogged = true;
                    log.warn("Device watcher is disabled until adb.exe is bundled: {}", e.getMessage());
                }
                return;
            }
            log.warn("Device watcher poll failed: {}", e.getMessage());
        } finally {
            LogContext.clear();
        }
    }

    private void validateAndHandleConnectedSerial(String serial) {
        try {
            DeviceValidationResult result = deviceValidationService.validateConnectedDevice(serial);
            if (!result.isValid()) {
                return;
            }

            Platform.runLater(() -> handleValidatedResult(result));
        } catch (Exception e) {
            log.warn("Device validation failed for serial {}: {}", serial, e.getMessage());
        }
    }

    private void handleValidatedResult(DeviceValidationResult result) {
        if (result.isAlreadySaved()) {
            // Update last_seen_at for already saved devices
            deviceValidationService.saveValidatedDevice(
                    result.getAccountUserId(),
                    result.getHardwareId(),
                    result.getMatchedWhitelistId());
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
}
