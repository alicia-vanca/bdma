package com.app.auth.totp.dtos;

/**
 * Runtime labels and actions for reusing the OTP view in login and protected
 * developer actions without creating separate dialog layouts.
 *
 * @param titleKey           i18n key shown as the dialog window title
 * @param headerKey          i18n key shown inside the OTP dialog header
 * @param cancelTextKey      i18n key shown on the cancel button
 * @param onVerified         action executed after a successful OTP verification
 * @param onCancel           action executed when the cancel button is pressed
 */
public record TotpDialogContext(
        String titleKey,
        String headerKey,
        String cancelTextKey,
        Runnable onVerified,
        Runnable onCancel) {

    public static TotpDialogContext login(Runnable onVerified, Runnable onCancel) {
        return new TotpDialogContext("totp.title", "totp.title", "common.back", onVerified, onCancel);
    }
}
