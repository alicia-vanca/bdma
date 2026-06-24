package com.app.auth.totp.services;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.app.common.helpers.AlertHelper;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.util.Duration;

/**
 * Owns developer OTP timeout state and prompts so layout controllers do not
 * carry
 * authentication flow details.
 */
@Component
public class DevOtpGuardService {

    private static final String I18N_COMMON_YES = "common.yes";
    private static final String I18N_COMMON_NO = "common.no";

    private final Session session;
    private final TotpPromptService totpPromptService;
    private final PauseTransition devOtpTimeoutPrompt = new PauseTransition();
    private Runnable logoutAction = () -> {
    };
    private boolean devOtpPromptVisible;
    private boolean timeoutPromptPending;

    public DevOtpGuardService(Session session, TotpPromptService totpPromptService) {
        this.session = session;
        this.totpPromptService = totpPromptService;
    }

    /**
     * Starts developer OTP timeout tracking without extending an already active
     * timeout during UI reloads.
     */
    public void start(Runnable logoutAction) {
        this.logoutAction = logoutAction == null ? () -> {
        } : logoutAction;
        if (!session.isDev()) {
            return;
        }
        if (!session.hasDevOtpTimeoutStarted()) {
            session.renewDevOtpTimeout();
        }
        scheduleTimeoutPrompt();
    }

    public void stop() {
        devOtpTimeoutPrompt.stop();
        devOtpPromptVisible = false;
        timeoutPromptPending = false;
    }

    public boolean promptCreatePatchUnlock() {
        boolean verified = promptProtectedAction(session::unlockCreatePatch);
        if (verified || !session.isDevOtpFresh()) {
            scheduleTimeoutPrompt();
        }
        return verified;
    }

    /**
     * Requires a fresh developer OTP challenge before a protected in-session
     * action. Successful verification renews the developer timeout window.
     *
     * @param onVerified action to run after OTP verification succeeds
     * @return {@code true} when verification succeeded
     */
    public boolean promptProtectedAction(Runnable onVerified) {
        return showDevOtpPrompt(
                "totp.dev.lockedFeature.title",
                "totp.dev.lockedFeature.header",
                "common.cancel",
                () -> {
                    if (onVerified != null) {
                        onVerified.run();
                    }
                    session.renewDevOtpTimeout();
                },
                null);
    }

    private void scheduleTimeoutPrompt() {
        devOtpTimeoutPrompt.stop();
        if (!session.isDev()) {
            timeoutPromptPending = false;
            return;
        }
        long remainingMs = session.getDevOtpRemainingMs();
        if (remainingMs <= 0L) {
            Platform.runLater(this::promptExpiredDevOtpFromTimeout);
            return;
        }
        devOtpTimeoutPrompt.setDuration(Duration.millis(remainingMs));
        devOtpTimeoutPrompt.setOnFinished(event -> Platform.runLater(this::promptExpiredDevOtpFromTimeout));
        devOtpTimeoutPrompt.playFromStart();
    }

    private void promptExpiredDevOtpFromTimeout() {
        if (!session.isDev() || session.isDevOtpFresh()) {
            timeoutPromptPending = false;
            return;
        }
        if (devOtpPromptVisible) {
            timeoutPromptPending = true;
            return;
        }
        timeoutPromptPending = false;
        if (showDevOtpPrompt(
                "totp.dev.session.title",
                "totp.dev.session.header",
                "top.logout",
                session::renewDevOtpTimeout,
                this::confirmDevOtpLogout)) {
            scheduleTimeoutPrompt();
        }
    }

    private void confirmDevOtpLogout() {
        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("totp.dev.logout.confirm.title"),
                I18n.get("totp.dev.logout.confirm.header"),
                I18n.get("totp.dev.logout.confirm.content"));

        ButtonType yesButton = new ButtonType(I18n.get(I18N_COMMON_YES), ButtonBar.ButtonData.YES);
        ButtonType noButton = new ButtonType(I18n.get(I18N_COMMON_NO), ButtonBar.ButtonData.NO);
        AlertHelper.setButtons(confirm, yesButton, noButton);

        Optional<ButtonType> chosen = confirm.showAndWait();
        if (chosen.isPresent() && chosen.get() == yesButton) {
            logoutAction.run();
        } else {
            scheduleTimeoutPrompt();
        }
    }

    private boolean showDevOtpPrompt(
            String titleKey,
            String headerKey,
            String cancelTextKey,
            Runnable onVerified,
            Runnable onCancel) {
        devOtpPromptVisible = true;
        try {
            return totpPromptService.prompt(titleKey, headerKey, cancelTextKey, onVerified, onCancel);
        } finally {
            devOtpPromptVisible = false;
            if (timeoutPromptPending && session.isDev() && !session.isDevOtpFresh()) {
                Platform.runLater(this::promptExpiredDevOtpFromTimeout);
            } else if (session.isDevOtpFresh()) {
                timeoutPromptPending = false;
            }
        }
    }
}
