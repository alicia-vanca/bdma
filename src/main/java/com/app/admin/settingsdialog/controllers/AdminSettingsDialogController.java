package com.app.admin.settingsdialog.controllers;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.MainApp;
import com.app.admin.layout.controllers.AdminLayoutController;
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.admin.settingsdialog.services.RestoreService;
import com.app.admin.usermanagement.controllers.UserEditFormController;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.ViewPaths;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.Language;
import com.app.common.definitions.enums.Theme;
import com.app.common.events.ThemeChangedEvent;
import com.app.common.helpers.AlertHelper;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.NoticeStackRenderer;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.modules.databackup.queues.DataBackupQueue;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.foldermanager.events.StorageRecoveryCompletedEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.modules.theme.ThemeManager;
import com.app.common.repositories.RestoreFailureRepository;
import com.app.common.services.DriveResolverService;
import com.app.common.services.UserSettingService;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.Setter;

@Component
public class AdminSettingsDialogController {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsDialogController.class);

    private static final String CSS_CLASS_STATUS_SUCCESS = "status-success";
    private static final String CSS_CLASS_STATUS_ERROR = "status-error";
    private static final String I18N_SETTING_STORAGE_PROGRESS = "setting.storage.progress";
    private static final String I18N_SETTING_STORAGE_FINAL = "setting.storage.final";
    private static final String I18N_SETTING_STORAGE_CHOOSE = "setting.storage.btn.choose";

    private final AdminSettingsDialogService adminSettingsService;
    private final Session session;
    private final UserSettingService userSettingService;
    private final AppUpdateController appUpdateController;
    private final DriveResolverService driveResolverService;
    private final AdminLayoutController adminLayoutController;
    private final ApplicationEventPublisher eventPublisher;
    private final RestoreService restoreService;
    private final DeviceSyncQueue deviceSyncQueue;
    private final DataBackupQueue dataBackupQueue;
    private final RestoreFailureRepository restoreFailureRepository;

    @FXML
    private VBox panel;
    @FXML
    private Label lblLanguage;
    @FXML
    private Button btnEnglish;
    @FXML
    private Button btnVietnamese;
    @FXML
    private Label lblTheme;
    @FXML
    private Button btnLightTheme;
    @FXML
    private Button btnDarkTheme;
    @FXML
    private Button btnInfo;
    @FXML
    private Button btnUpdate;
    @FXML
    private Label lblSaveFolderTitle;
    @FXML
    private TextField txtSavePath;
    @FXML
    private Button btnChooseSaveFolder;
    @FXML
    private Label lblBackupFolderTitle;
    @FXML
    private TextField txtBackupPath;
    @FXML
    private Button btnChooseBackupFolder;
    @FXML
    private Label lblExportFolderTitle;
    @FXML
    private TextField txtExportPath;
    @FXML
    private Button btnChooseExportFolder;
    @FXML
    private CheckBox chkAskEveryTimeExport;
    @FXML
    private Label lblAskEveryTimeExportTitle;
    @FXML
    private Label lblSaveFolderNote;
    @FXML
    private Label lblAutoDeleteDescription;
    @FXML
    private CheckBox chkAutoDelete;
    @FXML
    private Label lblAutoDeleteTitle;
    @FXML
    private Label lblStartWithWindowsDescription;
    @FXML
    private CheckBox chkStartWithWindows;
    @FXML
    private Label lblStartWithWindowsTitle;
    @FXML
    private VBox noticeContainer;
    @FXML
    private Button btnRestore;
    @FXML
    private Label lblStorageProgress;
    @FXML
    private HBox storageActionBox;
    @FXML
    private Button btnRetryFailedRestore;
    @FXML
    private Label lblDriveConflictWarning;

    private NoticeStackRenderer noticeRenderer;
    @Setter
    private Runnable onLanguageChangedAction;
    private ScheduledExecutorService progressScheduler;

    public AdminSettingsDialogController(
            AdminSettingsDialogService adminSettingsService,
            Session session,
            UserSettingService userSettingService,
            AppUpdateController appUpdateController,
            DriveResolverService driveResolverService,
            AdminLayoutController adminLayoutController,
            ApplicationEventPublisher eventPublisher,
            RestoreService restoreService,
            DeviceSyncQueue deviceSyncQueue,
            DataBackupQueue dataBackupQueue,
            RestoreFailureRepository restoreFailureRepository) {
        this.adminSettingsService = adminSettingsService;
        this.session = session;
        this.userSettingService = userSettingService;
        this.appUpdateController = appUpdateController;
        this.driveResolverService = driveResolverService;
        this.adminLayoutController = adminLayoutController;
        this.eventPublisher = eventPublisher;
        this.restoreService = restoreService;
        this.deviceSyncQueue = deviceSyncQueue;
        this.dataBackupQueue = dataBackupQueue;
        this.restoreFailureRepository = restoreFailureRepository;
    }

    @FXML
    public void initialize() {
        noticeRenderer = new NoticeStackRenderer(noticeContainer);

        refreshLocalizedText();
        setupSegmentButtons();
        setupActionButtons();
        loadAdminSettings();
    }

    private void refreshLocalizedText() {
        lblLanguage.setText("🌐 " + I18n.get("settings.language"));
        lblTheme.setText("🎨 " + I18n.get("settings.theme"));

        btnEnglish.setText(I18n.get("settings.language.english"));
        btnVietnamese.setText(I18n.get("settings.language.vietnamese"));
        btnLightTheme.setText("☀ " + I18n.get("settings.theme.light"));
        btnDarkTheme.setText("☾ " + I18n.get("settings.theme.dark"));

        lblSaveFolderTitle.setText(I18n.get("setting.storage.sync.title"));
        btnChooseSaveFolder.setText(I18n.get(I18N_SETTING_STORAGE_CHOOSE));
        lblBackupFolderTitle.setText(I18n.get("setting.storage.backup.title"));
        btnChooseBackupFolder.setText(I18n.get(I18N_SETTING_STORAGE_CHOOSE));
        lblExportFolderTitle.setText(I18n.get("setting.storage.export.title"));
        btnChooseExportFolder.setText(I18n.get(I18N_SETTING_STORAGE_CHOOSE));
        lblAskEveryTimeExportTitle.setText(I18n.get("setting.export.mode.ask.checkbox"));
        lblSaveFolderNote.setText(I18n.get("setting.storage.sync.note"));

        lblDriveConflictWarning.setText(I18n.get("setting.storage.warn.same_drive"));

        lblAutoDeleteDescription.setText(I18n.get("setting.databackup.autodelete.desc"));
        lblAutoDeleteTitle.setText(I18n.get("setting.databackup.autodelete.checkbox"));

        lblStartWithWindowsDescription.setText(I18n.get("setting.startWithWindows.desc"));
        lblStartWithWindowsTitle.setText(I18n.get("setting.startWithWindows.checkbox"));
        btnRestore.setText(I18n.get("setting.storage.btn.restore"));
        btnRetryFailedRestore.setText(I18n.get("setting.storage.btn.retry"));
    }

    // Bind each pair of segment buttons to equal widths within their row.
    private void setupSegmentButtons() {

        setActiveLanguageButton();
        setActiveThemeButton();

        btnEnglish.setOnAction(e -> switchLanguageIfNeeded(AppConstants.LANG_EN));
        btnVietnamese.setOnAction(e -> switchLanguageIfNeeded(AppConstants.LANG_VI));
        btnLightTheme.setOnAction(e -> {
            switchTheme(AppConstants.THEME_LIGHT);
            setActiveThemeButton();
        });
        btnDarkTheme.setOnAction(e -> {
            switchTheme(AppConstants.THEME_DARK);
            setActiveThemeButton();
        });
    }

    private void setupActionButtons() {
        btnInfo.setText(I18n.get("top.info"));
        btnUpdate.setText(I18n.get("top.update"));
        btnInfo.setOnAction(e -> openUserInfo());
        btnUpdate.setOnAction(e -> appUpdateController.onCheckUpdateManual());
    }

    private void loadAdminSettings() {
        adminSettingsService.getFolderPath(FolderType.SYNC).ifPresent(txtSavePath::setText);
        adminSettingsService.getFolderPath(FolderType.BACKUP).ifPresent(txtBackupPath::setText);
        adminSettingsService.getFolderPath(FolderType.EXPORT).ifPresent(txtExportPath::setText);
        chkAskEveryTimeExport.setSelected(adminSettingsService.getAskEveryTimeExport(session.getCurrentUserId()));
        chkAutoDelete.setSelected(adminSettingsService.getAutoDelete());
        chkStartWithWindows.setSelected(adminSettingsService.getStartWithWindows());
        checkAndShowDriveConflict();

        if (restoreService.isCancelled()) {
            hideProgressUI();
            return;
        }

        if (restoreService.isRunning()) {
            setStorageControlsDisabled(true);
            storageActionBox.setVisible(true);
            storageActionBox.setManaged(true);
            btnRetryFailedRestore.setVisible(false);
            btnRetryFailedRestore.setManaged(false);
            lblStorageProgress.setText(I18n.get(I18N_SETTING_STORAGE_PROGRESS,
                    restoreService.getCurrentProgress().buildProgressArgs()));
            startProgressUpdater();
            return;
        }

        if (!restoreFailureRepository.findAll().isEmpty()) {
            storageActionBox.setVisible(true);
            storageActionBox.setManaged(true);
            btnRetryFailedRestore.setVisible(true);
            btnRetryFailedRestore.setManaged(true);

            String saved = adminSettingsService.getLastRestoreProgress();
            if (saved != null && !saved.isBlank()) {
                String[] parts = saved.split(",");
                Object[] args = new Object[]{
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4])
                };
                lblStorageProgress.setText(I18n.get(I18N_SETTING_STORAGE_FINAL, args));
            } else {
                lblStorageProgress.setText(I18n.get("setting.storage.has.pending.retry"));
            }

            lblStorageProgress.getStyleClass().removeAll(CSS_CLASS_STATUS_SUCCESS, CSS_CLASS_STATUS_ERROR);
            lblStorageProgress.getStyleClass().add(CSS_CLASS_STATUS_ERROR);
        } else {
            lblStorageProgress.getStyleClass().removeAll(CSS_CLASS_STATUS_SUCCESS, CSS_CLASS_STATUS_ERROR);
        }
    }

    @FXML
    public void onSelectSaveFolder() {
        selectAndPersistFolder(txtSavePath, FolderType.SYNC);
    }

    @FXML
    public void onSelectBackupFolder() {
        selectAndPersistFolder(txtBackupPath, FolderType.BACKUP);
    }

    @FXML
    public void onSelectExportFolder() {
        selectAndPersistFolder(txtExportPath, FolderType.EXPORT);
    }

    @FXML
    public void onToggleAskEveryTimeExport() {
        boolean value = chkAskEveryTimeExport.isSelected();
        try {
            adminSettingsService.setAskEveryTimeExport(session.getCurrentUserId(), value);
            if (value) {
                showNotice(I18n.get("setting.export.mode.ask.on"), true);
            } else {
                showNotice(I18n.get("setting.export.mode.ask.off"), true);
            }
        } catch (Exception e) {
            log.error("Failed to save askEveryTimeExport setting", e);
            chkAskEveryTimeExport.setSelected(!value);
            showNotice(I18n.get("setting.storage.error"), false);
        }
    }

    @FXML
    public void onToggleAutoDelete() {
        boolean value = chkAutoDelete.isSelected();
        try {
            adminSettingsService.setAutoDelete(value);
            showNotice(value
                    ? I18n.get("setting.databackup.status.on")
                    : I18n.get("setting.databackup.status.off"), true);
        } catch (Exception e) {
            log.error("Failed to save autoDelete setting", e);
            chkAutoDelete.setSelected(!value);
            showNotice(I18n.get("setting.databackup.status.error"), false);
        }
    }

    @FXML
    public void onToggleStartWithWindows() {
        boolean value = chkStartWithWindows.isSelected();
        try {
            adminSettingsService.setStartWithWindows(value);
            showNotice(value
                    ? I18n.get("setting.startWithWindows.status.on")
                    : I18n.get("setting.startWithWindows.status.off"), true);
        } catch (IllegalStateException e) {
            log.warn("Failed to save startWithWindows setting: {}", e.getMessage());
            chkStartWithWindows.setSelected(!value);
            showNotice(I18n.get("setting.startWithWindows.status.error"), false);
        } catch (Exception e) {
            log.error("Failed to save startWithWindows setting", e);
            chkStartWithWindows.setSelected(!value);
            showNotice(I18n.get("setting.startWithWindows.status.error"), false);
        }
    }

    // ── Language / theme helpers ──────────────────────────────────────────────

    private void setActiveLanguageButton() {
        applyActiveButton(
                AppConstants.LANG_VI.equals(I18n.getLocale().getLanguage()) ? btnVietnamese : btnEnglish,
                List.of(btnEnglish, btnVietnamese));
    }

    private void setActiveThemeButton() {
        applyActiveButton(
                AppConstants.THEME_DARK.equals(ThemeManager.getTheme()) ? btnDarkTheme : btnLightTheme,
                List.of(btnLightTheme, btnDarkTheme));
    }

    private void switchLanguageIfNeeded(String languageTag) {
        if (languageTag.equals(I18n.getLocale().getLanguage()))
            return;
        Language language = AppConstants.LANG_EN.equals(languageTag) ? Language.EN : Language.VI;
        try {
            userSettingService.saveLanguage(session.getUser().getId(), language);
        } catch (Exception e) {
            log.warn("Could not persist language preference", e);
        }
        I18n.setLocale(Locale.forLanguageTag(languageTag));
        refreshLocalizedText();
        setupActionButtons();
        setActiveLanguageButton();
        Stage stage = (Stage) panel.getScene().getWindow();
        if (stage != null) {
            stage.setTitle(I18n.get("settings.title"));
        }
        if (onLanguageChangedAction != null) {
            onLanguageChangedAction.run();
        } else {
            MainApp.showAdmin();
        }

    }

    private void switchTheme(String theme) {
        Theme themeEnum = AppConstants.THEME_DARK.equals(theme) ? Theme.DARK : Theme.LIGHT;
        try {
            userSettingService.saveTheme(session.getUser().getId(), themeEnum);
        } catch (Exception e) {
            log.warn("Could not persist theme preference", e);
        }
        ThemeManager.setTheme(theme);
        ThemeManager.apply(MainApp.getScene());

        // Notify listeners so any other open dialogs can refresh their scenes to match
        // the new theme.
        eventPublisher.publishEvent(new ThemeChangedEvent(this, theme));
    }

    private void applyActiveButton(Button activeButton, List<Button> buttons) {
        buttons.forEach(b -> b.getStyleClass().remove(AppConstants.ACTIVE_BUTTON));
        if (!activeButton.getStyleClass().contains(AppConstants.ACTIVE_BUTTON)) {
            activeButton.getStyleClass().add(AppConstants.ACTIVE_BUTTON);
        }
    }

    // ── Folder helpers ────────────────────────────────────────────────────────

    // Show native directory picker and persist immediately after selection.
    private void selectAndPersistFolder(TextField txtField, FolderType type) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("setting.storage.chooser.title", type.toLocalizedString()));
        String current = txtField.getText();
        if (current != null && !current.isBlank()) {
            File currentDir = new File(current);
            if (currentDir.exists()) {
                chooser.setInitialDirectory(currentDir);
            }
        }
        Stage stage = (Stage) txtField.getScene().getWindow();
        File selected = chooser.showDialog(stage);
        if (selected != null) {
            txtField.setText(selected.getAbsolutePath());
            checkAndShowDriveConflict();
            persistFolder(txtField, type);
            restoreFailureRepository.clearAll();
            hideProgressUI();
        }

        adminLayoutController.refreshStorageStatus();
    }

    private void persistFolder(TextField txtField, FolderType type) {
        String path = txtField.getText();
        if (path == null || path.isBlank()) {
            showNotice(I18n.get("setting.storage.warn.empty"), false);
            return;
        }
        try {
            if (type == FolderType.EXPORT) {
                // Export dir is per-user; skip the global save.
                adminSettingsService.saveExportFolderForUser(session.getCurrentUserId(), path);
            } else {
                adminSettingsService.saveFolder(type, path);
            }
            driveResolverService.invalidateCache();
            eventPublisher.publishEvent(new StorageRestoredEvent(type));
            showNotice(I18n.get("setting.storage.success"), true);
        } catch (Exception e) {
            log.error("Failed to save folder setting for {}", type, e);
            showNotice(I18n.get("setting.storage.error"), false);
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private void openUserInfo() {
        DialogHelper.Dialog<UserEditFormController> dialog = DialogHelper.createDialog(
                ViewPaths.USER_ACCOUNT_DIALOG,
                I18n.get("user.account.title"));
        dialog.controller().prepareForAccount(session.getUser());
        dialog.controller().setOnSuccess(() -> showNotice(I18n.get("user.account.password.updated.success"), true));
        dialog.controller().setOnNoChange(() -> showNotice(I18n.get("user.update.nochange"), true));
        dialog.stage().setResizable(false);
        dialog.stage().initOwner(panel.getScene().getWindow());
        dialog.stage().showAndWait();
    }

    // ── Notice ────────────────────────────────────────────────────────────────

    private void showNotice(String message, boolean success) {
        if (message == null || message.isBlank() || noticeRenderer == null) {
            return;
        }
        if (success) {
            noticeRenderer.showSuccess(message);
        } else {
            noticeRenderer.showError(message);
        }
    }

    @FXML
    public void onRestore() {
        String backupPath = txtBackupPath.getText();
        String dataPath = txtSavePath.getText();
        if (backupPath == null || backupPath.isBlank()) {
            showNotice(I18n.get("setting.storage.error.backup.dir.not.configured"), false);
            return;
        }
        if (dataPath == null || dataPath.isBlank()) {
            showNotice(I18n.get("setting.storage.error.data.dir.not.configured"), false);
            return;
        }
        String reason = getStorageBlockedReason();
        if (reason != null) {
            showNotice(reason, false);
            return;
        }

        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("setting.storage.restore.confirm.title"),
                null,
                I18n.get("setting.storage.restore.confirm.content"));

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK)
            return;

        runStorageRecoveryOperation(adminSettingsService::restore);
    }

    private void runStorageRecoveryOperation(Supplier<RestoreService.BackupSyncResult> operation) {
        setStorageControlsDisabled(true);
        restoreService.setOnProgressInitialized(() -> Platform.runLater(() -> {
            storageActionBox.setVisible(true);
            storageActionBox.setManaged(true);
            btnRetryFailedRestore.setVisible(false);
            btnRetryFailedRestore.setManaged(false);
            lblStorageProgress.setText(I18n.get(I18N_SETTING_STORAGE_PROGRESS,
                    restoreService.getCurrentProgress().buildProgressArgs()));
            startProgressUpdater();
        }));

        Thread thread = new Thread(() -> {
            RestoreService.BackupSyncResult syncResult = operation.get();
            eventPublisher.publishEvent(new StorageRecoveryCompletedEvent(this.toString()));

            Platform.runLater(() -> {
                stopProgressUpdater();
                setStorageControlsDisabled(false);
                lblStorageProgress.setText(
                        I18n.get(I18N_SETTING_STORAGE_FINAL, restoreService.getCurrentProgress().buildProgressArgs()));
                if (syncResult.failures().isEmpty()) {
                    lblStorageProgress.getStyleClass().removeAll(CSS_CLASS_STATUS_SUCCESS, CSS_CLASS_STATUS_ERROR);
                    lblStorageProgress.getStyleClass().add(CSS_CLASS_STATUS_SUCCESS);
                    Executors.newSingleThreadScheduledExecutor()
                            .schedule(() -> Platform.runLater(this::hideProgressUI), 3, TimeUnit.SECONDS);
                } else {
                    btnRetryFailedRestore.setVisible(true);
                    btnRetryFailedRestore.setManaged(true);
                    lblStorageProgress.getStyleClass().removeAll(CSS_CLASS_STATUS_SUCCESS, CSS_CLASS_STATUS_ERROR);
                    lblStorageProgress.getStyleClass().add(CSS_CLASS_STATUS_ERROR);
                }
                if (!syncResult.success()) {
                    showNotice(syncResult.errorMessage(), false);
                }
            });
        });
        thread.setDaemon(true);
        thread.start();
    }

    private boolean isDriveConflict(String pathA, String pathB) {
        if (pathA == null || pathA.isBlank() || pathB == null || pathB.isBlank())
            return false;

        Path pathARoot = Path.of(pathA).getRoot();
        Path pathBRoot = Path.of(pathB).getRoot();

        if (pathARoot == null || pathBRoot == null)
            return false;

        return pathARoot.toString().equalsIgnoreCase(pathBRoot.toString());
    }

    private void checkAndShowDriveConflict() {
        String savePath = txtSavePath.getText();
        String backupPath = txtBackupPath.getText();

        boolean conflict = isDriveConflict(savePath, backupPath);

        lblDriveConflictWarning.setVisible(conflict);
        lblDriveConflictWarning.setManaged(conflict);
    }

    private String getStorageBlockedReason() {
        if (restoreService.isRunning()) {
            return I18n.get("setting.storage.error.already.running");
        }
        if (deviceSyncQueue.isActive()) {
            return I18n.get("setting.storage.error.blocked.device.sync");
        }
        if (dataBackupQueue.isActive()) {
            return I18n.get("setting.storage.error.blocked.data.backup");
        }
        return null;
    }

    private void startProgressUpdater() {
        stopProgressUpdater();
        storageActionBox.setVisible(true);
        storageActionBox.setManaged(true);
        progressScheduler = Executors.newSingleThreadScheduledExecutor();
        progressScheduler.scheduleAtFixedRate(() -> {
            if (!restoreService.isRunning())
                return;

            String message = I18n.get(I18N_SETTING_STORAGE_PROGRESS,
                    restoreService.getCurrentProgress().buildProgressArgs());
            boolean hasFailed = restoreService.getCurrentProgress().getFailed() > 0;

            Platform.runLater(() -> {
                lblStorageProgress.getStyleClass().removeAll(CSS_CLASS_STATUS_SUCCESS, CSS_CLASS_STATUS_ERROR);
                lblStorageProgress.getStyleClass().add(hasFailed ? CSS_CLASS_STATUS_ERROR : CSS_CLASS_STATUS_SUCCESS);
                lblStorageProgress.setText(message);
            });
        }, 0, 3, TimeUnit.SECONDS);
    }

    private void stopProgressUpdater() {
        if (progressScheduler != null) {
            progressScheduler.shutdownNow();
            progressScheduler = null;
        }
    }

    private void hideProgressUI() {
        storageActionBox.setVisible(false);
        storageActionBox.setManaged(false);
        lblStorageProgress.setText("");
        btnRetryFailedRestore.setVisible(false);
        btnRetryFailedRestore.setManaged(false);
    }

    @FXML
    public void onRetryFailedRestore() {
        runStorageRecoveryOperation(adminSettingsService::retryFailed);
    }

    private void setStorageControlsDisabled(boolean disabled) {
        btnRestore.setDisable(disabled);
        btnChooseSaveFolder.setDisable(disabled);
        btnChooseBackupFolder.setDisable(disabled);
        btnChooseExportFolder.setDisable(disabled);
    }

    @EventListener
    public void onStorageRestored(StorageRestoredEvent event) {
        Platform.runLater(() -> {
            switch (event.getTarget()) {
                case SYNC -> refreshStoragePathField(txtSavePath, FolderType.SYNC);
                case BACKUP -> refreshStoragePathField(txtBackupPath, FolderType.BACKUP);
                case EXPORT -> refreshStoragePathField(txtExportPath, FolderType.EXPORT);
            }
            if (txtSavePath != null && txtBackupPath != null && lblDriveConflictWarning != null) {
                checkAndShowDriveConflict();
            }
        });
    }

    /**
     * Refreshes a storage path field only when its FXML control is currently loaded.
     */
    private void refreshStoragePathField(TextField textField, FolderType folderType) {
        if (textField == null) {
            return;
        }
        adminSettingsService.getFolderPath(folderType).ifPresent(textField::setText);
    }
}
