package com.app.user.validation;

import com.app.common.exception.AppException;
import com.app.common.i18n.I18n;

import java.util.Objects;

// Centralize password + confirmation rules so user creation, user editing,
// and account self-service all enforce the same behavior.
public final class PasswordValidation {

    private PasswordValidation() {
    }

    // Returns true when a password change should be applied. Optional flows may
    // leave both fields blank, while required flows must provide and confirm a
    // password.
    public static boolean validate(String password, String passwordConfirm, boolean passwordRequired) {
        boolean hasPassword = password != null && !password.isBlank();
        boolean hasPasswordConfirm = passwordConfirm != null && !passwordConfirm.isBlank();

        if (!hasPassword && !hasPasswordConfirm) {
            if (passwordRequired) {
                throw new AppException(I18n.get("user.account.password.required"));
            }
            return false;
        }

        if (!hasPassword) {
            throw new AppException(I18n.get("user.account.password.required"));
        }

        if (!hasPasswordConfirm) {
            throw new AppException(I18n.get("user.account.password.confirm.required"));
        }

        if (!Objects.equals(password, passwordConfirm)) {
            throw new AppException(I18n.get("user.account.password.mismatch"));
        }

        return true;
    }
}