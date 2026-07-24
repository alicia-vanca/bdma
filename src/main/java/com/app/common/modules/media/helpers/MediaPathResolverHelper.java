package com.app.common.modules.media.helpers;

import java.io.IOException;
import java.nio.file.Path;

import com.app.common.dtos.FileView;
import com.app.common.modules.datarestore.services.RestoreService;
import com.app.common.modules.datarestore.services.RestoreService.BackupSyncResult;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.services.FolderManagerService;

public final class MediaPathResolverHelper {

    private final FolderManagerService folderManagerService;
    private final RestoreService restoreService;

    public MediaPathResolverHelper(FolderManagerService folderManagerService, RestoreService restoreService) {
        this.folderManagerService = folderManagerService;
        this.restoreService = restoreService;
    }

    public Path resolve(FileView file) throws IOException {
        Path resolvedPath = resolveSyncedPath(file);
        if (resolvedPath != null) {
            return resolvedPath;
        }
        return restoreMissingMediaFile(file);
    }

    private Path resolveSyncedPath(FileView file) {
        PathResolutionResult result = folderManagerService
                .findAbsolutePathFromNonDriveLetterPath(file.syncedPath(), file.fileSize());
        return result.isFound() ? result.getPath() : null;
    }

    private Path restoreMissingMediaFile(FileView file) throws IOException {
        if (file.backedUpPath() == null || file.backedUpPath().isBlank()) {
            throw new IOException("Backup path is missing for media file: " + file.name());
        }
        if (folderManagerService.getSyncDir() == null) {
            throw new IOException("Sync folder is not configured");
        }

        BackupSyncResult result = restoreService.restoreSingleFile(
                folderManagerService.getSyncDir().getAbsolutePath(),
                file.backedUpPath());
        if (!result.success()) {
            String message = result.errorMessage() != null ? result.errorMessage() : "Media restore failed";
            throw new IOException(message);
        }
        if (result.restoredPath() == null || result.restoredPath().isBlank()) {
            throw new IOException("Media file restored without destination path: " + file.name());
        }
        return Path.of(result.restoredPath());
    }
}
