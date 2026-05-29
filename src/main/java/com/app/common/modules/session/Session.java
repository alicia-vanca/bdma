package com.app.common.modules.session;

import com.app.common.definitions.enums.Role;
import com.app.common.models.User;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Session bean for storing the current user context.
 */
@Component
@Scope("singleton")
public class Session {

    private static final long DEV_OTP_VALID_MS = 5 * 60 * 1000L;

    private User currentUser;
    private User pendingDevUser;
    private long devOtpValidUntilMs;
    private boolean createPatchUnlocked;

    public void setUser(User user) {
        this.currentUser = user;
    }

    public User getUser() {
        return currentUser;
    }

    /**
     * Stages a developer account after password validation and before TOTP
     * verification completes.
     *
     * @param user the developer user pending second-factor verification
     */
    public void setPendingDevUser(User user) {
        this.pendingDevUser = user;
    }

    public User consumePendingDevUser() {
        User user = pendingDevUser;
        pendingDevUser = null;
        return user;
    }

    /**
     * Resets the developer OTP freshness window after a successful challenge.
     * Protected developer actions should call this only after TOTP verification.
     */
    public void renewDevOtpTimeout() {
        devOtpValidUntilMs = System.currentTimeMillis() + DEV_OTP_VALID_MS;
    }

    public boolean isDevOtpFresh() {
        return isDev() && System.currentTimeMillis() < devOtpValidUntilMs;
    }

    public boolean hasDevOtpTimeoutStarted() {
        return devOtpValidUntilMs > 0L;
    }

    /**
     * Returns how long the current developer OTP challenge remains valid.
     *
     * @return remaining milliseconds, or zero when expired
     */
    public long getDevOtpRemainingMs() {
        return Math.max(0L, devOtpValidUntilMs - System.currentTimeMillis());
    }

    public void unlockCreatePatch() {
        createPatchUnlocked = true;
    }

    public boolean isCreatePatchUnlocked() {
        return createPatchUnlocked;
    }

    public void clear() {
        currentUser = null;
        pendingDevUser = null;
        devOtpValidUntilMs = 0L;
        createPatchUnlocked = false;
    }

    public boolean isAdmin() {
        return currentUser != null && currentUser.getRole() == Role.ADMIN;
    }

    public boolean isDev() {
        return currentUser != null && currentUser.getRole() == Role.DEV;
    }

    public Long getCurrentUserId() {
        if (currentUser == null) {
            return 0L;
        }
        return currentUser.getId();
    }
}
