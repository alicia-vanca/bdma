
package com.app.common.modules.settingspopup.helpers;

import com.app.common.definitions.ViewPaths;
import com.app.common.helpers.SpringContextHolder;
import com.app.common.modules.session.Session;
import com.app.common.modules.settingspopup.controllers.SettingsPopupController;
import com.app.MainApp;
import com.app.common.modules.i18n.I18n;
import com.app.common.services.UserSettingService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

// Manage the shared settings popup so login/admin flows reuse the same language,
// theme, sizing, and reopen behavior.
public class SettingsPopupHelper {

    private static final Logger log = LoggerFactory.getLogger(SettingsPopupHelper.class);

    private static final Map<String, PopupState> POPUP_STATES = new HashMap<>();

    private final String stateKey;
    private final Button anchorButton;
    private final PopupAnchorY popupAnchorY;
    private final UserSettingService userSettingService;
    private final Runnable reloadUiAction;
    private final Runnable onCheckUpdateAction;
    private final Runnable onInformationAction;

    private Popup settingsPopup;

    public SettingsPopupHelper(String stateKey,
            Button anchorButton,
            PopupAnchorY popupAnchorY,
            UserSettingService userSettingService, // ← nullable
            Runnable reloadUiAction,
            Runnable onCheckUpdateAction,
            Runnable onInformationAction) {
        this.stateKey = stateKey;
        this.anchorButton = anchorButton;
        this.popupAnchorY = popupAnchorY;
        this.userSettingService = userSettingService;
        this.reloadUiAction = reloadUiAction;
        this.onCheckUpdateAction = onCheckUpdateAction;
        this.onInformationAction = onInformationAction;
    }

    public void initialize() {
        settingsPopup = createSettingsPopup();

        PopupState state = state();
        if (!state.reopenAfterReload) {
            return;
        }

        state.reopenAfterReload = false;
        if (state.x != null && state.y != null) {
            Platform.runLater(this::reopenPopupAtSavedLocation);
        } else {
            Platform.runLater(this::showPopupIfAnchorReady);
        }
    }

    public void togglePopup() {
        if (settingsPopup != null && settingsPopup.isShowing()) {
            settingsPopup.hide();
            return;
        }

        showPopupIfAnchorReady();
    }

    public void refreshIfOpen() {
        if (!capturePopupLocationIfOpen()) {
            return;
        }

        settingsPopup.hide();
        reopenPopupAtSavedLocation();
    }

    public void prepareForReloadIfOpen() {
        state().reopenAfterReload = capturePopupLocationIfOpen();
    }

    private boolean capturePopupLocationIfOpen() {
        if (settingsPopup == null || !settingsPopup.isShowing()) {
            return false;
        }

        rememberPopupLocation(settingsPopup.getX(), settingsPopup.getY());
        return true;
    }

    private void rememberPopupLocation(double x, double y) {
        PopupState state = state();
        state.x = x;
        state.y = y;
    }

    private void reopenPopupAtSavedLocation() {
        PopupState state = state();
        if (state.x == null || state.y == null) {
            return;
        }

        settingsPopup = createSettingsPopup();
        settingsPopup.show(anchorButton, state.x, state.y);
        rememberPopupLocation(settingsPopup.getX(), settingsPopup.getY());
    }

    private boolean showPopupIfAnchorReady() {
        Bounds anchor = anchorButton.localToScreen(anchorButton.getBoundsInLocal());
        if (anchor == null || anchor.getWidth() <= 0 || anchor.getHeight() <= 0) {
            return false;
        }

        settingsPopup = createSettingsPopup();
        settingsPopup.show(anchorButton, anchor.getMaxX() + 8, resolveAnchorY(anchor));
        rememberPopupLocation(settingsPopup.getX(), settingsPopup.getY());
        return true;
    }

    private double resolveAnchorY(Bounds anchor) {
        return popupAnchorY == PopupAnchorY.BOTTOM ? anchor.getMaxY() : anchor.getMinY();
    }

    private Popup createSettingsPopup() {
        Popup popup = new Popup();
        popup.setAutoFix(true);
        popup.setAutoHide(true);
        VBox panel = loadSettingsPanel();
        if (panel != null) {
            popup.getContent().add(panel);
        }
        return popup;
    }

    // Load popup content from FXML so structure/style changes stay in resources
    // instead of Java code.
    private VBox loadSettingsPanel() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(ViewPaths.SETTINGS_POPUP), I18n.getBundle());
            loader.setController(new SettingsPopupController(
                    SpringContextHolder.getBean(Session.class),
                    userSettingService != null ? userSettingService
                            : SpringContextHolder.getBean(UserSettingService.class),
                    new SettingsPopupController.Actions(
                            reloadUiAction,
                            this::prepareForReloadIfOpen,
                            this::refreshIfOpen,
                            this::hidePopup,
                            onCheckUpdateAction,
                            onInformationAction)));

            VBox panel = loader.load();
            if (MainApp.getScene() != null) {
                panel.getStylesheets().setAll(MainApp.getScene().getStylesheets());
            }
            return panel;
        } catch (Exception e) {
            log.error("Failed to load settings popup", e);
            return null;
        }
    }

    private void hidePopup() {
        if (settingsPopup != null) {
            settingsPopup.hide();
        }
    }

    private PopupState state() {
        return POPUP_STATES.computeIfAbsent(stateKey, key -> new PopupState());
    }

    private static final class PopupState {
        private boolean reopenAfterReload;
        private Double x;
        private Double y;
    }

    public enum PopupAnchorY {
        TOP,
        BOTTOM
    }
}
