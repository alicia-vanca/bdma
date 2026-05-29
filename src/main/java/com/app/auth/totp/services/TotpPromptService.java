package com.app.auth.totp.services;

import org.springframework.stereotype.Component;

import com.app.auth.totp.controllers.TotpController;
import com.app.auth.totp.dtos.TotpDialogContext;
import com.app.common.definitions.ViewPaths;
import com.app.common.helpers.CssLoader;
import com.app.common.helpers.DialogHelper;
import com.app.common.modules.i18n.I18n;

import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Opens the shared OTP FXML as a modal challenge for protected developer
 * actions while preserving the login OTP screen as the same reusable view.
 */
@Component
public class TotpPromptService {
    public boolean prompt(
            String titleKey,
            String headerKey,
            String cancelTextKey,
            Runnable onVerified,
            Runnable onCancel) {
        DialogHelper.Dialog<TotpController> dialog = DialogHelper.createDialog(
                ViewPaths.TOTP,
                I18n.get(titleKey),
                Modality.APPLICATION_MODAL);
        Stage stage = dialog.stage();
        stage.setWidth(480);
        stage.setHeight(360);
        stage.setResizable(false);
        CssLoader.applyLogin(stage.getScene());
        boolean[] verified = { false };

        dialog.controller().configureTotpContext(new TotpDialogContext(
                titleKey,
                headerKey,
                cancelTextKey,
                false,
                () -> {
                    verified[0] = true;
                    if (onVerified != null) {
                        onVerified.run();
                    }
                    stage.close();
                },
                () -> {
                    if (onCancel != null) {
                        onCancel.run();
                    }
                    stage.close();
                }));

        stage.setOnCloseRequest(event -> {
            if (!verified[0]) {
                dialog.controller().handleCancellation();
            }
        });
        stage.showAndWait();
        return verified[0];
    }
}
