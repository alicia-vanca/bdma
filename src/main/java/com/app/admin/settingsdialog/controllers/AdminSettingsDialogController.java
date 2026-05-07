package com.app.admin.settingsdialog.controllers;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.modules.theme.ThemeManager;
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
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.Setter;

@Component
public class AdminSettingsDialogController {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsDialogController.class);

    private final AdminSettingsDialogService adminSettingsService;
    private final Session session;
    private final UserSettingService userSettingService;
    private final AppUpdateController appUpdateController;
    private final DriveResolverService driveResolverService;
    private final AdminLayoutController adminLayoutController;
    private final ApplicationEventPublisher eventPublisher;

    @FXML
    private VBox panel;
    @FXML
    private Label lblTitle;
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
    private Label lblSaveFolderNote;
    @FXML
    private Label lblAutoDeleteTitle;
    @FXML
    private Label lblAutoDeleteDescription;
    @FXML
    private CheckBox chkAutoDelete;
    @FXML
    private Label lblAutoDeleteNote;
    @FXML
    private Label lblStartWithWindowsTitle;
    @FXML
    private Label lblStartWithWindowsDescription;
    @FXML
    private CheckBox chkStartWithWindows;
    @FXML
    private VBox noticeContainer;
    @FXML
    private Button btnRestore;
    @FXML
    private Label lblDriveConflictWarning;

    private NoticeStackRenderer noticeRenderer;
    @Setter
    private Runnable onLanguageChangedAction;

    public AdminSettingsDialogController(
            AdminSettingsDialogService adminSettingsService,
            Session session,
            UserSettingService userSettingService,
            AppUpdateController appUpdateController,
            DriveResolverService driveResolverService,
            AdminLayoutController adminLayoutController,
            ApplicationEventPublisher eventPublisher) {
        this.adminSettingsService = adminSettingsService;
        this.session = session;
        this.userSettingService = userSettingService;
        this.appUpdateController = appUpdateController;
        this.driveResolverService = driveResolverService;
        this.adminLayoutController = adminLayoutController;
        this.eventPublisher = eventPublisher;
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
        lblTitle.setText("⚙ " + I18n.get("settings.title"));
        lblLanguage.setText("🌐 " + I18n.get("settings.language"));
        lblTheme.setText("🎨 " + I18n.get("settings.theme"));

        btnEnglish.setText(I18n.get("settings.language.english"));
        btnVietnamese.setText(I18n.get("settings.language.vietnamese"));
        btnLightTheme.setText("☀ " + I18n.get("settings.theme.light"));
        btnDarkTheme.setText("☾ " + I18n.get("settings.theme.dark"));

        lblSaveFolderTitle.setText(I18n.get("setting.storage.sync.title"));
        btnChooseSaveFolder.setText(I18n.get("setting.storage.btn.choose"));
        lblBackupFolderTitle.setText(I18n.get("setting.storage.backup.title"));
        btnChooseBackupFolder.setText(I18n.get("setting.storage.btn.choose"));
        lblSaveFolderNote.setText(I18n.get("setting.storage.sync.note"));

        lblDriveConflictWarning.setText(I18n.get("setting.storage.warn.same_drive"));

        lblAutoDeleteTitle.setText(I18n.get("setting.databackup.autodelete.title"));
        lblAutoDeleteDescription.setText(I18n.get("setting.databackup.autodelete.desc"));
        chkAutoDelete.setText(I18n.get("setting.databackup.autodelete.checkbox"));
        lblAutoDeleteNote.setText(I18n.get("setting.databackup.autodelete.note"));

        lblStartWithWindowsTitle.setText(I18n.get("setting.startWithWindows.title"));
        lblStartWithWindowsDescription.setText(I18n.get("setting.startWithWindows.desc"));
        chkStartWithWindows.setText(I18n.get("setting.startWithWindows.checkbox"));
        btnRestore.setText(I18n.get("setting.storage.btn.restore"));
        checkAndShowDriveConflict();
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
        chkAutoDelete.setSelected(adminSettingsService.getAutoDelete());
        chkStartWithWindows.setSelected(adminSettingsService.getStartWithWindows());
        checkAndShowDriveConflict();
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
            adminSettingsService.saveFolder(path, type);
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
        if (backupPath == null || backupPath.isBlank()) {
            showNotice(I18n.get("setting.storage.restore.error.no_backup"), false);
            return;
        }

        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("setting.storage.restore.confirm.title"),
                null,
                I18n.get("setting.storage.restore.confirm.content"));

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK)
            return;

        btnRestore.setDisable(true);
        showNotice(I18n.get("setting.storage.restore.progress"), true);

        Thread.ofVirtual().start(() -> {
            RestoreService.RestoreResult restoreResult = adminSettingsService.restoreFromBackup();
            Platform.runLater(() -> {
                btnRestore.setDisable(false);
                if (restoreResult.success()) {
                    showNotice(I18n.get("setting.storage.restore.success", restoreResult.count()), true);
                } else {
                    showNotice(I18n.get("setting.storage.restore.error", restoreResult.errorMessage()), false);
                }
            });
        });
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
}