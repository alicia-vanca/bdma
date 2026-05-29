package com.app.auth.totp.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.MainApp;
import com.app.auth.totp.dtos.TotpDialogContext;
import com.app.auth.totp.services.TotpService;
import com.app.common.definitions.ViewPaths;
import com.app.common.helpers.CssLoader;
import com.app.common.helpers.SpringContextHolder;
import com.app.common.helpers.ViewLoader;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.models.User;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.preloginsettingspopup.helpers.PreLoginSettingsPopupHelper;
import com.app.common.modules.session.Session;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

@Component
public class TotpController {

    private static final Logger log = LoggerFactory.getLogger(TotpController.class);

    @FXML
    private Label lblHeader;
    @FXML
    private TextField digit1;
    @FXML
    private TextField digit2;
    @FXML
    private TextField digit3;
    @FXML
    private TextField digit4;
    @FXML
    private TextField digit5;
    @FXML
    private TextField digit6;
    @FXML
    private Label message;
    @FXML
    private Button btnBack;
    @FXML
    private Button btnSettings;

    private final TotpService totpService;
    private final Session session;
    private final AppUpdateController appUpdateController;
    private TextField[] digitFields;
    private PreLoginSettingsPopupHelper settingsPopupHelper;
    private TotpDialogContext context;
    private boolean updatingFields;
    private boolean verifying;
    private boolean cancellationHandled;
    private boolean windowCloseHandlerRegistered;

    public TotpController(TotpService totpService,
            Session session,
            AppUpdateController appUpdateController) {
        this.totpService = totpService;
        this.session = session;
        this.appUpdateController = appUpdateController;
    }

    @FXML
    public void initialize() {
        appUpdateController.setOnStatusChange(null);

        context = TotpDialogContext.login(this::completeLogin, MainApp::showLogin);
        settingsPopupHelper = new PreLoginSettingsPopupHelper(
                "totp",
                btnSettings,
                PreLoginSettingsPopupHelper.PopupAnchorY.TOP,
                null,
                this::reloadUI,
                appUpdateController::onCheckUpdateManual,
                null);
        settingsPopupHelper.initialize();

        digitFields = new TextField[] { digit1, digit2, digit3, digit4, digit5, digit6 };
        applyContext();
        hideError();
        setupDigitFields();
        Platform.runLater(() -> {
            registerWindowCloseHandler();
            logOtpShown();
            digit1.requestFocus();
        });
    }

    /**
     * Applies labels and callbacks after the shared OTP view is loaded as a modal
     * challenge for a specific protected action.
     *
     * @param context labels and callbacks for the current OTP challenge
     */
    public void configureTotpContext(TotpDialogContext context) {
        this.context = context == null ? this.context : context;
        cancellationHandled = false;
        applyContext();
    }

    private void applyContext() {
        if (context == null) {
            return;
        }
        lblHeader.setText(I18n.get(context.headerKey()));
        btnBack.setText(I18n.get(context.cancelTextKey()));
        btnSettings.setVisible(context.settingsBtnVisible());
        btnSettings.setManaged(context.settingsBtnVisible());
    }

    private void logOtpShown() {
        if (context != null) {
            log.info("OTP shown: [ {} - {} ]", otpTitle(), otpHeader());
        }
    }

