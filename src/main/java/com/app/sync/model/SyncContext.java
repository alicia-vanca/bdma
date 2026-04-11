package com.app.sync.model;

/**
 * Represents the authenticated user context used during sync.
 * - ADMIN: canSync() always returns true → sync all files
 * - USER : canSync() returns true only if username matches file owner
 */
public record SyncContext(
        String username,
        boolean isAdmin
) {
    public boolean canSync(String fileUserName) {
        if (isAdmin) return true;
        return username.equals(fileUserName);
    }
}
