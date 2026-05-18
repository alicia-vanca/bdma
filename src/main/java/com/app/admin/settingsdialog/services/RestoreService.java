package com.app.admin.settingsdialog.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.dtos.StorageProgress;
import com.app.common.models.RestoreFailure;
import com.app.common.modules.foldermanager.events.StorageDirRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.foldermanager.services.FolderSecurityService;
import com.app.common.modules.i18n.I18n;
import com.app.common.repositories.RestoreFailureRepository;
import com.app.common.services.AppConfigService;
import com.app.common.services.DriveResolverService;
import com.app.common.services.FileService;

import lombok.Getter;
import lombok.Setter;
import static com.app.common.definitions.AppConstants.MAX_RETRY;
import static com.app.common.utils.FileUtil.extractDriveLetter;

@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);

    private final FolderManagerService folderManager;
    private final FileService fileService;
    private final DriveResolverService driveResolverService;
    private final RestoreFailureRepository restoreFailureRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AppConfigService appConfigService;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    @Getter
    private final StorageProgress currentProgress = new StorageProgress();
    @Getter
    private List<RestoreFailure> lastFailures = new ArrayList<>();
    private volatile boolean recoveryDeferred = false;
    private final Object recoveryLock = new Object();

    public RestoreService(FolderManagerService folderManager,
            FileService fileService,
            DriveResolverService driveResolverService,
            RestoreFailureRepository restoreFailureRepository,
            ApplicationEventPublisher eventPublisher,
            AppConfigService appConfigService) {
        this.folderManager = folderManager;
        this.fileService = fileService;
        this.driveResolverService = driveResolverService;
        this.restoreFailureRepository = restoreFailureRepository;
        this.eventPublisher = eventPublisher;
        this.appConfigService = appConfigService;
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
     * Synchronizes files between backup and data directories.
     * - Copies all files from source to target (REPLACE_EXISTING)
     * - Batch updates DB: synced_path and backed_up_path to the new directory
     *
     * @return BackupSyncResult containing success status and count of processed files
     */
    public BackupSyncResult restore() {
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
            restoreFailureRepository.clearAll();
            List<Path> allFiles = scanFiles(backupDir);
            currentProgress.reset();
            currentProgress.init(allFiles.size());
            if (onProgressInitialized != null) {
                onProgressInitialized.run();
            }
            Map<String, Long> userMap = fileService.batchResolveUsers(allFiles);
            Map<String, Long> deviceMap = fileService.batchResolveDevices(allFiles);
            BackupSyncResult result = processRestoreFiles(dataDir, backupDir, allFiles, userMap, deviceMap, false);

            driveResolverService.invalidateCache();

            return result;

        } catch (Exception e) {
            log.error("[RESTORE] Failed: {}", e.getMessage(), e);
            return BackupSyncResult.failure(e.getMessage());
        } finally {
            isRunning.set(false);
        }
    }

    @Setter
    private Runnable onProgressInitialized;

    private List<Path> scanFiles(File sourceDir) throws IOException {
        Path dirPath = sourceDir.toPath();
        FolderSecurityService.unlockSinglePath(dirPath);
        try (var stream = Files.walk(dirPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !FolderSecurityService.isChecksumSidecarFile(path))
                    .filter(path -> !path.toString().endsWith(AppConstants.TMP_EXTENSION))
                    .toList();
        } finally {
            FolderSecurityService.lockSinglePath(dirPath);
        }
    }

    private void copyWithRetry(Path src, Path dest, Path relative) throws IOException {
        Path tempDest = dest.resolveSibling(dest.getFileName() + AppConstants.TMP_EXTENSION);
        int attempt = 0;
        while (attempt < MAX_RETRY) {
            attempt++;
            if (isCancelled.get()) {
                throw new IOException(I18n.get("setting.storage.error.cancelled"));
            }
            try {
                copyToVerifiedTempFile(src, tempDest, dest, relative);
                Files.move(tempDest, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                cleanupTempFile(tempDest);
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

    private void copyToVerifiedTempFile(Path src, Path tempDest, Path dest, Path relative) throws IOException {
        Files.createDirectories(dest.getParent());
        Files.deleteIfExists(tempDest);
        Files.copy(src, tempDest, StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(src) != Files.size(tempDest)) {
            throw new IOException("Size mismatch after copy: " + relative);
        }
    }

    private void cleanupTempFile(Path tempDest) {
        try {
            Files.deleteIfExists(tempDest);
        } catch (IOException cleanupException) {
            log.warn("[RESTORE] Failed to delete temp file {}: {}", tempDest, cleanupException.getMessage());
        }
    }

    private boolean isDiskFull(IOException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("No space left") || msg.contains("There is not enough space"));
    }

    private RestoreFailure buildFailure(Path srcPath, String errorMessage) {
        return new RestoreFailure(srcPath.toString(), errorMessage);
    }

    public record BackupSyncResult(boolean success, int count, String errorMessage,
                                   List<RestoreFailure> failures) {

        public static BackupSyncResult success(int count, List<RestoreFailure> failures) {
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

    public BackupSyncResult retryFailed() {
        resetCancelled();
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
            List<RestoreFailure> failedRecords = restoreFailureRepository.findAll();
            if (failedRecords.isEmpty()) {
                return BackupSyncResult.success(0, List.of());
            }
            List<Path> failedFiles = failedRecords.stream()
                    .map(r -> Path.of(r.getSrcPath()))
                    .toList();
            currentProgress.reset();
            currentProgress.init(failedFiles.size());

            if (onProgressInitialized != null) {
                onProgressInitialized.run();
            }
            Map<String, Long> userMap = fileService.batchResolveUsers(failedFiles);
            Map<String, Long> deviceMap = fileService.batchResolveDevices(failedFiles);
            BackupSyncResult result = processRestoreFiles(dataDir, backupDir,
                    failedFiles, userMap, deviceMap, true);
            driveResolverService.invalidateCache();
            return result;
        } catch (Exception e) {
            log.error("[RETRY] Failed: {}", e.getMessage(), e);
            return BackupSyncResult.failure(e.getMessage());
        } finally {
            isRunning.set(false);
        }
    }

    @EventListener
    public void onStorageDirRestored(StorageDirRestoredEvent event) {
        if (event != null && event.target() == FolderType.SYNC) {
            synchronized (recoveryLock) {
                recoveryDeferred = false;
                recoveryLock.notifyAll();
            }
        }
    }

    @EventListener
    public void onStorageDirRecoveryDeferred(StorageRecoveryDeferredEvent event) {
        if (event != null && event.getTarget() == FolderType.SYNC) {
            synchronized (recoveryLock) {
                recoveryDeferred = true;
                recoveryLock.notifyAll();
            }
        }
    }

    private boolean waitForDiskRecovery() {
        synchronized (recoveryLock) {
            recoveryDeferred = false;
            while (!recoveryDeferred && !isCancelled.get()) {
                try {
                    recoveryLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (!recoveryDeferred) {
                    return true;
                }
            }
            return false;
        }
    }

    private BackupSyncResult processRestoreFiles(
            File initialDataDir,
            File backupDir,
            List<Path> sourceFiles,
            Map<String, Long> userMap,
            Map<String, Long> deviceMap,
            boolean retryMode) {

        List<RestoreFailure> failures = new ArrayList<>();
        List<String> successPaths = new ArrayList<>();
        File dataDir = initialDataDir;
        RestoreContext ctx = new RestoreContext(
                backupDir, userMap, deviceMap, failures, successPaths, sourceFiles.size());
        for (int i = 0; i < sourceFiles.size(); i++) {
            Path srcPath = sourceFiles.get(i);
            if (isCancelled.get()) {
                currentProgress.reset();
                return BackupSyncResult.failure(
                        I18n.get("setting.storage.error.cancelled"));
            }

            Path relative = backupDir.toPath().relativize(srcPath);
            Path destPath = dataDir.toPath().resolve(relative);

            try {
                if (shouldSkipFile(srcPath, destPath, relative, dataDir, ctx)) continue;

                copyWithRetry(srcPath, destPath, relative);

                fileService.upsertFileFromRestore(relative.toString(), dataDir.getAbsolutePath(),
                        backupDir.getAbsolutePath(), userMap, deviceMap);
                successPaths.add(srcPath.toString());
                currentProgress.incrementSuccess();
            } catch (DiskFullException e) {
                String driveLetter = extractDriveLetter(destPath);
                log.error("[RESTORE] Disk full on drive {}", driveLetter);
                eventPublisher.publishEvent(new StorageUnavailableEvent(FolderType.SYNC, StorageIssueReason.LOW_SPACE));
                boolean recovered = waitForDiskRecovery();
                if (!recovered) {
                    currentProgress.incrementSkipped(sourceFiles.size() - i);
                    return BackupSyncResult.failure(I18n.get("setting.storage.error.disk.full", driveLetter));
                }
                dataDir = folderManager.getDataDir();
                Path newDestPath = dataDir.toPath().resolve(relative);
                try {
                    copyWithRetry(srcPath, newDestPath, relative);
                    fileService.upsertFileFromRestore(relative.toString(), dataDir.getAbsolutePath(),
                            backupDir.getAbsolutePath(), userMap, deviceMap);
                    successPaths.add(srcPath.toString());
                    currentProgress.incrementSuccess();
                } catch (IOException retryEx) {
                    log.error("[RESTORE] Failed after disk recovery: {}", relative, retryEx);
                    failures.add(buildFailure(srcPath, retryEx.getMessage()));
                    currentProgress.incrementFailed();
                }
            } catch (IOException e) {
                log.error("[RESTORE] Failed: {}", relative, e);
                failures.add(buildFailure(srcPath, e.getMessage()));
                currentProgress.incrementFailed();
            }
        }
        persistRestoreOutcome(failures, retryMode, successPaths);
        return BackupSyncResult.success(
                currentProgress.getSuccess(),
                failures);
    }

    private boolean shouldSkipFile(Path srcPath, Path destPath,
            Path relative, File dataDir, RestoreContext ctx) throws IOException {
        if (!FolderSecurityService.isValidBackupFile(srcPath)) {
            log.warn("[RESTORE] Invalid checksum, deleting: {}", srcPath);
            deleteInvalidFileWithHashes(srcPath);
            currentProgress.incrementChecksumInvalid();
            return true;
        }
        if (Files.exists(destPath) && Files.size(destPath) == Files.size(srcPath)) {
            upsertAndRecord(relative, dataDir, srcPath, ctx);
            return true;
        }
        return false;
    }

    // Delete invalid backup file and its associated hash files (.sha256 and
    // .sha256.backup)
    private void deleteInvalidFileWithHashes(Path filePath) {
        try {
            Files.deleteIfExists(filePath);
            log.info("[RESTORE] Deleted invalid file: {}", filePath);
        } catch (IOException e) {
            log.warn("[RESTORE] Failed to delete invalid file: {}", filePath, e);
        }

        Path primaryHash = FolderSecurityService.primaryChecksumPath(filePath);
        try {
            if (Files.deleteIfExists(primaryHash)) {
                log.info("[RESTORE] Deleted primary hash: {}", primaryHash);
            }
        } catch (IOException e) {
            log.warn("[RESTORE] Failed to delete primary hash: {}", primaryHash, e);
        }

        Path backupHash = FolderSecurityService.backupChecksumPath(filePath);
        try {
            if (Files.deleteIfExists(backupHash)) {
                log.info("[RESTORE] Deleted backup hash: {}", backupHash);
            }
        } catch (IOException e) {
            log.warn("[RESTORE] Failed to delete backup hash: {}", backupHash, e);
        }
    }

    private void persistRestoreOutcome(List<RestoreFailure> failures, boolean retryMode, List<String> successPaths) {
        restoreFailureRepository.clearAll();
        if (!failures.isEmpty()) {
            restoreFailureRepository.saveAll(failures);
        }
        if (retryMode && !successPaths.isEmpty()) {
            restoreFailureRepository.deleteByPaths(successPaths);
        }
        lastFailures = new ArrayList<>(failures);
        appConfigService.saveConfigValue(
                AppConstants.KEY_LAST_RESTORE_PROGRESS,
                currentProgress.getTotal() + "," +
                        currentProgress.getSuccess() + "," +
                        currentProgress.getFailed() + "," +
                        currentProgress.getChecksumInvalid() + "," +
                        currentProgress.getSkipped());
    }

    private void upsertAndRecord(Path relative, File dataDir, Path srcPath, RestoreContext ctx) {
        fileService.upsertFileFromRestore(
                relative.toString(),
                dataDir.getAbsolutePath(),
                ctx.backupDir().getAbsolutePath(),
                ctx.userMap(),
                ctx.deviceMap());
        ctx.successPaths().add(srcPath.toString());
        currentProgress.incrementSuccess();
    }

    private record RestoreContext(
            File backupDir,
            Map<String, Long> userMap,
            Map<String, Long> deviceMap,
            List<RestoreFailure> failures,
            List<String> successPaths,
            int totalFiles
    ) {
    }
}
