package com.app.common.dtos;

import java.io.File;

/**
 * Represents the authenticated user and device context used during sync.
 * - ADMIN: can sync files only from the connected camera
 * - USER : can sync files only when username and camera ID both match
 * - saveDir: captured once when sync starts, unaffected by mid-sync changes
 * - autoDelete: captured once when sync starts, unaffected by mid-sync changes
 * - deviceName: captured once when sync starts for display purposes
 * - hardwareId: connected device hardware ID used for ADB/device access
 * - cameraId: validated camera ID used for permissions and sync queue root rows
 */
public record SyncContext(
        String username,
        boolean isAdmin,
        File saveDir,
        boolean autoDelete,
        String deviceName,
        String hardwareId,
        String cameraId) {
    /**
     * Determines whether a file is eligible for this sync context.
     *
=     * @param fileCameraId camera ID encoded in the remote filename
     * @return true when the file belongs to the connected camera and, for non-admins,
     *         the authenticated user
     */
    public boolean canSync(String fileCameraId) {
        return cameraId != null && cameraId.equals(fileCameraId);
    }
}
