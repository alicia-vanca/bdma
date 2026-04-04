package com.app.setting.controller;

import com.app.MainApp;
import com.app.common.enums.Language;
import com.app.common.enums.Theme;
import com.app.common.i18n.I18n;
import com.app.common.session.Session;
import com.app.common.theme.ThemeManager;
import com.app.setting.service.UserSettingService;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;

public class SettingsPopupController {

    private static final String ACTIVE_BUTTON = "active-button";
    private static final String LANG_EN = "en";
    private static final String LANG_VI = "vi";

    @FXML
    private VBox panel;
    @FXML
    private Label lblTitle;
    @FXML
    private Label lblLanguage;
    @FXML
    private HBox languageRow;
    @FXML
    private Button btnEnglish;
    @FXML
    private Button btnVietnamese;
    @FXML
    private Label lblTheme;
    @FXML
    private HBox themeRow;
    @FXML
    private Button btnLightTheme;
    @FXML
    private Button btnDarkTheme;
    @FXML
    private Label lblActions;
    @FXML
    private VBox actionsColumn;
    @FXML
    private Button btnInfo;
    @FXML
    private Button btnUpdate;

    private final UserSettingService userSettingService;
    private final Runnable reloadUiAction;
    private final Runnable prepareReloadAction;
    private final Runnable refreshPopupAction;
    private final Runnable hidePopupAction;
    private final Runnable onCheckUpdateAction;
    private final Runnable onInformationAction;

    public SettingsPopupController(
            UserSettingService userSettingService,
            Runnable reloadUiAction,
            Runnable prepareReloadAction,
            Runnable refreshPopupAction,
            Runnable hidePopupAction,
            Runnable onCheckUpdateAction,
            Runnable onInformationAction) {
        this.userSettingService = userSettingService;
        this.reloadUiAction = reloadUiAction;
        this.prepareReloadAction = prepareReloadAction;
        this.refreshPopupAction = refreshPopupAction;
        this.hidePopupAction = hidePopupAction;
        this.onCheckUpdateAction = onCheckUpdateAction;
        this.onInformationAction = onInformationAction;
    }

    @FXML
    public void initialize() {
        setupText();
        setupSegmentButtons();
        setupActions();
    }

    private void setupText() {
        lblTitle.setText("⚙ " + I18n.get("settings.title"));
        lblLanguage.setText("🌐 " + I18n.get("settings.language"));
        lblTheme.setText("🎨 " + I18n.get("settings.theme"));
        lblActions.setText("⚡ " + I18n.get("settings.actions"));

        btnEnglish.setText(I18n.get("settings.language.english"));
        btnVietnamese.setText(I18n.get("settings.language.vietnamese"));
        btnLightTheme.setText("☀ " + I18n.get("settings.theme.light"));
        btnDarkTheme.setText("☾ " + I18n.get("settings.theme.dark"));
    }

    // Keep segment controls symmetric regardless of translation length by binding
    // each button to half of the available row width.
    private void setupSegmentButtons() {
        bindEqualButtonWidths(languageRow, btnEnglish, btnVietnamese);
        bindEqualButtonWidths(themeRow, btnLightTheme, btnDarkTheme);

        applyActiveButton(LANG_VI.equals(I18n.getLocale().getLanguage()) ? btnVietnamese : btnEnglish,
                List.of(btnEnglish, btnVietnamese));
        applyActiveButton(ThemeManager.THEME_DARK.equals(ThemeManager.getTheme()) ? btnDarkTheme : btnLightTheme,
                List.of(btnLightTheme, btnDarkTheme));

        btnEnglish.setOnAction(e -> switchLanguageIfNeeded(LANG_EN));
        btnVietnamese.setOnAction(e -> switchLanguageIfNeeded(LANG_VI));
        btnLightTheme.setOnAction(e -> {
            switchTheme(ThemeManager.THEME_LIGHT);
            applyActiveButton(btnLightTheme, List.of(btnLightTheme, btnDarkTheme));
        });
        btnDarkTheme.setOnAction(e -> {
            switchTheme(ThemeManager.THEME_DARK);
            applyActiveButton(btnDarkTheme, List.of(btnLightTheme, btnDarkTheme));
        });
    }

    private void setupActions() {
        boolean hasInfoAction = onInformationAction != null;
        boolean hasUpdateAction = onCheckUpdateAction != null;
        boolean hasAnyAction = hasInfoAction || hasUpdateAction;

        lblActions.setManaged(hasAnyAction);
        lblActions.setVisible(hasAnyAction);
        actionsColumn.setManaged(hasAnyAction);
        actionsColumn.setVisible(hasAnyAction);

        btnInfo.setManaged(hasInfoAction);
        btnInfo.setVisible(hasInfoAction);
        if (hasInfoAction) {
            btnInfo.setText(I18n.get("top.info"));
            btnInfo.setOnAction(e -> {
                onInformationAction.run();
                hidePopupAction.run();
            });
        }

        btnUpdate.setManaged(hasUpdateAction);
        btnUpdate.setVisible(hasUpdateAction);
        if (hasUpdateAction) {
            btnUpdate.setText(I18n.get("top.update"));
            btnUpdate.setOnAction(e -> {
                onCheckUpdateAction.run();
                hidePopupAction.run();
            });
        }
    }

    private void switchLanguageIfNeeded(String languageTag) {
        if (languageTag.equals(I18n.getLocale().getLanguage())) return;
        Language language = LANG_EN.equals(languageTag) ? Language.EN : Language.VI;
        saveUserConfig(null, language);

        prepareReloadAction.run();
        I18n.setLocale(Locale.forLanguageTag(languageTag));
        reloadUiAction.run();
    }

    private void switchTheme(String theme) {
        Theme themeEnum = ThemeManager.THEME_DARK.equals(theme) ? Theme.DARK : Theme.LIGHT;
        saveUserConfig(themeEnum, null);
        ThemeManager.setTheme(theme);
        ThemeManager.apply(MainApp.getScene());
        refreshPopupAction.run();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveUserConfig(Theme theme, Language language) {
        try {
            Long userId = Session.getUser().getId();
            if (theme != null) {
                userSettingService.saveTheme(userId, theme);
            }
            if (language != null) {
                userSettingService.saveLanguage(userId, language);
            }
        } catch (Exception e) {
            //  Do not block the UI if the database save fails
        }
    }

    private void bindEqualButtonWidths(HBox row, Button... buttons) {
        row.setMaxWidth(Double.MAX_VALUE);
        row.prefWidthProperty().bind(panel.widthProperty());

        double gaps = row.getSpacing() * (buttons.length - 1);
        for (Button button : buttons) {
            button.setMaxWidth(Double.MAX_VALUE);
            button.prefWidthProperty().bind(
                    Bindings.max(0, row.widthProperty().subtract(gaps).divide(buttons.length)));
        }
    }

    private void applyActiveButton(Button activeButton, List<Button> buttons) {
        buttons.forEach(button -> button.getStyleClass().remove(ACTIVE_BUTTON));
        if (!activeButton.getStyleClass().contains(ACTIVE_BUTTON)) {
            activeButton.getStyleClass().add(ACTIVE_BUTTON);
        }
    }
}