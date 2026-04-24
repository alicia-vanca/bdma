package com.app.admin.settingsdialog.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.services.DriveResolverService;
import com.app.common.services.FileService;

@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);

    private final FolderManagerService folderManager;
    private final FileService fileService;
    private final DriveResolverService driveResolverService;

    public RestoreService(FolderManagerService folderManager, FileService fileService,
            DriveResolverService driveResolverService) {
        this.folderManager = folderManager;
        this.fileService = fileService;
        this.driveResolverService = driveResolverService;
    }

    /**
     * Restores all files from backup → data.
     * - Copies all files from backupDir to dataDir (REPLACE_EXISTING)
     * - Batch updates DB: synced_path and backed_up_path to the new directory
     */
    public RestoreResult restoreAll() {
        File backupDir = folderManager.getBackupDir();
        File dataDir = folderManager.getDataDir();

        if (backupDir == null) {
            return RestoreResult.failure("Backup directory not configured");
        }
        if (dataDir == null) {
            return RestoreResult.failure("Data directory not configured");
        }

        List<String> restoredRelativeNames = new ArrayList<>();

        try {
            folderManager.withDataAndBackupUnlocked(() -> {
                if (!backupDir.exists()) {
                    throw new IOException("Backup directory not found after unlock: "
                            + backupDir.getAbsolutePath());
                }

                List<Path> allFiles;
                try (var stream = Files.walk(backupDir.toPath())) {
                    allFiles = stream.filter(Files::isRegularFile).toList();
                }

                for (Path src : allFiles) {
                    Path relativeToBackup = backupDir.toPath().relativize(src);
                    Path dest = dataDir.toPath().resolve(relativeToBackup);
                    try {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                        restoredRelativeNames.add(relativeToBackup.toString());
                        log.info("Restored: {}", relativeToBackup);
                    } catch (IOException e) {
                        log.error("Failed to restore: {} | reason: {}", relativeToBackup, e.getMessage(), e);
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.error("Restore failed: {}", e.getMessage(), e);
            return RestoreResult.failure(e.getMessage());
        }

        if (!restoredRelativeNames.isEmpty()) {
            updateDbPaths(restoredRelativeNames);
            driveResolverService.invalidateCache();
        }

        log.info("Restore completed: {} file(s)", restoredRelativeNames.size());
        return RestoreResult.success(restoredRelativeNames.size());
    }

    /**
     * Batch updates synced_path and backed_up_path in DB
     * based on the new data/backup directories configured in FolderManagerService.
     */
    private void updateDbPaths(List<String> relativeNames) {
        String newDataRelative = folderManager.stripDriveLetter(
                folderManager.getDataDir().getAbsolutePath());
        String newBackupRelative = folderManager.stripDriveLetter(
                folderManager.getBackupDir().getAbsolutePath());

        fileService.batchUpdatePaths(relativeNames, newDataRelative, newBackupRelative);
    }

    public record RestoreResult(boolean success, int count, String errorMessage) {
        public static RestoreResult success(int count) {
            return new RestoreResult(true, count, null);
        }

        public static RestoreResult failure(String message) {
            return new RestoreResult(false, 0, message);
        }
    }
}
