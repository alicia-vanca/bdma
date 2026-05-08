package com.app.admin.settingsdialog.services;

import com.app.common.definitions.enums.SyncDirection;
import com.app.common.dtos.StorageProgress;
import com.app.common.models.BackupRestoreFailure;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.foldermanager.services.FolderSecurityService;
import com.app.common.modules.i18n.I18n;
import com.app.common.services.DriveResolverService;
import com.app.common.services.FileService;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.app.common.definitions.AppConstants.MAX_RETRY;
import static com.app.common.utils.FileUtil.extractDriveLetter;

@Service
public class BackupSyncService {

    private static final Logger log = LoggerFactory.getLogger(BackupSyncService.class);

    private final FolderManagerService folderManager;
    private final FileService fileService;
    private final DriveResolverService driveResolverService;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    @Getter
    private final StorageProgress currentProgress = new StorageProgress();
    @Getter
    private List<BackupRestoreFailure> lastFailures = new ArrayList<>();

    public BackupSyncService(FolderManagerService folderManager,
                             FileService fileService,
                             DriveResolverService driveResolverService) {
        this.folderManager = folderManager;
        this.fileService = fileService;
        this.driveResolverService = driveResolverService;
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public boolean isCancelled() {
        return isCancelled.get();
    }

    public void cancel() {
        isCancelled.set(true);
        currentProgress.reset();
        lastFailures = new ArrayList<>();
    }

    private void resetCancelled() {
        isCancelled.set(false);
    }

    /**
     * backup → data
     */
    public BackupSyncResult restore() {
        return run(SyncDirection.BACKUP_TO_DATA);
    }

    /**
     * data → backup
     */
    public BackupSyncResult rebuild() {
        return run(SyncDirection.DATA_TO_BACKUP);
    }

    @Setter
    private Runnable onProgressInitialized;

    /**
     * Synchronizes files between backup and data directories.
     * - Copies all files from source to target (REPLACE_EXISTING)
     * - Batch updates DB: synced_path and backed_up_path to the new directory
     *
     * @param direction the sync direction (BACKUP_TO_DATA or DATA_TO_BACKUP)
     * @return BackupSyncResult containing success status and count of processed files
     */
    public BackupSyncResult run(SyncDirection direction) {
        resetCancelled();
        // Lock
        if (!isRunning.compareAndSet(false, true)) {
            return BackupSyncResult.failure(I18n.get("setting.storage.error.already.running"));
        }
        try {
            File backupDir = folderManager.getBackupDir();
            File dataDir = folderManager.getDataDir();

            if (backupDir == null) {
                return BackupSyncResult.failure(I18n.get("setting.storage.error.backup.dir.not.configured"));
            }
            if (dataDir == null) {
                return BackupSyncResult.failure(I18n.get("setting.storage.error.data.dir.not.configured"));
            }

            File sourceDir = (direction == SyncDirection.BACKUP_TO_DATA) ? backupDir : dataDir;

            List<Path> allFiles = scanFiles(sourceDir);
            currentProgress.reset();
            currentProgress.init(direction, allFiles.size());
            if (onProgressInitialized != null) {
                onProgressInitialized.run();
            }
            Map<String, Long> userMap = fileService.batchResolveUsers(allFiles);
            Map<String, Long> deviceMap = fileService.batchResolveDevices(allFiles);

            BackupSyncResult result = syncFiles(
                    direction, dataDir, backupDir,
                    allFiles, userMap, deviceMap);

            driveResolverService.invalidateCache();

            return result;

        } catch (Exception e) {
            log.error("[{}] Failed: {}", direction, e.getMessage(), e);
            return BackupSyncResult.failure(e.getMessage());
        } finally {
            isRunning.set(false);
        }
    }

    private List<Path> scanFiles(File sourceDir) throws IOException {
        Path dirPath = sourceDir.toPath();
        FolderSecurityService.removeDenyAcl(dirPath);
        try (var stream = Files.walk(dirPath)) {
            return stream.filter(Files::isRegularFile).toList();
        } finally {
            FolderSecurityService.applyDenyAcl(dirPath);
        }
    }

    private BackupSyncResult syncFiles(
            SyncDirection direction,
            File dataDir, File backupDir,
            List<Path> allFiles,
            Map<String, Long> userMap,
            Map<String, Long> deviceMap) {

        String operation;
        File sourceDir;
        File targetDir;
        if (direction == SyncDirection.BACKUP_TO_DATA) {
            operation = "RESTORE";
            sourceDir = backupDir;
            targetDir = dataDir;
        } else {
            operation = "REBUILD";
            sourceDir = dataDir;
            targetDir = backupDir;
        }
        List<BackupRestoreFailure> failures = new ArrayList<>();

        for (int i = 0; i < allFiles.size(); i++) {
            Path srcPath = allFiles.get(i);

            if (isCancelled.get()) {
                log.info("[{}] Cancelled", operation);
                return BackupSyncResult.failure(I18n.get("setting.storage.error.cancelled"));
            }
            Path relative = sourceDir.toPath().relativize(srcPath);
            Path destPath = targetDir.toPath().resolve(relative);

            try {
                if (Files.exists(destPath) && Files.size(destPath) == Files.size(srcPath)) {
                    fileService.upsertFileFromRestore(
                            relative.toString(),
                            dataDir.getAbsolutePath(),
                            backupDir.getAbsolutePath(),
                            userMap, deviceMap);
                    currentProgress.incrementSuccess();
                    continue;
                }
                copyWithRetry(srcPath, destPath, relative);

                fileService.upsertFileFromRestore(
                        relative.toString(),
                        dataDir.getAbsolutePath(),
                        backupDir.getAbsolutePath(),
                        userMap, deviceMap);

                currentProgress.incrementSuccess();

            } catch (DiskFullException e) {
                String driveLetter = extractDriveLetter(destPath);

                log.error("[{}] Disk full on drive {}", operation, driveLetter);

                for (int j = i; j < allFiles.size(); j++) {

                    Path remainingSrc = allFiles.get(j);
                    Path remainingRelative = sourceDir.toPath().relativize(remainingSrc);

                    failures.add(buildFailure(
                            remainingRelative.getFileName().toString(),
                            operation,
                            driveLetter
                    ));
                    currentProgress.incrementFailed();
                }

                lastFailures = new ArrayList<>(failures);
                currentProgress.failed();
                return BackupSyncResult.failure(I18n.get("setting.storage.error.disk.full", driveLetter));

            } catch (IOException  e) {
                log.error("[{}] Failed: {}", operation, relative, e);
                failures.add(buildFailure(relative.getFileName().toString(), operation, e.getMessage()));
                currentProgress.incrementFailed();
            }
        }
        lastFailures = new ArrayList<>(failures);
        currentProgress.completed();
        return BackupSyncResult.success(currentProgress.getSuccess(), failures);
    }

    private void copyWithRetry(Path src, Path dest, Path relative) throws IOException {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            if (isCancelled.get()) {
                throw new IOException(I18n.get("setting.storage.error.cancelled"));
            }
            try {
                Files.createDirectories(dest.getParent());
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                if (Files.size(src) == Files.size(dest)) {
                    return;
                }
                throw new IOException("Size mismatch after copy: " + relative);

            } catch (IOException e) {
                if (isDiskFull(e)) {
                    throw new DiskFullException(e.getMessage());
                }
                log.warn("[Retry {}/{}] {}: {}", attempt, MAX_RETRY, relative, e.getMessage());
                if (attempt == MAX_RETRY) {
                    throw e;
                }
            }
        }
    }

    private boolean isDiskFull(IOException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("No space left") || msg.contains("There is not enough space"));
    }

    private BackupRestoreFailure buildFailure(String fileName, String operation, String errorMessage) {
        BackupRestoreFailure failure = new BackupRestoreFailure();
        failure.setFileName(fileName);
        failure.setOperation(operation);
        failure.setErrorMessage(errorMessage);
        failure.setRetryCount(MAX_RETRY);
        failure.setLastAttemptedAt(LocalDateTime.now().toString());
        failure.setStatus("PENDING_RETRY");
        return failure;
    }

    public record BackupSyncResult(boolean success, int count, String errorMessage,
                                   List<BackupRestoreFailure> failures) {

        public static BackupSyncResult success(int count, List<BackupRestoreFailure> failures) {
            return new BackupSyncResult(true, count, null, failures);
        }

        public static BackupSyncResult failure(String message) {
            return new BackupSyncResult(false, 0, message, List.of());
        }
    }

    private static class DiskFullException extends IOException {
        public DiskFullException(String message) {
            super(message);
        }
    }

    public void clearLastFailures() {
        lastFailures = new ArrayList<>();
        currentProgress.reset();
    }
}
