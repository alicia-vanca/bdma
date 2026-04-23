package com.app.common.dtos;

import java.io.File;

/**
 * Represents the authenticated user context used during sync.
 * - ADMIN: canSync() always returns true → sync all files
 * - USER : canSync() returns true only if username matches file owner
 * - saveDir: captured once when sync starts, unaffected by mid-sync changes
 * - autoDelete: captured once when sync starts, unaffected by mid-sync changes
 */
public record SyncContext(
        String username,
        boolean isAdmin,
        File saveDir,
        boolean autoDelete) {
    public boolean canSync(String fileUserName) {
        if (isAdmin)
            return true;
        return username.equals(fileUserName);
    }
}
