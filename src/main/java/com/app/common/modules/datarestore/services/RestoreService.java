package com.app.common.modules.datarestore.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.app.common.modules.datarestore.events.FileRestoredEvent;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.dtos.FileInfo;
import com.app.common.dtos.StorageProgress;
import com.app.common.exceptions.DiskFullException;
import com.app.common.models.RestoreFailure;
import com.app.common.modules.foldermanager.events.StorageDirRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.exceptions.FileNotFoundOnAnyDriveException;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.foldermanager.services.FolderSecurityService;
import com.app.common.modules.i18n.I18n;
import com.app.common.repositories.RestoreFailureRepository;
import com.app.common.services.AppConfigService;
import com.app.common.modules.device.services.DeviceValidationService;
import com.app.common.services.DriveResolverService;
import com.app.common.services.FileService;
import com.app.common.services.UserService;

import lombok.Getter;
import lombok.Setter;
import static com.app.common.definitions.AppConstants.MAX_RETRY;
import static com.app.common.utils.FileUtil.extractDriveLetter;
import static com.app.common.utils.FileUtil.stripDriveLetter;

@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);

    private final FolderManagerService folderManager;
    private final FileService fileService;
    private final UserService userService;
    private final DeviceValidationService deviceValidationService;
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
    private volatile File currentSyncDir;
    @Setter
    private Runnable onProgressInitialized;

    public enum RestoreFailureReason {
        UNKNOWN,
        BACKUP_NOT_FOUND,
        STORAGE_RECOVERY_DEFERRED
    }

    public record BackupSyncResult(boolean success, int count, String errorMessage,
            List<RestoreFailure> failures, String restoredPath, RestoreFailureReason failureReason) {

        public static BackupSyncResult success(int count, List<RestoreFailure> failures) {
            return new BackupSyncResult(true, count, null, failures, null, null);
        }

        public static BackupSyncResult success(int count, List<RestoreFailure> failures, String restoredPath) {
            return new BackupSyncResult(true, count, null, failures, restoredPath, null);
        }

        public static BackupSyncResult failure(String message) {
            return failure(message, RestoreFailureReason.UNKNOWN);
        }

        public static BackupSyncResult failure(String message, RestoreFailureReason failureReason) {
            return new BackupSyncResult(false, 0, message, List.of(), null, failureReason);
        }

        public static BackupSyncResult failure(String message, RestoreFailureReason failureReason,
                int count, List<RestoreFailure> failures) {
            return new BackupSyncResult(false, count, message, failures, null, failureReason);
        }
    }

    public RestoreService(FolderManagerService folderManager,
            FileService fileService,
            UserService userService,
            DeviceValidationService deviceValidationService,
            DriveResolverService driveResolverService,
            RestoreFailureRepository restoreFailureRepository,
            ApplicationEventPublisher eventPublisher,
            AppConfigService appConfigService) {
        this.folderManager = folderManager;
        this.fileService = fileService;
        this.userService = userService;
        this.deviceValidationService = deviceValidationService;
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
     * @return BackupSyncResult containing success status and count of processed
     *         files
     */
    public BackupSyncResult restore() {
        resetCancelled();
        // Lock
        if (!isRunning.compareAndSet(false, true)) {
            return BackupSyncResult.failure(I18n.get("setting.storage.error.already.running"));
        }
        try {
            File backupDir = folderManager.getBackupDir();
            File syncDir = folderManager.getSyncDir();

            if (backupDir == null) {
                return BackupSyncResult.failure(I18n.get("setting.storage.error.backup.dir.not.configured"));
            }
            if (syncDir == null) {
                return BackupSyncResult.failure(I18n.get("setting.storage.error.data.dir.not.configured"));
            }
            restoreFailureRepository.clearAll();
            List<Path> allFiles = scanFiles(backupDir);
            currentProgress.reset();
            currentProgress.init(allFiles.size());
            if (onProgressInitialized != null) {
                onProgressInitialized.run();
            }
            BackupSyncResult result = processRestoreFiles(syncDir, allFiles, false);
            logBatchRestoreResult("Batch");

            driveResolverService.invalidateCache();

            return result;

        } catch (Exception e) {
            log.error("[RESTORE] Failed: {}", e.getMessage(), e);
            return BackupSyncResult.failure(e.getMessage());
        } finally {
            isRunning.set(false);
        }
    }

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

    private Path copyWithRetry(Path src, Path dest, Path relative) throws IOException {
        Path currentDest = dest;
        Path tempDest = currentDest.resolveSibling(currentDest.getFileName() + AppConstants.TMP_EXTENSION);
        int attempt = 0;
        while (attempt < MAX_RETRY) {
            attempt++;
            if (isCancelled.get()) {
                throw new IOException(I18n.get("setting.storage.error.cancelled"));
            }
            try {
                copyToVerifiedTempFile(src, tempDest, currentDest, relative);
                Files.move(tempDest, currentDest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return currentDest;
            } catch (IOException e) {
                cleanupTempFile(tempDest);
                if (isDiskFull(e)) {
                    String driveLetter = extractDriveLetter(currentDest);
                    log.error("[RESTORE] Disk full on drive {}", driveLetter);
                    eventPublisher
                            .publishEvent(new StorageUnavailableEvent(FolderType.SYNC, StorageIssueReason.LOW_SPACE));
                    boolean recovered = waitForDiskRecovery();
                    if (!recovered) {
                        throw new DiskFullException(I18n.get("setting.storage.error.disk.full", driveLetter));
                    }
                    currentSyncDir = folderManager.getSyncDir();
                    currentDest = currentSyncDir.toPath().resolve(relative);
                    tempDest = currentDest.resolveSibling(currentDest.getFileName() + AppConstants.TMP_EXTENSION);
                    attempt--;
                    continue;
                }
                log.warn("[Retry {}/{}] {}: {}", attempt, MAX_RETRY, relative, e.getMessage());
                if (attempt == MAX_RETRY) {
                    throw e;
                }
            }
        }
        throw new IOException("Copy failed after " + MAX_RETRY + " attempts");
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

    public BackupSyncResult retryFailed() {
        resetCancelled();
        if (!isRunning.compareAndSet(false, true)) {
            return BackupSyncResult.failure(I18n.get("setting.storage.error.already.running"));
        }
        try {
            File backupDir = folderManager.getBackupDir();
            File syncDir = folderManager.getSyncDir();
            if (backupDir == null) {
                return BackupSyncResult.failure(I18n.get("setting.storage.error.backup.dir.not.configured"));
            }
            if (syncDir == null) {
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
            BackupSyncResult result = processRestoreFiles(syncDir, failedFiles, true);
            logBatchRestoreResult("Retry batch");
            driveResolverService.invalidateCache();
            return result;
        } catch (Exception e) {
            log.error("[RETRY] Failed: {}", e.getMessage(), e);
            return BackupSyncResult.failure(e.getMessage());
        } finally {
            isRunning.set(false);
        }
    }

    private void logBatchRestoreResult(String operation) {
        log.info(
                "[RESTORE] {} result: total={}, succeeded={}, failed={}",
                operation,
                currentProgress.getTotal(),
                currentProgress.getSuccess(),
                currentProgress.getTotalFailed());
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
            File initialSyncDir,
            List<Path> sourceFiles,
            boolean retryMode) {

        List<RestoreFailure> failures = new ArrayList<>();
        List<String> successPaths = new ArrayList<>();
        currentSyncDir = initialSyncDir;
        for (int sourceIndex = 0; sourceIndex < sourceFiles.size(); sourceIndex++) {
            Path srcPath = sourceFiles.get(sourceIndex);
            if (isCancelled.get()) {
                currentProgress.reset();
                return BackupSyncResult.failure(
                        I18n.get("setting.storage.error.cancelled"));
            }

            BackupSyncResult singleResult = restoreSingleFile(currentSyncDir.getAbsolutePath(), srcPath.toString());
            if (singleResult.success()) {
                successPaths.add(srcPath.toString());
                currentProgress.incrementSuccess();
                continue;
            }

            if (singleResult.failures().isEmpty()) {
                failures.add(buildFailure(srcPath, singleResult.errorMessage()));
            } else {
                failures.addAll(singleResult.failures());
            }
            currentProgress.incrementFailed();

            if (singleResult.failureReason() == RestoreFailureReason.STORAGE_RECOVERY_DEFERRED) {
                int remainingCount = sourceFiles.size() - sourceIndex - 1;
                for (int remainingIndex = sourceIndex + 1; remainingIndex < sourceFiles.size(); remainingIndex++) {
                    failures.add(buildFailure(sourceFiles.get(remainingIndex), singleResult.errorMessage()));
                }
                currentProgress.incrementSkipped(remainingCount);
                persistRestoreOutcome(failures, retryMode, successPaths);
                return BackupSyncResult.failure(
                        singleResult.errorMessage(),
                        RestoreFailureReason.STORAGE_RECOVERY_DEFERRED,
                        currentProgress.getSuccess(),
                        failures);
            }
        }
        persistRestoreOutcome(failures, retryMode, successPaths);
        return BackupSyncResult.success(
                currentProgress.getSuccess(),
                failures);
    }

    /**
     * Restores one backup file into the sync folder and updates its database
     * record.
     * Accepts an absolute backup path or a stored non-drive-letter path.
     */
    public BackupSyncResult restoreSingleFile(String syncFolderPath, String backupFilePath) {
        try {
            File syncDir = new File(syncFolderPath);
            Path sourcePath = resolveBackupFilePath(backupFilePath);
            Path relative = folderManager.toRelativeDataPath(sourcePath);
            Path destPath = syncDir.toPath().resolve(relative);

            if (!FolderSecurityService.isValidBackupFile(sourcePath)) {
                log.warn("[RESTORE] Invalid checksum, deleting: {}", sourcePath);
                deleteInvalidFileWithHashes(sourcePath);
                return BackupSyncResult.failure("Invalid checksum: " + sourcePath);
            }

            String fileName = sourcePath.getFileName().toString();
            FileInfo info = FileInfo.parse(fileName);
            if (info == null) {
                return BackupSyncResult.failure("Cannot parse file name: " + fileName);
            }

            Long userId = userService.getOrCreateSyncUser(info.username());
            if (userId == null) {
                return BackupSyncResult.failure("Cannot resolve user: " + info.username());
            }

            Long deviceId = deviceValidationService.resolveDeviceId(info.cameraId());
            if (deviceId == null) {
                return BackupSyncResult.failure("Cannot resolve device: " + info.cameraId());
            }

            if (!Files.exists(destPath) || Files.size(destPath) != Files.size(sourcePath)) {
                destPath = copyWithRetry(sourcePath, destPath, relative);
            }

            fileService.upsertFileRecord(destPath.toString(), sourcePath.toString(), userId, deviceId,
                    info.createDate(), fileName);
            eventPublisher.publishEvent(new FileRestoredEvent(stripDriveLetter(destPath.toString())));
            return BackupSyncResult.success(1, List.of(), destPath.toString());
        } catch (FileNotFoundOnAnyDriveException e) {
            log.warn("[RESTORE] Backup file not found: {}", backupFilePath);
            return BackupSyncResult.failure(e.getMessage(), RestoreFailureReason.BACKUP_NOT_FOUND);
        } catch (DiskFullException e) {
            log.info("[RESTORE] Storage recovery deferred while restoring: {}", backupFilePath);
            return BackupSyncResult.failure(e.getMessage(), RestoreFailureReason.STORAGE_RECOVERY_DEFERRED);
        } catch (IOException e) {
            log.error("[RESTORE] Single file failed: {}", backupFilePath, e);
            return BackupSyncResult.failure(e.getMessage());
        }
    }

    private Path resolveBackupFilePath(String backupFilePath) throws IOException {
        if (backupFilePath == null || backupFilePath.isBlank()) {
            throw new IOException("Backup file path is blank");
        }

        Path directPath = Path.of(backupFilePath);
        if (directPath.getRoot() != null) {
            if (Files.exists(directPath)) {
                return directPath;
            }
            throw new FileNotFoundOnAnyDriveException(backupFilePath);
        }

        return folderManager.resolvePathAcrossDrives(backupFilePath);
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

}