    /**
     * Hooks the containing window close button into the same cancel path as the
     * visible cancel button so audit logs stay consistent for all exit methods.
     */
    private void registerWindowCloseHandler() {
        if (windowCloseHandlerRegistered || digit1.getScene() == null) {
            return;
        }

        Window window = digit1.getScene().getWindow();
        if (window == null) {
            return;
        }

        window.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, event -> handleCancellation());
        windowCloseHandlerRegistered = true;
    }

    private String otpTitle() {
        return context == null ? "unknown" : I18n.get(context.titleKey());
    }

    private String otpHeader() {
        return context == null ? "unknown" : I18n.get(context.headerKey());
    }

    private void setupDigitFields() {
        for (int i = 0; i < digitFields.length; i++) {
            TextField field = digitFields[i];
            int index = i;

            field.textProperty().addListener((obs, oldValue, newValue) -> handleDigitChanged(index, newValue));
            field.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.BACK_SPACE && field.getText().isEmpty() && index > 0) {
                    digitFields[index - 1].requestFocus();
                    digitFields[index - 1].selectAll();
                }
            });
            field.setOnAction(event -> verifyIfComplete());
        }
    }

    private void handleDigitChanged(int index, String value) {
        if (updatingFields) {
            return;
        }

        hideError();
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        if (digits.length() > 1) {
            fillFrom(index, digits);
            return;
        }

        updatingFields = true;
        try {
            digitFields[index].setText(digits);
        } finally {
            updatingFields = false;
        }

        if (!digits.isEmpty()) {
            focusNext(index);
        }
        verifyIfComplete();
    }

    private void fillFrom(int startIndex, String digits) {
        updatingFields = true;
        try {
            int target = startIndex;
            for (int i = 0; i < digits.length() && target < digitFields.length; i++, target++) {
                digitFields[target].setText(String.valueOf(digits.charAt(i)));
            }
            if (target < digitFields.length) {
                digitFields[target].requestFocus();
            } else {
                digitFields[digitFields.length - 1].requestFocus();
            }
        } finally {
            updatingFields = false;
        }
        verifyIfComplete();
    }

    private void focusNext(int index) {
        if (index < digitFields.length - 1) {
            digitFields[index + 1].requestFocus();
        }
    }

    private void verifyIfComplete() {
        if (verifying) {
            return;
        }
        String code = currentCode();
        if (code.length() == digitFields.length) {
            handleVerify();
        }
    }

    @FXML
    private void handleVerify() {
        String code = currentCode();
        if (code.length() != digitFields.length) {
            return;
        }

        String title = otpTitle();
        String header = otpHeader();
        verifying = true;
        try {
            if (!totpService.verify(code)) {
                log.warn("OTP invalid: [ {} - {} ]", title, header);
                showError();
                clearCode();
                digit1.requestFocus();
                return;
            }

            cancellationHandled = true;
            log.info("OTP validated: [ {} - {} ]", title, header);
            if (context != null && context.onVerified() != null) {
                context.onVerified().run();
            }
        } finally {
            verifying = false;
        }
    }

    private void completeLogin() {
        User devUser = session.consumePendingDevUser();
        if (devUser == null) {
            log.warn("TOTP verification attempted without pending dev user");
            showError();
            clearCode();
            digit1.requestFocus();
            return;
        }

        session.setUser(devUser);
        log.info("Dev user logged in successfully");
        MainApp.showAdmin();
    }

    @FXML
    private void handleBack() {
        handleCancellation();
    }

    /**
     * Runs cancellation once regardless of whether user clicks cancel/back or
     * closes the OTP window from the title bar.
     */
    public void handleCancellation() {
        if (cancellationHandled) {
            return;
        }

        cancellationHandled = true;
        log.info("OTP cancelled: [ {} - {} ]", otpTitle(), otpHeader());
        if (context != null && context.onCancel() != null) {
            context.onCancel().run();
        }
    }

    @FXML
    private void openSettingsPopup() {
        settingsPopupHelper.togglePopup();
    }

    private void reloadUI() {
        try {
            ViewLoader viewLoader = SpringContextHolder.getBean(ViewLoader.class);
            var result = viewLoader.loadView(ViewPaths.TOTP);
            if (result != null) {
                Parent root = (Parent) result.node();
                MainApp.getScene().setRoot(root);
                CssLoader.applyLogin(MainApp.getScene());
            }
        } catch (Exception e) {
            log.error("Failed to reload TOTP UI", e);
        }
    }

    private void showError() {
        message.setText(I18n.get("totp.error.invalid"));
        message.setVisible(true);
    }

    private void hideError() {
        message.setVisible(false);
    }

    private String currentCode() {
        StringBuilder builder = new StringBuilder(digitFields.length);
        for (TextField field : digitFields) {
            String text = field.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            builder.append(text);
        }
        return builder.toString();
    }

    private void clearCode() {
        updatingFields = true;
        try {
            for (TextField field : digitFields) {
                field.clear();
            }
        } finally {
            updatingFields = false;
        }
    }
}
