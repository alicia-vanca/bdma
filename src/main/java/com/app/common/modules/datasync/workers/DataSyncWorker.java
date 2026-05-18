package com.app.common.modules.datasync.workers;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FileType;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.dtos.FileInfo;
import com.app.common.dtos.SyncContext;
import com.app.common.events.DeviceEvent;
import com.app.common.events.FailureSummaryRequestedEvent;
import com.app.common.events.FailureSummaryRequestedEvent.FailureSummaryRow;
import com.app.common.exceptions.DeviceDisconnectedException;
import com.app.common.models.FileRecord;
import com.app.common.modules.datasync.events.FileSyncCompletedEvent;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.datasync.services.DataSyncService;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.queuemanager.services.QueueManagerService;
import com.app.common.services.AdbClient;
import com.app.common.services.AppNoticeService;
import com.app.common.services.DeviceMiniStatus;
import com.app.common.services.DriveLetterMapper;
import com.app.common.services.MassStorageFileSource;
import com.app.common.utils.FileUtil;

import lombok.Getter;

@Component
public class DataSyncWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DataSyncWorker.class);

    private static final String INTERNAL_ROOT = System.getProperty("app.internal.root", "/storage/emulated/0/DCIM");
    private static final String EXTERNAL_SUFFIX = "/Android/data/com.bodycamera.nettysocket/cache";

    private static final String ERROR_DISCONNECTED = "device.sync.error.disconnected";
    private static final String ERROR_EXCEPTION = "device.sync.error.exception";
    private static final String ERROR_STORAGE_UNAVAILABLE = "device.sync.error.storage_unavailable";
    private static final String ERROR_STORAGE_FULL = "device.sync.error.storage_full";
    private static final String ERROR_BAD_CONNECTION = "device.sync.error.bad_connection";
    private static final String ERROR_REMOTE_SIZE_UNAVAILABLE = "device.sync.error.remote_size_unavailable";
    private static final String ERROR_DEVICE_NOT_FOUND = "device.sync.error.device_not_found";
    private static final String ERROR_USER_NOT_FOUND = "device.sync.error.user_not_found";
    private static final String ERROR_UNKNOWN = "device.sync.error.unknown";
    private static final String ERROR_PERMISSION_DENIED = "device.sync.error.permission_denied";
    private static final String ERROR_FILE_NOT_CREATED = "device.sync.error.file_not_created";
    private static final String ERROR_SIZE_MISMATCH = "device.sync.error.size_mismatch";
    private static final String ERROR_TRANSFER = "device.sync.error.transfer";
    private static final String ERROR_ADB_PULL_FAILED = "device.sync.error.adb_pull_failed";

    private static final String SYNC_SUMMARY_TITLE = "device.sync.summary.title";
    private static final String SYNC_SUMMARY_HEADER = "device.sync.summary.header";
    private static final String SYNC_SUMMARY_CONTENT = "device.sync.summary.content";
    private static final String SYNC_SUMMARY_COLUMN_FILENAME = "device.sync.summary.column.filename";
    private static final String SYNC_SUMMARY_COLUMN_REASON = "device.sync.summary.column.reason";

    private final DeviceSyncQueue queue;
    private final DataSyncService dataSyncService;
    private final FolderManagerService folderManagerService;
    private final AdbClient adbClient;
    private final DeviceMiniStatus progressTracker;
    private final ApplicationEventPublisher publisher;
    private final AppNoticeService appNoticeService;
    private final DriveLetterMapper driveLetterMapper;
    private final MassStorageFileSource massStorageFileSource;
    private final QueueManagerService queueManagerService;

    private final Map<String, String> driveLetterCache = new ConcurrentHashMap<>();
    private final Set<String> disconnectedDevices = ConcurrentHashMap.newKeySet();
    private volatile boolean shutdownRequested = false;
    private volatile boolean saveRecoveryDeferred = false;
    private volatile boolean saveRecoveryRestored = false;
    private final Object recoveryLock = new Object();

    // Records and inner classes
    private record SyncFile(String remotePath, String localPath, String relativeLocalPath, FileInfo info,
            String failureReason) {
    }

    private record PullResult(boolean succeeded, String failureReason) {
        private static PullResult success() {
            return new PullResult(true, null);
        }

        private static PullResult failure(String reason) {
            return new PullResult(false, reason);
        }

        public boolean isSuccess() {
            return succeeded;
        }
    }

    private record SyncPreparation(SyncFileCollection fileCollection, Set<String> syncedPaths,
            LookupCache lookupCache, List<SyncFile> filesToBeProcessed) {
    }

    private record SyncFileCollection(List<SyncFile> allSyncFiles, List<SyncFile> unsyncedFiles,
            int alreadySyncedCount) {
    }

    /**
     * Result of processing a single file, indicating next action.
     */
    private static class FileProcessingResult {
        private final boolean abort;
        private final boolean retry;
        @Getter
        private final boolean success;
        @Getter
        private final String failureReason;
        private final SyncContext updatedContext;

        private FileProcessingResult(boolean abort, boolean retry, boolean success, String failureReason,
                SyncContext updatedContext) {
            this.abort = abort;
            this.retry = retry;
            this.success = success;
            this.failureReason = failureReason;
            this.updatedContext = updatedContext;
        }

        public static FileProcessingResult abort(String reason) {
            return new FileProcessingResult(true, false, false, reason, null);
        }

        public static FileProcessingResult retryWithContext(SyncContext context) {
            return new FileProcessingResult(false, true, false, null, context);
        }

        public static FileProcessingResult success() {
            return new FileProcessingResult(false, false, true, null, null);
        }

        public static FileProcessingResult failure(String reason) {
            return new FileProcessingResult(false, false, false, reason, null);
        }

        public boolean shouldAbort() {
            return abort;
        }

        public boolean shouldRetry() {
            return retry;
        }

        public boolean contextUpdated() {
            return updatedContext != null;
        }

        public SyncContext updatedContext() {
            return updatedContext;
        }
    }

    public static class SyncCounters {
        int total;
        int passed;
        int failed;

        public SyncCounters(int total, int passed, int failed) {
            this.total = total;
            this.passed = passed;
            this.failed = failed;
        }
    }

    private final class LookupCache {
        private final Map<String, Long> deviceIds = new HashMap<>();
        private final Map<String, Long> userIds = new HashMap<>();

        private Long deviceId(String cameraId) {
            return deviceIds.computeIfAbsent(cameraId, dataSyncService::resolveDeviceId);
        }

        private Long userId(String userName) {
            return userIds.computeIfAbsent(userName, dataSyncService::resolveUserId);
        }
    }

    private static final class PreflightException extends RuntimeException {
        private PreflightException(String message) {
            super(message);
        }
    }

    // -------------------------------------------------------------------------
    // Construction and lifecycle
    // -------------------------------------------------------------------------

    public DataSyncWorker(DeviceSyncQueue queue,
            DataSyncService dataSyncService,
            FolderManagerService folderManagerService,
            AdbClient adbClient,
            DeviceMiniStatus progressTracker,
            ApplicationEventPublisher publisher,
            AppNoticeService appNoticeService,
            DriveLetterMapper driveLetterMapper,
            MassStorageFileSource massStorageFileSource,
            QueueManagerService queueManagerService) {
        this.queue = queue;
        this.dataSyncService = dataSyncService;
        this.folderManagerService = folderManagerService;
        this.adbClient = adbClient;
        this.progressTracker = progressTracker;
        this.publisher = publisher;
        this.appNoticeService = appNoticeService;
        this.driveLetterMapper = driveLetterMapper;
        this.massStorageFileSource = massStorageFileSource;
        this.queueManagerService = queueManagerService;
    }

    @Override
    public void run() {
        log.info("Sync worker started");
        while (!shutdownRequested && !Thread.currentThread().isInterrupted()) {
            try {
                DeviceSyncQueue.Entry entry = queue.take();
                processDevice(entry);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("SyncWorker error", e);
            }
        }
        log.info("Sync worker stopped gracefully");
    }

    /**
     * Request graceful shutdown. Worker will finish current file then stop.
     */
    public void requestShutdown() {
        shutdownRequested = true;
    }

    /**
     * Reset shutdown flag when restarting the worker.
     */
    public void resetShutdownFlag() {
        shutdownRequested = false;
    }

    // -------------------------------------------------------------------------
    // Sync orchestration and preparation
    // -------------------------------------------------------------------------

    // Discover and prepare files for sync from device
    private SyncPreparation prepareSyncFiles(String hardwareId, Long deviceId, SyncContext syncContext,
            boolean autoDelete) {
        List<FileRecord> syncedFiles = dataSyncService.loadSyncedFiles(deviceId);
        Set<String> relativeSyncedPaths = new java.util.HashSet<>();
        for (FileRecord syncedFile : syncedFiles) {
            PathResolutionResult resolution = folderManagerService
                    .findAbsolutePathFromNonDriveLetterPath(syncedFile.getSyncedPath(), syncedFile.getFileSize());

            if (!resolution.isFound()) {
                continue;
            }

            try {
                relativeSyncedPaths.add(
                        folderManagerService.toRelativeDataPath(resolution.getPath().toString()));
            } catch (IOException e) {
                log.warn("Invalid syncedPath, resync: {}", syncedFile.getSyncedPath());
            }
        }

        try {
            Thread.sleep(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<String> remoteFilePaths = new ArrayList<>(findFiles(hardwareId, INTERNAL_ROOT));

        String ext = getExternalStorage(hardwareId);
        if (ext != null) {
            remoteFilePaths.addAll(findFiles(hardwareId, ext + EXTERNAL_SUFFIX));
        } else {
            List<String> massStorageFiles = findFilesFromMassStorage(hardwareId);
            if (!massStorageFiles.isEmpty()) {
                remoteFilePaths.addAll(massStorageFiles);
            }
        }

        LookupCache lookupCache = new LookupCache();
        SyncFileCollection fileCollection = collectSyncFiles(syncContext, remoteFilePaths,
                relativeSyncedPaths,
                lookupCache);

        List<SyncFile> filesToBeProcessed = autoDelete ? fileCollection.allSyncFiles()
                : fileCollection.unsyncedFiles();

        return new SyncPreparation(fileCollection, relativeSyncedPaths, lookupCache, filesToBeProcessed);
    }

    private String getExternalStorage(String hardwareId) {
        try {
            return adbClient.getExternalStorage(hardwareId);
        } catch (DeviceDisconnectedException e) {
            disconnectedDevices.add(hardwareId);
            throw new PreflightException(I18n.get(ERROR_DISCONNECTED));
        }
    }

    private List<String> findFilesFromMassStorage(String hardwareId) {
        String driveLetter = driveLetterMapper.resolve(hardwareId);
        if (driveLetter == null) {
            throw new PreflightException(I18n.get(ERROR_BAD_CONNECTION));
        }
        driveLetterCache.put(hardwareId, driveLetter);
        log.info("[{}] MassStorage fallback: using drive {}", hardwareId, driveLetter);
        return massStorageFileSource.findFiles(driveLetter, FileType.ALL_VALUES);
    }

    // Parse remote file paths into SyncFile objects and categorize by sync status
    private SyncFileCollection collectSyncFiles(SyncContext syncContext,
            List<String> remoteFilePaths,
            Set<String> syncedPaths,
            LookupCache lookupCache) {

        List<SyncFile> allSyncFiles = new ArrayList<>();
        List<SyncFile> unsyncedFiles = new ArrayList<>();
        int alreadySyncedCount = 0;

        for (String path : remoteFilePaths) {
            SyncFile syncFile = buildSyncFile(path, syncContext, lookupCache);
            if (syncFile != null) {
                allSyncFiles.add(syncFile);
                queueManagerService.addFileToSyncTracker(syncContext.hardwareId(), name(syncFile.remotePath()),
                        syncFile.relativeLocalPath());
                if (!syncedPaths.contains(syncFile.relativeLocalPath())) {
                    unsyncedFiles.add(syncFile);
                } else {
                    alreadySyncedCount++;
                    queueManagerService.markSyncFileCompleted(syncContext.hardwareId(), syncFile.relativeLocalPath());
                }
            }
        }

        return new SyncFileCollection(allSyncFiles, unsyncedFiles, alreadySyncedCount);
    }

    // Build SyncFile from remote path, validating user permissions and database
    // references. Size is retrieved later only for unsynced files.
    // Returns null if relative path cannot be determined or validation fails.
    private SyncFile buildSyncFile(String path, SyncContext syncContext, LookupCache lookupCache) {
        String type = extractType(path);
        FileInfo info = FileInfo.parse(name(path), 0, type);

        if (info == null || !syncContext.canSync(info.username())) {
            return null;
        }

        String localPath = resolveLocalPath(info, path, syncContext.saveDir());
        if (localPath == null
                || lookupCache.deviceId(info.cameraId()) == null
                || lookupCache.userId(info.username()) == null) {
            return null;
        }

        // Fail fast if relative path cannot be determined
        String relativeLocalPath;
        try {
            relativeLocalPath = folderManagerService.toRelativeDataPath(localPath);
        } catch (IOException e) {
            log.warn("Failed to convert to relative path, skipping file: {}", localPath);
            return null;
        }

        return new SyncFile(path, localPath, relativeLocalPath, info, null);
    }

    private List<String> findFiles(String hardwareId, String root) {
        try {
            return adbClient.findFiles(hardwareId, root, FileType.ALL_VALUES);
        } catch (DeviceDisconnectedException e) {
            disconnectedDevices.add(hardwareId);
            throw new PreflightException(I18n.get(ERROR_DISCONNECTED));
        }
    }

    // Build local file path from FileInfo and remote path structure using captured
    // saveDir.
    private String resolveLocalPath(FileInfo info, String remotePath, File saveDir) {
        String[] parts = remotePath.split("/");
        if (parts.length < 3)
            return null;

        if (saveDir == null)
            return null;

        File dir = new File(new File(saveDir, info.username()), parts[parts.length - 3]);
        return new File(dir, parts[parts.length - 1]).getAbsolutePath();
    }

    // Extract folder type from remote path (third-to-last path component)
    private String extractType(String remotePath) {
        String[] parts = remotePath.split("/");
        return parts.length >= 3 ? parts[parts.length - 3] : null;
    }

    // -------------------------------------------------------------------------
    // Device-level sync flow
    // -------------------------------------------------------------------------

    private void processDevice(DeviceSyncQueue.Entry entry) {
        String hardwareId = entry.hardwareId();
        SyncContext syncContext = entry.context();
        long startedAt = System.currentTimeMillis();
        saveRecoveryDeferred = false;

        try {
            Long deviceId = dataSyncService.validateDevice(hardwareId);
            Long userId = dataSyncService.resolveUserId(syncContext.username());
            if (deviceId == null || userId == null)
                return;

            String deviceName = syncContext.deviceName();
            queueManagerService.markDeviceSyncProcessing(hardwareId);
            adbClient.stopCameraService(hardwareId);
            adbClient.setUsbFunctionsNone(hardwareId);

            boolean autoDelete = syncContext.autoDelete();

            SyncPreparation preparedSyncFiles = prepareSyncFiles(hardwareId, deviceId, syncContext, autoDelete);

            int totalRemoteFileCount = preparedSyncFiles.fileCollection().allSyncFiles().size();

            SyncCounters counters = new SyncCounters(
                    totalRemoteFileCount,
                    autoDelete ? 0 : preparedSyncFiles.fileCollection().alreadySyncedCount(),
                    0);

            if (counters.passed > 0) {
                log.info("Skipped {} already synced files", counters.passed);
            }

            progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);

            List<SyncFile> failedList = new ArrayList<>();
            boolean shouldRetryFailures = processSyncFiles(hardwareId, syncContext, preparedSyncFiles,
                    failedList, counters);

            if (shouldRetryFailures && !failedList.isEmpty()) {
                retryFailed(hardwareId, syncContext, preparedSyncFiles, failedList, counters);
            }

            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.info("Finished sync for {}: total={}, passed={}, failed={}, elapsedMs={}",
                    hardwareId, counters.total, counters.passed, counters.failed, elapsedMs);

            queueManagerService.markDeviceSyncCompleted(hardwareId, counters.total, counters.passed, counters.failed);

            if (counters.failed > 0 && !shutdownRequested) {
                showSyncFailureSummary(hardwareId, syncContext, deviceName, counters, failedList);
            }
        } catch (PreflightException e) {
            log.warn("Aborting sync for {} before file processing: {}", hardwareId, e.getMessage());
            progressTracker.markSyncing(hardwareId, 1, 0, 1);
            appNoticeService.showError(e.getMessage());
        } finally {
            disconnectedDevices.remove(hardwareId);
            driveLetterCache.remove(hardwareId);
            queue.done(hardwareId);
            adbClient.startCameraService(hardwareId);
        }
    }

    // -------------------------------------------------------------------------
    // Per-file processing pipeline
    // -------------------------------------------------------------------------

    // Process each file in the sync list
    private boolean processSyncFiles(String hardwareId, SyncContext syncContext, SyncPreparation prep,
            List<SyncFile> failedList, SyncCounters counters) {

        List<SyncFile> filesToBeProcessed = prep.filesToBeProcessed();
        int i = 0;
        while (i < filesToBeProcessed.size()) {
            SyncFile file = filesToBeProcessed.get(i);

            if (!checkCanContinueProcessing(hardwareId, filesToBeProcessed, i, failedList, counters)) {
                return false;
            }

            logProcessingFile(file, counters);

            FileProcessingResult result = processSingleFile(hardwareId, syncContext, prep, i, file, counters);

            if (result.shouldAbort() || isDeviceDead(hardwareId)) {
                abortAndFailRemaining(hardwareId, result, filesToBeProcessed, i, failedList, counters);
                return false;
            }

            if (result.contextUpdated()) {
                syncContext = result.updatedContext();
                rebaseRemainingSyncFiles(filesToBeProcessed, i, syncContext.saveDir());
                rebaseRemainingSyncFiles(failedList, 0, syncContext.saveDir());
            }

            if (!result.shouldRetry()) {
                i++;

                if (result.isSuccess()) {
                    recordFileSyncSuccess(hardwareId, file, counters);
                } else {
                    handleFailedFileSync(file, result, failedList);
                }
            }
        }

        return true;
    }

    // Abort the sync loop if the result requests abort or the device is dead,
    // failing all remaining files in one pass.
    private void abortAndFailRemaining(String hardwareId, FileProcessingResult result,
            List<SyncFile> filesToBeProcessed, int index, List<SyncFile> failedList, SyncCounters counters) {
        String reason = result.getFailureReason();
        if (reason == null) {
            reason = isDeviceDead(hardwareId) ? ERROR_DISCONNECTED : ERROR_UNKNOWN;
        }
        failRemaining(hardwareId, filesToBeProcessed, index, failedList, counters, reason);
    }

    // Classify a failed file result and add it to the failed list.
    private void handleFailedFileSync(SyncFile file, FileProcessingResult result, List<SyncFile> failedList) {
        String reason;
        reason = result.getFailureReason() != null ? result.getFailureReason() : ERROR_UNKNOWN;
        failedList
                .add(new SyncFile(file.remotePath(), file.localPath(), file.relativeLocalPath(), file.info(), reason));
    }

    // Update counters, log, and notify queue/progress tracker after a successful
    // file sync.
    private void recordFileSyncSuccess(String hardwareId, SyncFile file, SyncCounters counters) {
        counters.passed++;
        queueManagerService.markSyncFileCompleted(hardwareId, file.relativeLocalPath());
        progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
    }

    private void logProcessingFile(SyncFile file, SyncCounters counters) {
        if (log.isInfoEnabled()) {
            int current = counters.passed + counters.failed + 1;
            log.info("Processing file ({}/{}): {}", current, counters.total, name(file.remotePath()));
        }
    }

    /**
     * Process a single file and return the result indicating next action.
     */
    private FileProcessingResult processSingleFile(String hardwareId, SyncContext syncContext, SyncPreparation prep,
            int index, SyncFile file, SyncCounters counters) {

        if (handleAlreadySyncedFile(hardwareId, syncContext, prep.syncedPaths(), file)) {
            return FileProcessingResult.success();
        }

        if (!folderManagerService.isDataDirAccessible(syncContext.saveDir())) {
            return handleDataDirInaccessible(hardwareId, syncContext, index);
        }

        SyncFile sizedFile;
        try {
            sizedFile = withResolvedRemoteSize(hardwareId, file);
        } catch (DeviceDisconnectedException e) {
            disconnectedDevices.add(hardwareId);
            return FileProcessingResult.failure(ERROR_DISCONNECTED);
        } catch (IOException e) {
            return FileProcessingResult.failure(ERROR_REMOTE_SIZE_UNAVAILABLE);
        }

        List<SyncFile> filesToBeProcessed = prep.filesToBeProcessed();
        filesToBeProcessed.set(index, sizedFile);

        file = sizedFile;

        long requiredBytes = file.info().size();
        if (!prep.syncedPaths().contains(file.relativeLocalPath())
                && requiredBytes > 0
                && !folderManagerService.hasSufficientSpace(syncContext.saveDir(), requiredBytes)) {
            return handleInsufficientSpace(hardwareId, syncContext, filesToBeProcessed, index, requiredBytes);
        }

        // Mark as processing before attempting
        queueManagerService.markSyncFileProcessing(hardwareId, file.relativeLocalPath());

        return processFile(hardwareId, syncContext, prep, file, counters);
    }

    private FileProcessingResult processFile(String hardwareId, SyncContext syncContext, SyncPreparation prep,
            SyncFile file, SyncCounters counters) {

        LookupCache lookupCache = prep.lookupCache();

        Set<String> syncedPaths = prep.syncedPaths();

        Long dId = lookupCache.deviceId(file.info().cameraId());
        Long uId = lookupCache.userId(file.info().username());
        if (dId == null || uId == null) {
            String reason = dId == null ? ERROR_DEVICE_NOT_FOUND : ERROR_USER_NOT_FOUND;
            return FileProcessingResult.failure(reason);
        }

        PullResult result = pullAndVerify(hardwareId, file.remotePath(), file.localPath(), file.info().size());
        if (result.isSuccess()) {
            String nonDriverLetterSyncedPath = FileUtil.stripDriveLetter(file.localPath());
            dataSyncService.saveFile(uId, dId, name(file.remotePath()), nonDriverLetterSyncedPath, file.info());
            syncedPaths.add(file.relativeLocalPath());
            logSyncedFile(file.localPath(), counters.passed + 1, counters.total);

            publisher.publishEvent(new FileSyncCompletedEvent(hardwareId, nonDriverLetterSyncedPath));

            if (syncContext.autoDelete()) {
                deleteRemoteFile(hardwareId, file.remotePath());
            }
            return FileProcessingResult.success();
        }
        if (result.failureReason() != null && result.failureReason().equals(ERROR_DISCONNECTED)) {
            // If device disconnected during file processing, abort immediately without
            // retry
            return FileProcessingResult.abort(result.failureReason());
        }
        return FileProcessingResult.failure(result.failureReason());
    }

    // Pull file from device and verify size against the expected remote size.
    private PullResult pullAndVerify(String hardwareId, String remotePath, String localPath, long expectedSize) {
        String tempPath = localPath + AppConstants.TMP_EXTENSION;
        boolean moveSucceeded = false;
        try {
            PullResult result = folderManagerService
                    .withSpecificDirPrepared(new File(tempPath).getParentFile(),
                            () -> pullAndVerifyInternal(hardwareId, remotePath, tempPath, expectedSize));
            if (result.isSuccess()) {
                if (log.isDebugEnabled()) {
                    log.debug("Pull verified for {} -> {} ({} bytes)", name(remotePath), tempPath, expectedSize);
                }
                Files.move(Path.of(tempPath), Path.of(localPath), StandardCopyOption.REPLACE_EXISTING);
                moveSucceeded = true;
            }
            return result;
        } catch (AccessDeniedException e) {
            log.warn("Exception during pullAndVerify for {} -> {}: {}", name(remotePath), localPath, e.getMessage(),
                    e);
            return PullResult.failure(ERROR_PERMISSION_DENIED);
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Exception during pullAndVerify for {} -> {}: {}", name(remotePath), localPath, e.getMessage(),
                        e);
            }
            return PullResult.failure(ERROR_EXCEPTION);
        } finally {
            if (!moveSucceeded) {
                try {
                    cleanupIncompleteFile(tempPath);
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Failed to cleanup temp file {}: {}", tempPath, e.getMessage());
                    }
                }
            }
        }
    }

    private PullResult pullAndVerifyInternal(String hardwareId, String remotePath, String localPath,
            long expectedSize) {
        File f = new File(localPath);

        if (!deleteExistingFile(localPath)) {
            if (log.isWarnEnabled()) {
                log.warn("Failed to delete existing file before pull: {}", localPath);
            }
            return PullResult.failure(ERROR_PERMISSION_DENIED);
        }

        PullResult pullResult = pullFile(hardwareId, remotePath, localPath);
        if (!pullResult.isSuccess()) {
            if (log.isWarnEnabled()) {
                log.warn("ADB pull failed for: {} -> {} - Reason: {}", name(remotePath), localPath,
                        I18n.get(pullResult.failureReason()));
            }
            return pullResult;
        }

        if (!f.exists()) {
            if (log.isWarnEnabled()) {
                File parent = f.getParentFile();
                log.warn("File not created after successful pull: {} (parent exists: {}, parent writable: {})",
                        localPath, parent != null && parent.exists(), parent != null && parent.canWrite());
            }
            return PullResult.failure(ERROR_FILE_NOT_CREATED);
        }

        return verifyFileSize(f, expectedSize, remotePath);
    }

    private PullResult pullFile(String hardwareId, String remotePath, String localPath) {
        // Fail fast if device disconnected to avoid unnecessary ADB operations
        if (isDeviceDead(hardwareId)) {
            return PullResult.failure(ERROR_DISCONNECTED);
        }

        if (remotePath.startsWith(MassStorageFileSource.MASS_STORAGE_PREFIX)) {
            String driveLetter = driveLetterCache.get(hardwareId);
            if (driveLetter == null) {
                return PullResult.failure(ERROR_TRANSFER);
            }
            boolean success = massStorageFileSource.copyFile(driveLetter, remotePath, localPath);
            return success ? PullResult.success() : PullResult.failure(ERROR_TRANSFER);
        }
        try {
            boolean success = adbClient.pullFile(hardwareId, remotePath, localPath);
            return success ? PullResult.success() : PullResult.failure(ERROR_ADB_PULL_FAILED);
        } catch (DeviceDisconnectedException e) {
            disconnectedDevices.add(hardwareId);
            return PullResult.failure(ERROR_DISCONNECTED);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith(AdbClient.ERR_DEVICE_NOT_FOUND)) {
                return PullResult.failure(ERROR_BAD_CONNECTION);
            }
            return PullResult.failure(ERROR_ADB_PULL_FAILED);
        }
    }

    // -------------------------------------------------------------------------
    // Retry flow
    // -------------------------------------------------------------------------

    // Retry failed file transfers up to MAX_RETRY times, updating counters directly
    private void retryFailed(String hardwareId, SyncContext syncContext, SyncPreparation prep,
            List<SyncFile> failedList,
            SyncCounters counters) {

        List<SyncFile> failedFilesRemaining = new ArrayList<>();

        int i = 0;
        while (i < failedList.size()) {
            SyncFile file = failedList.get(i);

            if (shouldAbortRetry(hardwareId)) {
                handleAbortedRetry(hardwareId, failedList, file, failedFilesRemaining, counters);
                return;
            }

            FileProcessingResult result = retryFileWithAttempts(failedList, i, prep, syncContext, counters);

            if (result.shouldAbort()) {
                handleAbortedRetry(hardwareId, failedList, file, failedFilesRemaining, counters);
                return;
            }

            // Update queue status based on result
            if (result.isSuccess()) {
                counters.passed++;
                queueManagerService.markSyncFileCompleted(hardwareId, file.relativeLocalPath());
            } else {
                counters.failed++;
                failedFilesRemaining.add(file);
                showErrorNotification(file);
                String reason = result.getFailureReason() != null ? result.getFailureReason() : ERROR_UNKNOWN;
                queueManagerService.markSyncFileFailed(hardwareId, file.relativeLocalPath(), reason);
            }
            if (!result.shouldRetry()) {
                i++;
                progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
            }
        }

        logFailedFiles(failedFilesRemaining);
    }

    // Attempt to retry a single file up to MAX_RETRY times
    private FileProcessingResult retryFileWithAttempts(List<SyncFile> failedList, int index, SyncPreparation prep,
            SyncContext syncContext, SyncCounters counters) {
        String hardwareId = syncContext.hardwareId();
        SyncFile file = failedList.get(index);
        FileProcessingResult result = FileProcessingResult.failure(ERROR_UNKNOWN);
        int attempt = 1;
        while (attempt <= AppConstants.MAX_RETRY) {
            result = processSingleFile(hardwareId, syncContext, prep, index, file,
                    counters);

            if (result.shouldAbort()) {
                logOneRetryAbortion(hardwareId, file);
                return result;
            }

            if (result.isSuccess()) {
                return result;
            } else {
                if (isDeviceDead(hardwareId)) {
                    // If device disconnected during retry, abort immediately without further
                    // attempts
                    return result;
                } else {
                    logRetryAttemptFailure(attempt, file);
                }
            }

            if (result.contextUpdated()) {
                syncContext = result.updatedContext();
                rebaseRemainingSyncFiles(failedList, index, syncContext.saveDir());
            }

            if (!result.shouldRetry()) {
                attempt++;
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Storage recovery handling
    // -------------------------------------------------------------------------

    /**
     * Handle data directory inaccessibility during file processing.
     */
    private FileProcessingResult handleDataDirInaccessible(String hardwareId, SyncContext syncContext, int index) {
        if (log.isWarnEnabled()) {
            log.warn("[{}] Save directory became inaccessible during sync: {}",
                    hardwareId,
                    syncContext.saveDir() != null ? syncContext.saveDir().getAbsolutePath() : "null");
        }
        log.debug(
                "DataSyncWorker.handleDataDirInaccessible firing StorageUnavailableEvent: target={} reason={} device={}",
                FolderType.SYNC, StorageIssueReason.DRIVE_UNAVAILABLE, hardwareId);
        publisher.publishEvent(
                new StorageUnavailableEvent(FolderType.SYNC, StorageIssueReason.DRIVE_UNAVAILABLE));

        // Non-admin users cannot change save directory, wait for alert dismissal then
        // fail
        if (!syncContext.isAdmin()) {
            if (log.isWarnEnabled()) {
                log.warn(
                        "[{}] Non-admin user cannot recover from inaccessible save directory, waiting for alert dismissal",
                        hardwareId);
            }
            waitForStorageAlertDismissal(hardwareId);
            return FileProcessingResult.abort(ERROR_STORAGE_UNAVAILABLE);
        }

        SyncContext resumedContext = waitForDataDirRecovery(hardwareId, syncContext, 0L);
        if (resumedContext == null) {
            if (log.isWarnEnabled()) {
                log.warn("[{}] Data directory recovery aborted while waiting for accessible save dir",
                        hardwareId);
            }
            return FileProcessingResult.abort(ERROR_STORAGE_UNAVAILABLE);
        }
        if (log.isInfoEnabled()) {
            log.info("[{}] Save directory recovered, resuming sync from index {} using {}",
                    hardwareId,
                    index,
                    resumedContext.saveDir() != null ? resumedContext.saveDir().getAbsolutePath() : "null");
        }
        return FileProcessingResult.retryWithContext(resumedContext);
    }

    private SyncFile withResolvedRemoteSize(String hardwareId, SyncFile file) throws IOException {
        long remoteSize = getRemoteSize(hardwareId, file.remotePath());
        return createFileWithSize(file, remoteSize);
    }

    // Create a new SyncFile with updated size information
    private SyncFile createFileWithSize(SyncFile file, long size) {
        FileInfo updatedInfo = new FileInfo(file.info().cameraId(), file.info().username(),
                file.info().createDate(), size, file.info().type());
        return new SyncFile(file.remotePath(), file.localPath(), file.relativeLocalPath(), updatedInfo, null);
    }

    // Get remote file size strictly.
    // Throws DeviceDisconnectedException if device disconnected, IOException
    // otherwise.
    private long getRemoteSize(String hardwareId, String remotePath) throws IOException, DeviceDisconnectedException {
        if (remotePath.startsWith(MassStorageFileSource.MASS_STORAGE_PREFIX)) {
            String driveLetter = driveLetterCache.get(hardwareId);
            if (driveLetter == null) {
                throw new IOException("Mass storage drive letter is missing");
            }

            long size = massStorageFileSource.getFileSize(driveLetter, remotePath);
            if (size < 0) {
                throw new IOException("Mass storage size unavailable");
            }
            return size;
        }
        try {
            long size = adbClient.getRemoteSize(hardwareId, remotePath);
            if (size < 0) {
                throw new IOException("ADB returned negative size");
            }
            return size;
        } catch (DeviceDisconnectedException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to read remote size", e);
        }
    }

    /**
     * Handle insufficient space during file processing.
     */
    private FileProcessingResult handleInsufficientSpace(String hardwareId, SyncContext syncContext,
            List<SyncFile> filesToBeProcessed, int index, long requiredBytes) {
        if (log.isWarnEnabled()) {
            log.warn("[{}] Insufficient space detected for {} (required={} bytes, saveDir={})",
                    hardwareId,
                    name(filesToBeProcessed.get(index).remotePath()),
                    requiredBytes,
                    syncContext.saveDir() != null ? syncContext.saveDir().getAbsolutePath() : "null");
        }
        log.debug(
                "DataSyncWorker.handleInsufficientSpace firing StorageUnavailableEvent: target={} reason={} requiredBytes={} device={}",
                FolderType.SYNC, StorageIssueReason.LOW_SPACE, requiredBytes, hardwareId);
        publisher.publishEvent(
                new StorageUnavailableEvent(FolderType.SYNC, StorageIssueReason.LOW_SPACE, requiredBytes));

        // Non-admin users cannot change save directory, wait for alert dismissal then
        // fail
        if (!syncContext.isAdmin()) {
            if (log.isWarnEnabled()) {
                log.warn("[{}] Non-admin user cannot recover from insufficient space, waiting for alert dismissal",
                        hardwareId);
            }
            waitForStorageAlertDismissal(hardwareId);
            return FileProcessingResult.abort(ERROR_STORAGE_FULL);
        }

        SyncContext resumedContext = waitForDataDirRecovery(hardwareId, syncContext, requiredBytes);
        if (resumedContext == null) {
            if (log.isWarnEnabled()) {
                log.warn("[{}] Data directory recovery aborted while waiting for enough free space",
                        hardwareId);
            }
            return FileProcessingResult.abort(ERROR_STORAGE_FULL);
        }
        if (log.isInfoEnabled()) {
            log.info("[{}] Save directory has recovered capacity, resuming sync from index {} using {}",
                    hardwareId,
                    index,
                    resumedContext.saveDir() != null ? resumedContext.saveDir().getAbsolutePath() : "null");
        }
        return FileProcessingResult.retryWithContext(resumedContext);
    }

    /**
     * Wait for non-admin user to dismiss storage unavailable alert.
     * Blocks until StorageRecoveryDeferredEvent is fired.
     */
    private void waitForStorageAlertDismissal(String hardwareId) {
        synchronized (recoveryLock) {
            saveRecoveryDeferred = false;

            while (!saveRecoveryDeferred && !shutdownRequested && !Thread.currentThread().isInterrupted()
                    && !isDeviceDead(hardwareId)) {
                try {
                    recoveryLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("[{}] Storage alert dismissed", hardwareId);
            }
        }
    }

    private SyncContext waitForDataDirRecovery(String hardwareId,
            SyncContext currentContext,
            long requiredBytes) {
        synchronized (recoveryLock) {
            saveRecoveryDeferred = false;
            saveRecoveryRestored = false;
        }

        while (shouldContinueRecoveryWait(hardwareId)) {
            if (!waitForRecoverySignal(hardwareId)) {
                return null;
            }

            if (isRecoveryDeferred(hardwareId)) {
                return null;
            }

            File latestDataDir = folderManagerService.getDataDir();
            if (!isRecoveredDataDirReady(latestDataDir, requiredBytes)) {
                logRecoveryDirNotReady(hardwareId, latestDataDir, requiredBytes);
                synchronized (recoveryLock) {
                    saveRecoveryRestored = false;
                }
                continue;
            }

            return buildRecoveredContext(hardwareId, currentContext, latestDataDir, requiredBytes);
        }
        return null;
    }

    private boolean waitForRecoverySignal(String hardwareId) {
        synchronized (recoveryLock) {
            while (!saveRecoveryDeferred
                    && !saveRecoveryRestored
                    && shouldContinueRecoveryWait(hardwareId)) {
                try {
                    recoveryLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !Thread.currentThread().isInterrupted();
        }
    }

    private boolean isRecoveryDeferred(String hardwareId) {
        if (!saveRecoveryDeferred) {
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug("[{}] Recovery wait canceled because user selected 'Later'", hardwareId);
        }
        return true;
    }

    private void logRecoveryDirNotReady(String hardwareId, File latestDataDir, long requiredBytes) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] Recovery signal received but save directory is not ready yet. dir={} requiredBytes={}",
                    hardwareId,
                    latestDataDir != null ? latestDataDir.getAbsolutePath() : "null",
                    requiredBytes);
        }
    }

    private SyncContext buildRecoveredContext(String hardwareId, SyncContext currentContext, File latestDataDir,
            long requiredBytes) {
        if (log.isInfoEnabled()) {
            log.info(
                    "[{}] Data directory recovery completed. activeSaveDir={} requiredBytes={}",
                    hardwareId,
                    latestDataDir.getAbsolutePath(),
                    requiredBytes);
        }
        return new SyncContext(
                currentContext.username(),
                currentContext.isAdmin(),
                latestDataDir,
                currentContext.autoDelete(),
                currentContext.deviceName(), hardwareId);
    }

    /**
     * Validate that the recovered save directory is accessible and has enough free
     * space.
     */
    private boolean isRecoveredDataDirReady(File dataDir, long requiredBytes) {
        if (dataDir == null || !folderManagerService.isDataDirAccessible(dataDir)) {
            return false;
        }
        return requiredBytes <= 0 || folderManagerService.hasSufficientSpace(dataDir, requiredBytes);
    }

    /**
     * Check if recovery wait loop should continue.
     */
    private boolean shouldContinueRecoveryWait(String hardwareId) {
        return !shutdownRequested && !Thread.currentThread().isInterrupted() && !isDeviceDead(hardwareId);
    }

    private void rebaseRemainingSyncFiles(List<SyncFile> syncFiles, int fromIndex, File newSaveDir) {
        for (int i = fromIndex; i < syncFiles.size(); i++) {
            SyncFile file = syncFiles.get(i);
            String rebasedLocalPath = resolveLocalPath(file.info(), file.remotePath(), newSaveDir);
            if (rebasedLocalPath == null) {
                continue;
            }

            String rebasedRelativePath;
            try {
                rebasedRelativePath = folderManagerService.toRelativeDataPath(rebasedLocalPath);
            } catch (IOException e) {
                rebasedRelativePath = file.relativeLocalPath();
            }

            syncFiles.set(i,
                    new SyncFile(file.remotePath(), rebasedLocalPath, rebasedRelativePath, file.info(), null));

        }
    }

    // -------------------------------------------------------------------------
    // Abort/failure bookkeeping
    // -------------------------------------------------------------------------

    /**
     * Check if processing can continue or should abort due to shutdown/disconnect.
     */
    private boolean checkCanContinueProcessing(String hardwareId, List<SyncFile> syncFiles, int index,
            List<SyncFile> failedList, SyncCounters counters) {
        if (shutdownRequested || Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId)) {
            String abortReason;
            String failureReason;

            if (isDeviceDead(hardwareId)) {
                abortReason = "Device disconnected";
                failureReason = ERROR_DISCONNECTED;
            } else if (shutdownRequested) {
                abortReason = "Shutdown requested (logout)";
                failureReason = ERROR_EXCEPTION;
            } else {
                abortReason = "Thread interrupted";
                failureReason = ERROR_EXCEPTION;
            }

            if (log.isWarnEnabled()) {
                log.warn("[{}] {}. Breaking sync file loop", hardwareId, abortReason);
            }
            failRemaining(hardwareId, syncFiles, index, failedList, counters, failureReason);
            return false;
        }
        return true;
    }

    private void failRemaining(String hardwareId, List<SyncFile> syncFiles, int fromIndex, List<SyncFile> failedList,
            SyncCounters counters, String reason) {
        int remainingCount = counters.total - counters.passed - counters.failed;
        failRemainingFiles(hardwareId, syncFiles, fromIndex, failedList, reason);

        counters.failed = failedList.size();

        progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);

        if (log.isWarnEnabled()) {
            log.warn("[{}] sync aborted. Marked remaining {} files as failed without retry",
                    hardwareId,
                    remainingCount);
        }
    }

    private void failRemainingFiles(String hardwareId, List<SyncFile> syncFiles,
            int fromIndex, List<SyncFile> failedList, String reason) {
        for (int i = fromIndex; i < syncFiles.size(); i++) {
            SyncFile remaining = syncFiles.get(i);
            failedList.add(new SyncFile(
                    remaining.remotePath(),
                    remaining.localPath(),
                    remaining.relativeLocalPath(),
                    remaining.info(),
                    reason));
        }
        for (SyncFile failedFile : failedList) {
            reason = failedFile.failureReason() != null ? failedFile.failureReason() : reason;
            queueManagerService.markSyncFileFailed(hardwareId, failedFile.relativeLocalPath(), reason);
        }
    }

    private void cleanupIncompleteFile(String localPath) {
        boolean deleted = deleteExistingFile(localPath);
        if (deleted && log.isDebugEnabled()) {
            log.debug("Cleaned up failed file: {}", localPath);
        }
        if (!deleted && log.isDebugEnabled()) {
            log.debug("Failed to clean up failed file: {}", localPath);
        }
    }

    private boolean deleteExistingFile(String path) {
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) {
            try {
                Files.delete(filePath);
            } catch (IOException e) {
                if (log.isWarnEnabled()) {
                    log.warn("Failed to delete file: {} message: {}", path, e.getMessage());
                }
                return false;
            }
        }
        return true;
    }

    private PullResult verifyFileSize(File file, long expectedSize, String remotePath) {
        long actualSize = file.length();

        if (actualSize != expectedSize) {
            if (log.isWarnEnabled()) {
                log.warn("File size verification failed: {} (expected: {} bytes, actual: {} bytes)",
                        name(remotePath), expectedSize, actualSize);
            }
            return PullResult.failure(ERROR_SIZE_MISMATCH);
        }
        return PullResult.success();
    }

    private boolean handleAlreadySyncedFile(String hardwareId,
            SyncContext syncContext,
            Set<String> syncedPaths,
            SyncFile file) {
        if (!syncedPaths.contains(file.relativeLocalPath())) {
            return false;
        }

        if (syncContext.autoDelete()) {
            deleteRemoteFile(hardwareId, file.remotePath());
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // User notifications
    // -------------------------------------------------------------------------

    /**
     * Show detailed sync failure summary with device name, counts, and failed file
     * table.
     */
    private void showSyncFailureSummary(String hardwareId, SyncContext syncContext, String deviceName,
            SyncCounters counters,
            List<SyncFile> failedList) {
        if (failedList.isEmpty()) {
            return;
        }

        List<FailureSummaryRow> rows = new ArrayList<>();
        for (SyncFile pf : failedList) {
            String fileName = name(pf.remotePath());
            String reason = pf.failureReason() != null ? pf.failureReason() : ERROR_UNKNOWN;
            rows.add(new FailureSummaryRow(fileName, I18n.get(reason)));
        }

        String header = I18n.get(SYNC_SUMMARY_HEADER, deviceName, counters.failed);
        String content = I18n.get(SYNC_SUMMARY_CONTENT, counters.total, counters.passed, counters.failed);
        appNoticeService.showError(header + "\n" + content);
        publisher.publishEvent(new FailureSummaryRequestedEvent(
                I18n.get(SYNC_SUMMARY_TITLE),
                header,
                content,
                I18n.get(SYNC_SUMMARY_COLUMN_FILENAME),
                I18n.get(SYNC_SUMMARY_COLUMN_REASON),
                rows,
                () -> requeueRetry(hardwareId, syncContext)));
    }

    /**
     * Queue a new sync attempt from the failure summary dialog or retry button.
     * Public to allow reuse from QueueDialogController.
     */
    public void requeueRetry(String hardwareId, SyncContext syncContext) {
        if (isDeviceDead(hardwareId)) {
            if (log.isWarnEnabled()) {
                log.warn("Cannot requeue sync for {} because device is disconnected", hardwareId);
            }
            appNoticeService.showError(I18n.get(ERROR_DISCONNECTED));
            return;
        }

        boolean queued = queue.add(hardwareId, syncContext);
        if (!queued) {
            if (log.isInfoEnabled()) {
                log.info("Skipped retry queue for {} because the device is already queued or syncing", hardwareId);
            }
            return;
        }
        log.info("Requeued sync for {} from failure summary dialog", hardwareId);
        appNoticeService.showSuccess(I18n.get("device.sync.queued", syncContext.deviceName()));
    }

    // Check if retry should be aborted due to shutdown, interruption, or device
    // disconnect
    private boolean shouldAbortRetry(String hardwareId) {
        return shutdownRequested || Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId);
    }

    // Log failed files after all retry attempts exhausted
    private void logFailedFiles(List<SyncFile> failedFilesRemaining) {
        for (SyncFile pf : failedFilesRemaining) {
            if (log.isWarnEnabled()) {
                String reasonKey = pf.failureReason() != null ? pf.failureReason() : ERROR_UNKNOWN;
                log.warn("File failed after retries: {} - Reason: {}", name(pf.remotePath()), I18n.get(reasonKey));
            }
        }
    }

    // Handle retry abortion mid-process, marking remaining files as failed
    private void handleAbortedRetry(String hardwareId, List<SyncFile> failedList, SyncFile currentFile,
            List<SyncFile> failedFilesRemaining, SyncCounters counters) {
        logAllRetryAbortion(hardwareId);

        collectRemainingFiles(failedList, currentFile, failedFilesRemaining);
        markPendingFilesFailedInQueue(hardwareId, failedFilesRemaining, getAbortReasonMessage(hardwareId));
        counters.failed += failedFilesRemaining.size();
        progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
        appNoticeService.showError(I18n.get(ERROR_DISCONNECTED));
        logFailedFiles(failedFilesRemaining);
    }

    /**
     * Ensure queue state transitions to failed for all pending files when retry is
     * skipped or aborted.
     */
    private void markPendingFilesFailedInQueue(String hardwareId, List<SyncFile> pendingFiles, String reason) {
        for (SyncFile pendingFile : pendingFiles) {
            queueManagerService.markSyncFileFailed(hardwareId, pendingFile.relativeLocalPath(), reason);
        }
    }

    // Collect all remaining files from current position to end of list
    private void collectRemainingFiles(List<SyncFile> failedList, SyncFile currentFile,
            List<SyncFile> failedFilesRemaining) {
        for (int i = failedList.indexOf(currentFile); i < failedList.size(); i++) {
            failedFilesRemaining.add(failedList.get(i));
        }
    }

    // Get localized abort reason message for queue status
    private String getAbortReasonMessage(String hardwareId) {
        if (isDeviceDead(hardwareId)) {
            return ERROR_DISCONNECTED;
        }
        return ERROR_EXCEPTION;
    }

    // Determine the reason for aborting retry
    private String getAbortReasonForLogging(String hardwareId) {
        if (isDeviceDead(hardwareId)) {
            return "Device disconnected";
        }
        return shutdownRequested ? "Shutdown requested" : "Thread interrupted";
    }

    // Log retry abortion with appropriate reason
    private void logOneRetryAbortion(String hardwareId, SyncFile pf) {
        if (log.isDebugEnabled()) {
            String abortReason = getAbortReasonForLogging(hardwareId);
            log.debug("[{}] Aborting retry for {} due to {}", hardwareId, name(pf.remotePath()), abortReason);
        }
    }

    // Log retry abortion with appropriate reason
    private void logAllRetryAbortion(String hardwareId) {
        String abortReason = getAbortReasonForLogging(hardwareId);
        if (log.isDebugEnabled()) {
            log.debug("[{}] {} during retry. Marking remaining files as failed", hardwareId, abortReason);
        }
    }

    // Log individual retry attempt failure
    private void logRetryAttemptFailure(int attempt, SyncFile pf) {
        if (log.isDebugEnabled()) {
            log.info("Retry attempt {}/{} failed for: {}", attempt, AppConstants.MAX_RETRY,
                    name(pf.remotePath()));
        }
    }

    private void logSyncedFile(String localPath, int current, int total) {
        String syncedFileName;
        try {
            syncedFileName = folderManagerService.toRelativeDataPath(localPath);
        } catch (IOException e) {
            syncedFileName = localPath;
        }
        log.info("Synced ({}/{}): {}", current, total, syncedFileName);
    }

    private void showErrorNotification(SyncFile pf) {
        String fileName = name(pf.remotePath());
        String reasonKey = pf.failureReason() != null ? pf.failureReason() : ERROR_UNKNOWN;
        String message = I18n.get("device.sync.error.failed", fileName) + "\n" +
                I18n.get("device.sync.error.reason", I18n.get(reasonKey));
        appNoticeService.showError(message);
    }

    private void deleteRemoteFile(String hardwareId, String remotePath) {
        if (remotePath.startsWith(MassStorageFileSource.MASS_STORAGE_PREFIX)) {
            log.debug("[{}] Skipping delete for mass storage file: {}", hardwareId, remotePath);
            return;
        }
        adbClient.deleteRemoteFile(hardwareId, remotePath);
    }

    // -------------------------------------------------------------------------
    // Event listeners
    // -------------------------------------------------------------------------

    // Listen for device disconnect events and mark device as disconnected to abort
    // sync
    @EventListener
    public void onDeviceEvent(DeviceEvent event) {
        if (event.type() == DeviceEvent.EventType.DISCONNECTED) {
            disconnectedDevices.add(event.hardwareId());
        } else if (event.type() == DeviceEvent.EventType.CONNECTED) {
            disconnectedDevices.remove(event.hardwareId());
        }
    }

    @EventListener
    public void onStorageRecoveryDeferred(StorageRecoveryDeferredEvent event) {
        if (event != null && event.getTarget() == FolderType.SYNC) {
            synchronized (recoveryLock) {
                saveRecoveryDeferred = true;
                saveRecoveryRestored = false;
                recoveryLock.notifyAll();
            }
            if (log.isDebugEnabled()) {
                log.debug("Storage recovery was deferred by user for target={}", event.getTarget());
            }
        }
    }

    @EventListener
    public void onStorageRestored(StorageRestoredEvent event) {
        if (event != null && event.getTarget() == FolderType.SYNC) {
            synchronized (recoveryLock) {
                saveRecoveryDeferred = false;
                saveRecoveryRestored = true;
                recoveryLock.notifyAll();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Small utilities
    // -------------------------------------------------------------------------

    // Check if device disconnected during sync using event-driven flag
    private boolean isDeviceDead(String hardwareId) {
        return disconnectedDevices.contains(hardwareId);
    }

    private String name(String path) {
        return path.substring(path.lastIndexOf("/") + 1);
    }
}
