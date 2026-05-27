package com.app.user.settingsdialog.controllers;

import java.io.File;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.MainApp;
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.admin.usermanagement.controllers.UserEditFormController;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.ViewPaths;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.Language;
import com.app.common.definitions.enums.Theme;
import com.app.common.events.ThemeChangedEvent;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.NoticeStackRenderer;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.modules.theme.ThemeManager;
import com.app.common.services.UserSettingService;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.Setter;

@Component
public class UserSettingsDialogController {

    private static final Logger log = LoggerFactory.getLogger(UserSettingsDialogController.class);

    private final AdminSettingsDialogService settingsService;
    private final Session session;
    private final UserSettingService userSettingService;
    private final AppUpdateController appUpdateController;
    private final ApplicationEventPublisher eventPublisher;

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
    private Label lsbCheckVersion;
    @FXML
    private Label lblExportFolderTitle;
    @FXML
    private TextField txtExportPath;
    @FXML
    private Button btnChooseExportFolder;
    @FXML
    private CheckBox chkAskEveryTimeExport;
    @FXML
    private VBox noticeContainer;

    private NoticeStackRenderer noticeRenderer;
    @Setter
    private Runnable onLanguageChangedAction;

    public UserSettingsDialogController(
            AdminSettingsDialogService settingsService,
            Session session,
            UserSettingService userSettingService,
            AppUpdateController appUpdateController,
            ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.session = session;
        this.userSettingService = userSettingService;
        this.appUpdateController = appUpdateController;
        this.eventPublisher = eventPublisher;
    }

    @FXML
    public void initialize() {
        noticeRenderer = new NoticeStackRenderer(noticeContainer);

        refreshLocalizedText();
        setupSegmentButtons();
        setupActionButtons();
        loadUserSettings();
    }

    private void refreshLocalizedText() {
        lblLanguage.setText("🌐 " + I18n.get("settings.language"));
        lblTheme.setText("🎨 " + I18n.get("settings.theme"));

        btnEnglish.setText(I18n.get("settings.language.english"));
        btnVietnamese.setText(I18n.get("settings.language.vietnamese"));
        btnLightTheme.setText("☀ " + I18n.get("settings.theme.light"));
        btnDarkTheme.setText("☾ " + I18n.get("settings.theme.dark"));

        lblExportFolderTitle.setText(I18n.get("setting.storage.export.title"));
        btnChooseExportFolder.setText(I18n.get("setting.storage.btn.choose"));
        chkAskEveryTimeExport.setText(I18n.get("setting.export.mode.ask.checkbox"));
        lsbCheckVersion.setText(I18n.get("setting.startWithWindows.checkversion",getVersionCurrent()));
    }

    // Keep user settings limited to per-user preferences and safe self-service
    // actions.
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

    private void loadUserSettings() {
        txtExportPath.setText(settingsService.getExportFolderForUser(session.getCurrentUserId()));
        chkAskEveryTimeExport.setSelected(settingsService.getAskEveryTimeExport(session.getCurrentUserId()));
    }

    @FXML
    public void onSelectExportFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("setting.storage.chooser.title", I18n.get("setting.storage.export.title")));
        String current = txtExportPath.getText();
        if (current != null && !current.isBlank()) {
            File currentDir = new File(current);
            if (currentDir.exists() && currentDir.isDirectory()) {
                chooser.setInitialDirectory(currentDir);
            }
        }

        Stage stage = (Stage) txtExportPath.getScene().getWindow();
        File selected = chooser.showDialog(stage);
        if (selected == null) {
            return;
        }

        txtExportPath.setText(selected.getAbsolutePath());
        persistExportFolder();
    }

    @FXML
    public void onToggleAskEveryTimeExport() {
        boolean value = chkAskEveryTimeExport.isSelected();
        try {
            settingsService.setAskEveryTimeExport(session.getCurrentUserId(), value);
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

    private void persistExportFolder() {
        String path = txtExportPath.getText();
        if (path == null || path.isBlank()) {
            return;
        }

        try {
            settingsService.saveExportFolderForUser(session.getCurrentUserId(), path);
            showNotice(I18n.get("setting.storage.saved"), true);
        } catch (Exception e) {
            log.error("Failed to save export folder", e);
            showNotice(I18n.get("setting.storage.error"), false);
        }
    }

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
        if (languageTag.equals(I18n.getLocale().getLanguage())) {
            return;
        }

        Language language = AppConstants.LANG_EN.equals(languageTag) ? Language.EN : Language.VI;
        try {
            userSettingService.saveLanguage(session.getUser().getId(), language);
        } catch (Exception e) {
            log.warn("Failed to save language setting", e);
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
        }
    }

    /**
     * Switches the theme for the current user and notifies open scenes/dialogs so
     * they can refresh their stylesheets immediately.
     *
     * @param theme the application theme constant to apply
     */
    private void switchTheme(String theme) {
        Theme themeEnum = AppConstants.THEME_DARK.equals(theme) ? Theme.DARK : Theme.LIGHT;
        try {
            userSettingService.saveTheme(session.getUser().getId(), themeEnum);
        } catch (Exception e) {
            log.warn("Failed to save theme setting", e);
        }

        ThemeManager.setTheme(theme);
        ThemeManager.apply(MainApp.getScene());
        eventPublisher.publishEvent(new ThemeChangedEvent(this, theme));
    }

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

    private void applyActiveButton(Button activeButton, List<Button> buttons) {
        buttons.forEach(button -> button.getStyleClass().remove(AppConstants.ACTIVE_BUTTON));
        if (!activeButton.getStyleClass().contains(AppConstants.ACTIVE_BUTTON)) {
            activeButton.getStyleClass().add(AppConstants.ACTIVE_BUTTON);
        }
    }

    @EventListener
    public void onStorageRestored(StorageRestoredEvent event) {
        if (event.getTarget() != FolderType.EXPORT) {
            return;
        }

        Platform.runLater(() -> {
            if (txtExportPath != null) {
                txtExportPath.setText(settingsService.getExportFolderForUser(session.getCurrentUserId()));
            }
        });
    }
    private String getVersionCurrent(){
        String version = getClass()
                .getPackage()
                .getImplementationVersion();

        if (version == null) {
            version = "Not version";
        }
        return version;
    }
}
