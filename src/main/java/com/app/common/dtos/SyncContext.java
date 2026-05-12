package com.app.common.dtos;

import java.io.File;

/**
 * Represents the authenticated user context used during sync.
 * - ADMIN: canSync() always returns true → sync all files
 * - USER : canSync() returns true only if username matches file owner
 * - saveDir: captured once when sync starts, unaffected by mid-sync changes
 * - autoDelete: captured once when sync starts, unaffected by mid-sync changes
 * - deviceName: captured once when sync starts for display purposes
 */
public record SyncContext(
        String username,
        boolean isAdmin,
        File saveDir,
        boolean autoDelete,
        String deviceName) {
    public boolean canSync(String fileUserName) {
        if (isAdmin)
            return true;
        return username.equals(fileUserName);
    }
}
