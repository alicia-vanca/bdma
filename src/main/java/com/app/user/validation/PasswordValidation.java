package com.app.user.validation;

import com.app.common.exception.AppException;
import com.app.common.helper.SpringContextHolder;
import com.app.common.i18n.I18n;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.Objects;

// Centralize password + confirmation rules so user creation, user editing,
// and account self-service all enforce the same behavior.
public final class PasswordValidation {

    private static final String PASSWORD_POLICY_MESSAGE_KEY = "{user.account.password.policy}";

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

        validatePasswordPolicy(password);

        return true;
    }

    // Delegate policy checks to Bean Validation so this rule stays declarative and
    // can be reused consistently across all password update flows.
    private static void validatePasswordPolicy(String password) {
        Validator validator = SpringContextHolder.getBean(Validator.class);
        Set<ConstraintViolation<PasswordCandidate>> violations = validator.validate(new PasswordCandidate(password));

        if (!violations.isEmpty()) {
            ConstraintViolation<PasswordCandidate> violation = violations.iterator().next();
            throw new AppException(resolveViolationMessage(violation));
        }
    }

    // Bean Validation resolves from ValidationMessages by default, while this app
    // keeps UI strings in I18n bundles; map {key} templates through I18n.
    private static String resolveViolationMessage(ConstraintViolation<?> violation) {
        String template = violation.getMessageTemplate();
        if (template != null && template.startsWith("{") && template.endsWith("}")) {
            String key = template.substring(1, template.length() - 1);
            return I18n.get(key);
        }
        return violation.getMessage();
    }

    private record PasswordCandidate(
            @Size(min = 8, message = PASSWORD_POLICY_MESSAGE_KEY) @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = PASSWORD_POLICY_MESSAGE_KEY) String value) {
    }
}