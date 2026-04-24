package com.app.common.definitions.enums;

/**
 * Represents the outcome of a login attempt.
 * Use these states to communicate whether authentication succeeded
 * or why access was denied.
 */
public enum LoginResult {
    /**
     * Login completed successfully and the user may proceed into the application.
     */
    SUCCESS,

    /**
     * Login failed because the supplied identity or password did not match
     * an active account. Use this for standard authentication failures.
     */
    INVALID_CREDENTIALS,

    /**
     * Login failed because the account exists but is not allowed to sign in
     * due to being deactivated. Use this instead of invalid credentials when
     * the account status is the reason access is denied.
     */
    ACCOUNT_DEACTIVATED
}
