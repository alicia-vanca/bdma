package com.app.admin.settingsdialog.controllers;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.MainApp;
import com.app.admin.layout.controllers.AdminLayoutController;
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.admin.usermanagement.controllers.UserEditFormController;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.ViewPaths;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.Language;
import com.app.common.definitions.enums.Role;
import com.app.common.definitions.enums.Theme;
import com.app.common.events.ThemeChangedEvent;
import com.app.common.helpers.AlertHelper;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.NoticeStackRenderer;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.modules.databackup.queues.DataBackupQueue;
import com.app.common.modules.datarestore.services.RestoreService;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.foldermanager.events.StorageRecoveryCompletedEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.services.DefaultStorageLocationService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.modules.theme.ThemeManager;
import com.app.common.repositories.RestoreFailureRepository;
import com.app.common.services.DriveResolverService;
import com.app.common.services.UserSettingService;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import lombok.Setter;

@Primary
@Component
public class AdminSettingsDialogController {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsDialogController.class);
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String CSS_CLASS_STATUS_SUCCESS = "status-success";
    private static final String CSS_CLASS_STATUS_ERROR = "status-error";
    private static final String I18N_SETTING_STORAGE_PROGRESS = "setting.storage.progress";
    private static final String I18N_SETTING_STORAGE_FINAL = "setting.storage.final";
    private static final String I18N_SETTING_STORAGE_CHOOSE = "setting.storage.btn.choose";
    private static final String I18N_SETTING_STORAGE_REMOVABLE_MESSAGE = "setting.storage.removable.message";

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
    private final DefaultStorageLocationService defaultStorageLocationService;

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
    private CheckBox chkDeleteEmptyDateFolders;
    @FXML
    private Label lblDeleteEmptyDateFoldersTitle;
    @FXML
    private Label lblStartWithWindowsDescription;
    @FXML
    private CheckBox chkStartWithWindows;
    @FXML
    private Label lblStartWithWindowsTitle;
    @FXML
    private Label lsbCheckVersion;
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
    @FXML
    private Button btnDefaultSettings;

    private NoticeStackRenderer noticeRenderer;
    @Setter
    private Runnable onLanguageChangedAction;
    private Timeline progressTimeline;
    private PauseTransition hideProgressDelay;

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
            RestoreFailureRepository restoreFailureRepository,
            DefaultStorageLocationService defaultStorageLocationService) {
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
        this.defaultStorageLocationService = defaultStorageLocationService;
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
        lblDeleteEmptyDateFoldersTitle.setText(
                I18n.get("setting.datasync.deleteEmptyDateFolders.checkbox"));

        lblStartWithWindowsDescription.setText(I18n.get("setting.startWithWindows.desc"));
        lblStartWithWindowsTitle.setText(I18n.get("setting.startWithWindows.checkbox"));
        lsbCheckVersion.setText(I18n.get("setting.startWithWindows.checkversion", getVersionCurrent()));
        btnRestore.setText(I18n.get("setting.storage.btn.restore"));
        btnRetryFailedRestore.setText(I18n.get("setting.storage.btn.retry"));
        btnDefaultSettings.setText(I18n.get("setting.defaults.button"));
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
        loadAutoDeleteSettings();
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
                Object[] args = new Object[] {
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
    private void onClickAskEveryTimeExportLabel(MouseEvent event) {
        chkAskEveryTimeExport.fire();
    }

    @FXML
    public void onToggleAutoDelete() {
        boolean value = chkAutoDelete.isSelected();
        try {
            boolean childValue = value && chkDeleteEmptyDateFolders.isSelected();
            adminSettingsService.setAutoDeleteState(value, childValue);
            if (!value) {
                chkDeleteEmptyDateFolders.setSelected(false);
            }
            updateDeleteEmptyFolderAvailability();
            showNotice(value
                    ? I18n.get("setting.databackup.status.on")
                    : I18n.get("setting.databackup.status.off"), true);
        } catch (Exception e) {
            log.error("Failed to save autoDelete setting", e);
            loadAutoDeleteSettings();
            showNotice(I18n.get("setting.databackup.status.error"), false);
        }
    }

    @FXML
    private void onClickAutoDelete(MouseEvent event) {
        chkAutoDelete.fire();
    }

    @FXML
    public void onToggleDeleteEmptyDateFolders() {
        if (!chkAutoDelete.isSelected()) {
            chkDeleteEmptyDateFolders.setSelected(false);
            updateDeleteEmptyFolderAvailability();
            return;
        }

        boolean value = chkDeleteEmptyDateFolders.isSelected();
        try {
            adminSettingsService.setDeleteEmptyDateFolders(value);
            showNotice(value
                    ? I18n.get("setting.datasync.deleteEmptyDateFolders.status.on")
                    : I18n.get("setting.datasync.deleteEmptyDateFolders.status.off"), true);
        } catch (Exception e) {
            log.error("Failed to save deleteEmptyDateFolders setting", e);
            loadAutoDeleteSettings();
            showNotice(I18n.get("setting.datasync.deleteEmptyDateFolders.status.error"), false);
        }
    }

    @FXML
    private void onClickDeleteEmptyDateFolders(MouseEvent event) {
        if (!chkDeleteEmptyDateFolders.isDisabled()) {
            chkDeleteEmptyDateFolders.fire();
        }
    }

    private void loadAutoDeleteSettings() {
        boolean autoDelete = adminSettingsService.getAutoDelete();
        boolean deleteEmptyDateFolders = adminSettingsService.getDeleteEmptyDateFolders();

        if (!autoDelete && deleteEmptyDateFolders) {
            try {
                adminSettingsService.setAutoDeleteState(false, false);
            } catch (Exception e) {
                log.error("Failed to normalize invalid auto-delete child setting", e);
            }
            deleteEmptyDateFolders = false;
        }

        chkAutoDelete.setSelected(autoDelete);
        chkDeleteEmptyDateFolders.setSelected(autoDelete && deleteEmptyDateFolders);
        updateDeleteEmptyFolderAvailability();
    }

    private void updateDeleteEmptyFolderAvailability() {
        boolean disabled = !chkAutoDelete.isSelected();
        chkDeleteEmptyDateFolders.setDisable(disabled);
        lblDeleteEmptyDateFoldersTitle.setDisable(disabled);
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

    @FXML
    private void onClickStartWithWindows(MouseEvent event) {
        chkStartWithWindows.fire();
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
        if (type == FolderType.SYNC || type == FolderType.BACKUP) {
            defaultStorageLocationService.refreshVolumesAsync();
        }
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
        while (true) {
            File selected = chooser.showDialog(stage);
            if (selected == null) {
                break;
            }
            if (isRemovableSelection(type, selected)) {
                if (showRemovableStorageNotice()) {
                    continue;
                }
                break;
            }

            txtField.setText(selected.getAbsolutePath());
            checkAndShowDriveConflict();
            persistFolder(txtField, type);
            restoreFailureRepository.clearAll();
            hideProgressUI();
            break;
        }

        adminLayoutController.refreshStorageStatus();
    }

    private boolean isRemovableSelection(FolderType type, File selected) {
        return (type == FolderType.SYNC || type == FolderType.BACKUP)
                && defaultStorageLocationService.isOnRemovableVolume(selected.toPath());
    }

    private boolean showRemovableStorageNotice() {
        Alert notice = AlertHelper.createConfirmation(
                I18n.get("setting.storage.removable.title"),
                I18n.get("setting.storage.removable.header"),
                I18n.get(I18N_SETTING_STORAGE_REMOVABLE_MESSAGE));
        ButtonType chooseAnother = new ButtonType(
                I18n.get("setting.storage.removable.choose_another"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.get("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertHelper.setButtons(notice, chooseAnother, cancel);
        return notice.showAndWait().filter(chooseAnother::equals).isPresent();
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

    protected void openUserInfo() {
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
        restoreService.setOnProgressInitialized(() -> Platform.runLater(this::showStorageProgressInitialized));

        Thread thread = new Thread(() -> {
            RestoreService.BackupSyncResult syncResult = operation.get();
            eventPublisher.publishEvent(new StorageRecoveryCompletedEvent(this.toString()));
            Platform.runLater(() -> showStorageRecoveryResult(syncResult));
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void showStorageProgressInitialized() {
        storageActionBox.setVisible(true);
        storageActionBox.setManaged(true);
        btnRetryFailedRestore.setVisible(false);
        btnRetryFailedRestore.setManaged(false);
        lblStorageProgress.setText(I18n.get(I18N_SETTING_STORAGE_PROGRESS,
                restoreService.getCurrentProgress().buildProgressArgs()));
        startProgressUpdater();
    }

    private void showStorageRecoveryResult(RestoreService.BackupSyncResult syncResult) {
        stopProgressUpdater();
        setStorageControlsDisabled(false);

        String failureMessage = syncResult.errorMessage();
        boolean completedWithoutFailures = syncResult.success() && syncResult.failures().isEmpty();
        lblStorageProgress.setText(getStorageRecoveryResultMessage(completedWithoutFailures, failureMessage));
        lblStorageProgress.getStyleClass().removeAll(CSS_CLASS_STATUS_SUCCESS, CSS_CLASS_STATUS_ERROR);

        if (completedWithoutFailures) {
            showStorageRecoverySuccess();
            return;
        }

        showStorageRecoveryFailure(!syncResult.failures().isEmpty());
        if (!syncResult.success()) {
            showNotice(failureMessage, false);
        }
    }

    private String getStorageRecoveryResultMessage(boolean completedWithoutFailures, String failureMessage) {
        if (completedWithoutFailures || failureMessage == null || failureMessage.isBlank()) {
            return I18n.get(I18N_SETTING_STORAGE_FINAL, restoreService.getCurrentProgress().buildProgressArgs());
        }
        return failureMessage;
    }

    private void showStorageRecoverySuccess() {
        lblStorageProgress.getStyleClass().add(CSS_CLASS_STATUS_SUCCESS);
        scheduleHideProgressUI();
    }

    private void showStorageRecoveryFailure(boolean hasRecoverableFailures) {
        btnRetryFailedRestore.setVisible(hasRecoverableFailures);
        btnRetryFailedRestore.setManaged(hasRecoverableFailures);
        lblStorageProgress.getStyleClass().add(CSS_CLASS_STATUS_ERROR);
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

        if (lblDriveConflictWarning.isVisible() != conflict) {
            lblDriveConflictWarning.setVisible(conflict);
            lblDriveConflictWarning.setManaged(conflict);
            
            if (panel != null && panel.getScene() != null && panel.getScene().getWindow() != null) {
                Stage stage = (Stage) panel.getScene().getWindow();
                if (stage.isShowing()) {
                    Platform.runLater(stage::sizeToScene);
                }
            }
        }
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
        cancelPendingHideProgress();
        storageActionBox.setVisible(true);
        storageActionBox.setManaged(true);
        updateStorageProgress();

        progressTimeline = new Timeline(new KeyFrame(Duration.seconds(3), event -> updateStorageProgress()));
        progressTimeline.setCycleCount(Animation.INDEFINITE);
        progressTimeline.play();
    }

    private void updateStorageProgress() {
        if (!restoreService.isRunning())
            return;

        String message = I18n.get(I18N_SETTING_STORAGE_PROGRESS,
                restoreService.getCurrentProgress().buildProgressArgs());
        boolean hasFailed = restoreService.getCurrentProgress().getTotalFailed() > 0;

        lblStorageProgress.getStyleClass().removeAll(CSS_CLASS_STATUS_SUCCESS, CSS_CLASS_STATUS_ERROR);
        lblStorageProgress.getStyleClass().add(hasFailed ? CSS_CLASS_STATUS_ERROR : CSS_CLASS_STATUS_SUCCESS);
        lblStorageProgress.setText(message);
    }

    private void stopProgressUpdater() {
        if (progressTimeline != null) {
            progressTimeline.stop();
            progressTimeline = null;
        }
    }

    private void scheduleHideProgressUI() {
        cancelPendingHideProgress();
        hideProgressDelay = new PauseTransition(Duration.seconds(3));
        hideProgressDelay.setOnFinished(event -> {
            hideProgressDelay = null;
            hideProgressUI();
        });
        hideProgressDelay.play();
    }

    private void cancelPendingHideProgress() {
        if (hideProgressDelay != null) {
            hideProgressDelay.stop();
            hideProgressDelay = null;
        }
    }

    private void hideProgressUI() {
        cancelPendingHideProgress();
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
                case DECRYPT -> {
                    // Decrypt storage is managed by its own module and has no field in this dialog.
                }
            }
            if (txtSavePath != null && txtBackupPath != null && lblDriveConflictWarning != null) {
                checkAndShowDriveConflict();
            }
        });
    }

    /**
     * Refreshes a storage path field only when its FXML control is currently
     * loaded.
     */
    private void refreshStoragePathField(TextField textField, FolderType folderType) {
        if (textField == null) {
            return;
        }
        adminSettingsService.getFolderPath(folderType).ifPresent(textField::setText);
    }

    public static String getVersionCurrent() {
        String version = AdminSettingsDialogController.class
                .getPackage()
                .getImplementationVersion();

        if (version == null) {
            version = AppConstants.VERSION_DEV;
        }
        return version;
    }

    // ────────────── Default Settings Reset ──────────────────────────────────

    /**
     * Returns the reset scope for this controller. Subclasses can override to
     * specify DEV scope.
     */
    protected Role getResetScope() {
        return Role.ADMIN;
    }

    @FXML
    public void onResetDefaultSettings() {
        Role scope = getResetScope();

        if (adminSettingsService.isResetBlocked(scope)) {
            noticeRenderer.showError(I18n.get("setting.defaults.blocked"));
            return;
        }

        // Refresh the shared volume cache while the user reads the confirmation.
        defaultStorageLocationService.refreshVolumesAsync();

        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("setting.defaults.confirm.title"),
                null,
                I18n.get("setting.defaults.confirm.content"));

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        // Recheck block state after confirmation (race condition protection)
        if (adminSettingsService.isResetBlocked(scope)) {
            noticeRenderer.showError(I18n.get("setting.defaults.blocked"));
            return;
        }

        btnDefaultSettings.setDisable(true);

        executor.execute(() -> {
            boolean success = adminSettingsService.resetToDefaults(session.getCurrentUserId(), scope);

            Platform.runLater(() -> {
                btnDefaultSettings.setDisable(false);
                if (success) {
                    loadAdminSettings();

                    I18n.setLocale(Locale.forLanguageTag(AppConstants.LANG_VI));
                    ThemeManager.setTheme(AppConstants.THEME_LIGHT);
                    ThemeManager.apply(MainApp.getScene());
                    eventPublisher.publishEvent(new ThemeChangedEvent(this, AppConstants.THEME_LIGHT));

                    refreshLocalizedText();
                    setupActionButtons();

                    setActiveLanguageButton();
                    setActiveThemeButton();

                    adminLayoutController.refreshStorageStatus();

                    if (onLanguageChangedAction != null) {
                        onLanguageChangedAction.run();
                    } else {
                        MainApp.showAdmin();
                    }

                    noticeRenderer.showSuccess(I18n.get("setting.defaults.success"));
                    log.info("Settings reset to defaults: scope={}, userId={}", scope, session.getCurrentUserId());
                } else {
                    noticeRenderer.showError(I18n.get("setting.defaults.error"));
                    log.error("Failed to reset settings to defaults: scope={}, userId={}", scope, session.getCurrentUserId());
                }
            });
        });
    }
}
