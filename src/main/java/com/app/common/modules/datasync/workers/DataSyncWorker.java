package com.app.common.modules.datasync.workers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.dtos.FileInfo;
import com.app.common.dtos.SyncContext;
import com.app.common.events.DeviceEvent;
import com.app.common.exceptions.DeviceDisconnectedException;
import com.app.common.helpers.AlertHelper;
import com.app.common.modules.datasync.events.FileSyncCompletedEvent;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.datasync.services.DataSyncService;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.services.AdbClient;
import com.app.common.services.AppNoticeService;
import com.app.common.services.DriveLetterMapper;
import com.app.common.services.MassStorageFileSource;
import com.app.common.services.SyncProgressTracker;

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
    private final SyncProgressTracker progressTracker;
    private final ApplicationEventPublisher publisher;
    private final AppNoticeService appNoticeService;
    private final DriveLetterMapper driveLetterMapper;
    private final MassStorageFileSource massStorageFileSource;

    private final Map<String, String> driveLetterCache = new ConcurrentHashMap<>();
    private final Set<String> disconnectedDevices = ConcurrentHashMap.newKeySet();
    private volatile boolean shutdownRequested = false;
    private volatile boolean saveRecoveryDeferred = false;
    private final Object recoveryLock = new Object();

    // Records and inner classes
    private record SyncFile(String remotePath, String localPath, String relativeLocalPath, FileInfo info) {
    }

    private record PendingFile(String remotePath, String localPath, String relativeLocalPath, FileInfo info,
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

    private enum ProcessResult {
        SUCCESS,
        FAILED
    }

    private static final class PreflightException extends RuntimeException {
        private PreflightException(String message) {
            super(message);
        }
    }

    public DataSyncWorker(DeviceSyncQueue queue,
            DataSyncService dataSyncService,
            FolderManagerService folderManagerService,
            AdbClient adbClient,
            SyncProgressTracker progressTracker,
            ApplicationEventPublisher publisher,
            AppNoticeService appNoticeService,
            DriveLetterMapper driveLetterMapper,
            MassStorageFileSource massStorageFileSource) {
        this.queue = queue;
        this.dataSyncService = dataSyncService;
        this.folderManagerService = folderManagerService;
        this.adbClient = adbClient;
        this.progressTracker = progressTracker;
        this.publisher = publisher;
        this.appNoticeService = appNoticeService;
        this.driveLetterMapper = driveLetterMapper;
        this.massStorageFileSource = massStorageFileSource;
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

            List<PendingFile> failedList = new ArrayList<>();
            boolean shouldRetryFailures = processSyncFiles(hardwareId, syncContext, preparedSyncFiles,
                    failedList, counters);

            if (shouldRetryFailures && !failedList.isEmpty()) {
                retryFailed(hardwareId, syncContext, preparedSyncFiles, failedList, counters);
            }

            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.info("Finished sync for {}: total={}, passed={}, failed={}, elapsedMs={}",
                    hardwareId, counters.total, counters.passed, counters.failed, elapsedMs);

            if (counters.failed > 0 && !shutdownRequested) {
                showSyncFailureSummary(deviceName, counters, failedList);
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

    // Discover and prepare files for sync from device
    private SyncPreparation prepareSyncFiles(String hardwareId, Long deviceId, SyncContext syncContext,
            boolean autoDelete) {
        Set<String> syncedPaths = dataSyncService.loadSyncedPaths(deviceId);
        Set<String> relativeSyncedPaths = new java.util.HashSet<>();
        for (String syncedPath : syncedPaths) {
            PathResolutionResult resolution = folderManagerService
                    .findAbsolutePathFromNonDriveLetterPath(syncedPath);

            if (!resolution.isFound()) {
                if (log.isDebugEnabled()) {
                    log.debug("Synced path missing on disk, will resync: {}", syncedPath);
                }
                continue;
            }

            try {
                relativeSyncedPaths.add(folderManagerService.toRelativeDataPath(resolution.getPath().toString()));
            } catch (IOException e) {
                log.warn("Invalid syncedPath, resync: {}", syncedPath);
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

    private List<String> findFilesFromMassStorage(String hardwareId) {
        String driveLetter = driveLetterMapper.resolve(hardwareId);
        if (driveLetter == null) {
            throw new PreflightException(I18n.get(ERROR_BAD_CONNECTION));
        }
        driveLetterCache.put(hardwareId, driveLetter);
        log.info("[{}] MassStorage fallback: using drive {}", hardwareId, driveLetter);
        return massStorageFileSource.findFiles(driveLetter, AppConstants.MEDIA_TYPES);
    }

    // Process each file in the sync list
    private boolean processSyncFiles(String hardwareId, SyncContext syncContext, SyncPreparation prep,
            List<PendingFile> failedList, SyncCounters counters) {

        List<SyncFile> filesToBeProcessed = prep.filesToBeProcessed();
        int i = 0;
        while (i < filesToBeProcessed.size()) {
            SyncFile file = filesToBeProcessed.get(i);

            if (!checkCanContinueProcessing(hardwareId, filesToBeProcessed, i, failedList, counters)) {
                return false;
            }

            int current = counters.passed + counters.failed + 1;
            if (log.isInfoEnabled()) {
                log.info("Processing file ({}/{}): {}", current, counters.total, name(file.remotePath()));
            }

            FileProcessingResult result = processSingleFile(hardwareId, syncContext, prep, i, file,
                    failedList, counters);

            if (result.shouldAbort()) {
                return false;
            }

            if (result.contextUpdated()) {
                syncContext = result.updatedContext();
            }

            if (!result.shouldRetry()) {
                i++;
            }
        }

        return true;
    }

    /**
     * Check if processing can continue or should abort due to shutdown/disconnect.
     */
    private boolean checkCanContinueProcessing(String hardwareId, List<SyncFile> syncFiles, int index,
            List<PendingFile> failedList, SyncCounters counters) {
        if (shutdownRequested || Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId)) {
            String abortReason;
            String failureReason;

            if (isDeviceDead(hardwareId)) {
                abortReason = "Device disconnected";
                failureReason = I18n.get(ERROR_DISCONNECTED);
            } else if (shutdownRequested) {
                abortReason = "Shutdown requested (logout)";
                failureReason = I18n.get(ERROR_EXCEPTION);
            } else {
                abortReason = "Thread interrupted";
                failureReason = I18n.get(ERROR_EXCEPTION);
            }

            if (log.isWarnEnabled()) {
                log.warn("[{}] {}. Breaking sync file loop", hardwareId, abortReason);
            }
            failRemaining(hardwareId, syncFiles, index, failedList, counters, failureReason);
            return false;
        }
        return true;
    }

    /**
     * Process a single file and return the result indicating next action.
     */
    private FileProcessingResult processSingleFile(String hardwareId, SyncContext syncContext, SyncPreparation prep,
            int index, SyncFile file, List<PendingFile> failedList, SyncCounters counters) {

        if (handleAlreadySyncedFile(hardwareId, syncContext, prep.syncedPaths(), file)) {
            counters.passed++;
            progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
            return FileProcessingResult.proceed();
        }

        List<SyncFile> filesToBeProcessed = prep.filesToBeProcessed();
        if (!folderManagerService.isDataDirAccessible(syncContext.saveDir())) {
            return handleDataDirInaccessible(hardwareId, syncContext, filesToBeProcessed, index, failedList, counters);
        }

        SyncFile sizedFile = withResolvedRemoteSize(hardwareId, file, failedList);
        if (sizedFile == null) {
            return FileProcessingResult.proceed();
        }
        filesToBeProcessed.set(index, sizedFile);
        file = sizedFile;

        long requiredBytes = file.info().size();
        if (!prep.syncedPaths().contains(file.relativeLocalPath())
                && requiredBytes > 0
                && !folderManagerService.hasSufficientSpace(syncContext.saveDir(), requiredBytes)) {
            return handleInsufficientSpace(hardwareId, syncContext, filesToBeProcessed, index, requiredBytes,
                    failedList,
                    counters);
        }

        ProcessResult result = processFile(hardwareId, syncContext, prep, file, failedList, counters);

        if (result != ProcessResult.FAILED) {
            counters.passed++;
        }

        if (result == ProcessResult.SUCCESS) {
            progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
        }

        return FileProcessingResult.proceed();
    }

    /**
     * Handle data directory inaccessibility during file processing.
     */
    private FileProcessingResult handleDataDirInaccessible(String hardwareId, SyncContext syncContext,
            List<SyncFile> filesToBeProcessed, int index, List<PendingFile> failedList, SyncCounters counters) {
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
            String failureReason = I18n.get(ERROR_STORAGE_UNAVAILABLE);
            if (log.isWarnEnabled()) {
                log.warn(
                        "[{}] Non-admin user cannot recover from inaccessible save directory, waiting for alert dismissal",
                        hardwareId);
            }
            waitForStorageAlertDismissal(hardwareId);
            failRemaining(hardwareId, filesToBeProcessed, index, failedList, counters, failureReason);
            return FileProcessingResult.abort();
        }

        SyncContext resumedContext = waitForDataDirRecovery(hardwareId, syncContext, filesToBeProcessed, index, 0L);
        if (resumedContext == null) {
            String failureReason = I18n.get(ERROR_STORAGE_UNAVAILABLE);
            if (log.isWarnEnabled()) {
                log.warn("[{}] Data directory recovery aborted while waiting for accessible save dir",
                        hardwareId);
            }
            failRemaining(hardwareId, filesToBeProcessed, index, failedList, counters, failureReason);
            return FileProcessingResult.abort();
        }
        if (log.isInfoEnabled()) {
            log.info("[{}] Save directory recovered, resuming sync from index {} using {}",
                    hardwareId,
                    index,
                    resumedContext.saveDir() != null ? resumedContext.saveDir().getAbsolutePath() : "null");
        }
        return FileProcessingResult.retryWithContext(resumedContext);
    }

    /**
     * Handle insufficient space during file processing.
     */
    private FileProcessingResult handleInsufficientSpace(String hardwareId, SyncContext syncContext,
            List<SyncFile> filesToBeProcessed, int index, long requiredBytes, List<PendingFile> failedList,
            SyncCounters counters) {
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
            String failureReason = I18n.get(ERROR_STORAGE_FULL);
            if (log.isWarnEnabled()) {
                log.warn("[{}] Non-admin user cannot recover from insufficient space, waiting for alert dismissal",
                        hardwareId);
            }
            waitForStorageAlertDismissal(hardwareId);
            failRemaining(hardwareId, filesToBeProcessed, index, failedList, counters, failureReason);
            return FileProcessingResult.abort();
        }

        SyncContext resumedContext = waitForDataDirRecovery(hardwareId, syncContext, filesToBeProcessed, index,
                requiredBytes);
        if (resumedContext == null) {
            String failureReason = I18n.get(ERROR_STORAGE_FULL);
            if (log.isWarnEnabled()) {
                log.warn("[{}] Data directory recovery aborted while waiting for enough free space",
                        hardwareId);
            }
            failRemaining(hardwareId, filesToBeProcessed, index, failedList, counters, failureReason);
            return FileProcessingResult.abort();
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
     * Result of processing a single file, indicating next action.
     */
    private static class FileProcessingResult {
        private final boolean abort;
        private final boolean retry;
        private final SyncContext updatedContext;

        private FileProcessingResult(boolean abort, boolean retry, SyncContext updatedContext) {
            this.abort = abort;
            this.retry = retry;
            this.updatedContext = updatedContext;
        }

        public static FileProcessingResult proceed() {
            return new FileProcessingResult(false, false, null);
        }

        public static FileProcessingResult abort() {
            return new FileProcessingResult(true, false, null);
        }

        public static FileProcessingResult retryWithContext(SyncContext context) {
            return new FileProcessingResult(false, true, context);
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

    @SuppressWarnings("java:S1751")
    private SyncContext waitForDataDirRecovery(String hardwareId,
            SyncContext currentContext,
            List<SyncFile> syncFiles,
            int fromIndex,
            long requiredBytes) {
        synchronized (recoveryLock) {
            saveRecoveryDeferred = false;

            while (shouldContinueRecoveryWait(hardwareId)) {
                try {
                    recoveryLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }

                // Event fired, check which one
                if (saveRecoveryDeferred) {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] Recovery wait canceled because user selected 'Later'", hardwareId);
                    }
                    return null;
                }

                // StorageRestoredEvent fired, storage is valid
                File latestDataDir = folderManagerService.getDataDir();
                if (log.isInfoEnabled()) {
                    log.info(
                            "[{}] Data directory recovery completed. activeSaveDir={} requiredBytes={}",
                            hardwareId,
                            latestDataDir != null ? latestDataDir.getAbsolutePath() : "null",
                            requiredBytes);
                }
                rebasePendingSyncFiles(syncFiles, fromIndex, latestDataDir);
                return new SyncContext(
                        currentContext.username(),
                        currentContext.isAdmin(),
                        latestDataDir,
                        currentContext.autoDelete(),
                        currentContext.deviceName());
            }
            return null;
        }
    }

    /**
     * Check if recovery wait loop should continue.
     */
    private boolean shouldContinueRecoveryWait(String hardwareId) {
        return !shutdownRequested && !Thread.currentThread().isInterrupted() && !isDeviceDead(hardwareId);
    }

    private void rebasePendingSyncFiles(List<SyncFile> syncFiles, int fromIndex, File newSaveDir) {
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
                    new SyncFile(file.remotePath(), rebasedLocalPath, rebasedRelativePath, file.info()));
        }
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

    private void failRemainingFiles(List<SyncFile> syncFiles,
            int fromIndex,
            List<PendingFile> failedList,
            String reason) {
        for (int i = fromIndex; i < syncFiles.size(); i++) {
            SyncFile remaining = syncFiles.get(i);
            failedList.add(new PendingFile(
                    remaining.remotePath(),
                    remaining.localPath(),
                    remaining.relativeLocalPath(),
                    remaining.info(),
                    reason));
        }
    }

    private void failRemaining(String hardwareId,
            List<SyncFile> syncFiles,
            int fromIndex,
            List<PendingFile> failedList,
            SyncCounters counters,
            String reason) {
        int remainingCount = syncFiles.size() - fromIndex;
        failRemainingFiles(syncFiles, fromIndex, failedList, reason);
        counters.failed = failedList.size();
        progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);

        if (log.isWarnEnabled()) {
            log.warn("[{}] sync aborted. Marked remaining {} files as failed without retry",
                    hardwareId,
                    remainingCount);
        }
    }

    /**
     * Show detailed sync failure summary with device name, counts, and failed file
     * table.
     */
    private void showSyncFailureSummary(String deviceName, SyncCounters counters,
            List<PendingFile> failedList) {
        if (failedList.isEmpty()) {
            return;
        }

        // Convert PendingFile list to FailedFileRow for table display
        List<FailedFileRow> rows = new ArrayList<>();
        for (PendingFile pf : failedList) {
            String fileName = name(pf.remotePath());
            String reason = pf.failureReason() != null ? pf.failureReason() : I18n.get(ERROR_UNKNOWN);
            rows.add(new FailedFileRow(fileName, reason));
        }

        // Show detailed failure dialog with scrollable table
        String header = I18n.get(SYNC_SUMMARY_HEADER, deviceName);
        String content = I18n.get(SYNC_SUMMARY_CONTENT, deviceName, counters.total, counters.passed, counters.failed);
        appNoticeService.showError(header + "\n" + content);
        AlertHelper.DialogText dialogText = new AlertHelper.DialogText(
                I18n.get(SYNC_SUMMARY_TITLE),
                header,
                content);
        AlertHelper.showAlertWithTable(
                dialogText,
                rows,
                I18n.get(SYNC_SUMMARY_COLUMN_FILENAME),
                "fileName",
                I18n.get(SYNC_SUMMARY_COLUMN_REASON),
                "reason");
    }

    /**
     * Row model for failed file table display.
     */
    public static class FailedFileRow {
        private final String fileName;
        private final String reason;

        public FailedFileRow(String fileName, String reason) {
            this.fileName = fileName;
            this.reason = reason;
        }

        public String getFileName() {
            return fileName;
        }

        public String getReason() {
            return reason;
        }
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
                if (!syncedPaths.contains(syncFile.relativeLocalPath())) {
                    unsyncedFiles.add(syncFile);
                } else {
                    alreadySyncedCount++;
                }
            }
        }

        return new SyncFileCollection(allSyncFiles, unsyncedFiles, alreadySyncedCount);
    }

    // Create a new SyncFile with updated size information
    private SyncFile createFileWithSize(SyncFile file, long size) {
        FileInfo updatedInfo = new FileInfo(file.info().cameraId(), file.info().username(),
                file.info().createDate(), size, file.info().type());
        return new SyncFile(file.remotePath(), file.localPath(), file.relativeLocalPath(), updatedInfo);
    }

    private SyncFile withResolvedRemoteSize(String hardwareId, SyncFile file, List<PendingFile> failedList) {
        try {
            long remoteSize = getRemoteSize(hardwareId, file.remotePath());
            return createFileWithSize(file, remoteSize);
        } catch (DeviceDisconnectedException e) {
            disconnectedDevices.add(hardwareId);
            failedList.add(new PendingFile(file.remotePath(), file.localPath(), file.relativeLocalPath(), file.info(),
                    I18n.get(ERROR_DISCONNECTED)));
            return null;
        } catch (IOException e) {
            failedList.add(new PendingFile(file.remotePath(), file.localPath(), file.relativeLocalPath(), file.info(),
                    I18n.get(ERROR_REMOTE_SIZE_UNAVAILABLE)));
            return null;
        }
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

        return new SyncFile(path, localPath, relativeLocalPath, info);
    }

    private ProcessResult processFile(String hardwareId, SyncContext syncContext, SyncPreparation prep, SyncFile file,
            List<PendingFile> failed, SyncCounters counters) {

        LookupCache lookupCache = prep.lookupCache();

        Set<String> syncedPaths = prep.syncedPaths();

        Long dId = lookupCache.deviceId(file.info().cameraId());
        Long uId = lookupCache.userId(file.info().username());
        if (dId == null || uId == null) {
            String reason = dId == null ? I18n.get(ERROR_DEVICE_NOT_FOUND)
                    : I18n.get(ERROR_USER_NOT_FOUND);
            failed.add(new PendingFile(file.remotePath(), file.localPath(), file.relativeLocalPath(), file.info(),
                    reason));
            return ProcessResult.FAILED;
        }

        PullResult result = pullAndVerify(hardwareId, file.remotePath(), file.localPath(), file.info().size());
        if (result.isSuccess()) {
            String nonDriverLetterSyncedPath = folderManagerService.stripDriveLetter(file.localPath());
            dataSyncService.saveFile(uId, dId, name(file.remotePath()), nonDriverLetterSyncedPath, file.info());
            syncedPaths.add(file.relativeLocalPath());
            logSyncedFile(file.localPath(), counters.passed + 1, counters.total);

            publisher.publishEvent(new FileSyncCompletedEvent(hardwareId, nonDriverLetterSyncedPath));

            if (syncContext.autoDelete()) {
                deleteRemoteFile(hardwareId, file.remotePath());
            }
            return ProcessResult.SUCCESS;
        }

        cleanupIncompleteFile(file.localPath());
        failed.add(new PendingFile(file.remotePath(), file.localPath(), file.relativeLocalPath(), file.info(),
                result.failureReason()));
        return ProcessResult.FAILED;
    }

    private List<String> findFiles(String hardwareId, String root) {
        try {
            return adbClient.findFiles(hardwareId, root, AppConstants.MEDIA_TYPES);
        } catch (DeviceDisconnectedException e) {
            disconnectedDevices.add(hardwareId);
            throw new PreflightException(I18n.get(ERROR_DISCONNECTED));
        }
    }

    // Retry failed file transfers up to MAX_RETRY times, updating counters directly
    private void retryFailed(String hardwareId, SyncContext syncContext, SyncPreparation prep,
            List<PendingFile> failedList,
            SyncCounters counters) {
        if (isDeviceDead(hardwareId)) {
            handleDeviceDisconnectedDuringRetry(hardwareId, failedList, counters);
            return;
        }

        List<PendingFile> failedFilesRemaining = new ArrayList<>();

        for (PendingFile pf : failedList) {
            if (shouldAbortRetry(hardwareId)) {
                handleAbortedRetry(hardwareId, failedList, pf, failedFilesRemaining, counters);
                return;
            }

            boolean success = retryFileWithAttempts(hardwareId, pf, prep, syncContext, counters);
            updateCountersAfterRetry(success, pf, failedFilesRemaining, counters);
            progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
        }

        logAndCleanupFailedFiles(failedFilesRemaining);
    }

    // Check if retry should be aborted due to shutdown, interruption, or device
    // disconnect
    private boolean shouldAbortRetry(String hardwareId) {
        return shutdownRequested || Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId);
    }

    // Handle device disconnection before retry starts
    private void handleDeviceDisconnectedDuringRetry(String hardwareId, List<PendingFile> failedList,
            SyncCounters counters) {
        counters.failed += failedList.size();
        progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
        appNoticeService.showError(I18n.get(ERROR_DISCONNECTED));
        logAndCleanupFailedFiles(failedList);
    }

    // Handle retry abortion mid-process, marking remaining files as failed
    private void handleAbortedRetry(String hardwareId, List<PendingFile> failedList, PendingFile currentFile,
            List<PendingFile> failedFilesRemaining, SyncCounters counters) {
        String abortReason = getAbortReason(hardwareId);
        if (log.isWarnEnabled()) {
            log.warn("[{}] {} during retry. Marking remaining files as failed", hardwareId, abortReason);
        }

        collectRemainingFiles(failedList, currentFile, failedFilesRemaining);
        counters.failed += failedFilesRemaining.size();
        progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
        appNoticeService.showError(I18n.get(ERROR_DISCONNECTED));
        logAndCleanupFailedFiles(failedFilesRemaining);
    }

    // Determine the reason for aborting retry
    private String getAbortReason(String hardwareId) {
        if (isDeviceDead(hardwareId)) {
            return "Device disconnected";
        }
        return shutdownRequested ? "Shutdown requested" : "Thread interrupted";
    }

    // Collect all remaining files from current position to end of list
    private void collectRemainingFiles(List<PendingFile> failedList, PendingFile currentFile,
            List<PendingFile> failedFilesRemaining) {
        for (int i = failedList.indexOf(currentFile); i < failedList.size(); i++) {
            failedFilesRemaining.add(failedList.get(i));
        }
    }

    // Update counters and tracking after a retry attempt completes
    private void updateCountersAfterRetry(boolean success, PendingFile pf, List<PendingFile> failedFilesRemaining,
            SyncCounters counters) {
        if (success) {
            counters.passed++;
        } else {
            failedFilesRemaining.add(pf);
            counters.failed++;
            showErrorNotification(pf);
        }
    }

    // Attempt to retry a single file up to MAX_RETRY times
    private boolean retryFileWithAttempts(String hardwareId, PendingFile pf, SyncPreparation prep,
            SyncContext syncContext, SyncCounters counters) {
        for (int attempt = 1; attempt <= AppConstants.MAX_RETRY; attempt++) {
            if (shouldAbortRetry(hardwareId)) {
                logRetryAbortion(hardwareId, pf);
                return false;
            }

            if (retryOneFile(hardwareId, pf, prep, syncContext, counters)) {
                return true;
            }

            logRetryAttemptFailure(attempt, pf);
        }
        return false;
    }

    // Log retry abortion with appropriate reason
    private void logRetryAbortion(String hardwareId, PendingFile pf) {
        if (log.isDebugEnabled()) {
            String abortReason = getAbortReason(hardwareId);
            log.debug("[{}] Aborting retry for {} due to {}", hardwareId, name(pf.remotePath()), abortReason);
        }
    }

    // Log individual retry attempt failure
    private void logRetryAttemptFailure(int attempt, PendingFile pf) {
        if (attempt < AppConstants.MAX_RETRY && log.isInfoEnabled()) {
            log.info("Retry attempt {}/{} failed for: {}", attempt, AppConstants.MAX_RETRY,
                    name(pf.remotePath()));
        }
    }

    // Retry syncing a single failed file, returns true if successful
    private boolean retryOneFile(String hardwareId, PendingFile pf, SyncPreparation prep, SyncContext syncContext,
            SyncCounters counters) {

        Long dId = prep.lookupCache().deviceId(pf.info().cameraId());
        Long uId = prep.lookupCache().userId(pf.info().username());

        if (dId == null || uId == null) {
            return false;
        }

        long expectedSize = pf.info().size();
        if (expectedSize <= 0) {
            try {
                expectedSize = getRemoteSize(hardwareId, pf.remotePath());
            } catch (DeviceDisconnectedException e) {
                disconnectedDevices.add(hardwareId);
                return false;
            } catch (IOException e) {
                return false;
            }
        }

        PullResult result = pullAndVerify(hardwareId, pf.remotePath(), pf.localPath(), expectedSize);
        if (!result.isSuccess()) {
            return false;
        }

        String nonDriverLetterSyncedPath = folderManagerService.stripDriveLetter(pf.localPath());
        dataSyncService.saveFile(uId, dId, name(pf.remotePath()), nonDriverLetterSyncedPath, pf.info());
        prep.syncedPaths().add(pf.relativeLocalPath());
        logSyncedFile(pf.localPath(), counters.passed + 1, counters.total);

        publisher.publishEvent(new FileSyncCompletedEvent(hardwareId, nonDriverLetterSyncedPath));

        if (syncContext.autoDelete()) {
            deleteRemoteFile(hardwareId, pf.remotePath());
        }
        return true;
    }

    private void showErrorNotification(PendingFile pf) {
        String fileName = name(pf.remotePath());
        String reason = pf.failureReason() != null ? pf.failureReason() : I18n.get(ERROR_UNKNOWN);
        String message = I18n.get("device.sync.error.failed", fileName) + "\n" +
                I18n.get("device.sync.error.reason", reason);
        appNoticeService.showError(message);
    }

    // Log and clean up failed files after all retry attempts exhausted
    private void logAndCleanupFailedFiles(List<PendingFile> failedFilesRemaining) {
        for (PendingFile pf : failedFilesRemaining) {
            logFailedFile(pf);
            cleanupIncompleteFile(pf.localPath());
        }
    }

    private void logFailedFile(PendingFile pf) {
        if (log.isWarnEnabled()) {
            String reason = pf.failureReason() != null ? pf.failureReason() : "Unknown error";
            log.warn("File failed after retries: {} - Reason: {}", name(pf.remotePath()), reason);
        }
    }

    private void cleanupIncompleteFile(String localPath) {
        if (deleteExistingFile(localPath) && log.isDebugEnabled()) {
            log.debug("Cleaned up failed file: {}", localPath);
        }
    }

    private void logSyncedFile(String localPath, int current, int total) {
        try {
            String relativeName = folderManagerService.toRelativeDataPath(localPath);
            log.info("Synced ({}/{}): {}", current, total, relativeName);
        } catch (IOException e) {
            log.info("Synced ({}/{}): {}", current, total, localPath);
        }
    }

    // Build local file path from FileInfo and remote path structure using captured
    // saveDir. Applies filename hashing with CLSID suffix for obfuscation.
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

    // Pull file from device and verify size against the expected remote size.
    private PullResult pullAndVerify(String hardwareId, String remotePath, String localPath, long expectedSize) {
        try {
            return folderManagerService
                    .withSpecificDirPrepared(new File(localPath).getParentFile(),
                            () -> pullAndVerifyInternal(hardwareId, remotePath, localPath, expectedSize));
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Exception during pullAndVerify for {} -> {}: {}", name(remotePath), localPath, e.getMessage(),
                        e);
            }
            return PullResult.failure(I18n.get(ERROR_EXCEPTION));
        }
    }

    private PullResult pullAndVerifyInternal(String hardwareId, String remotePath, String localPath,
            long expectedSize) {
        File f = new File(localPath);

        if (!deleteExistingFile(localPath)) {
            return PullResult.failure(I18n.get(ERROR_PERMISSION_DENIED));
        }

        PullResult pullResult = pullFile(hardwareId, remotePath, localPath);
        if (!pullResult.isSuccess()) {
            if (log.isWarnEnabled()) {
                log.warn("ADB pull failed for: {} -> {} - Reason: {}", name(remotePath), localPath,
                        pullResult.failureReason());
            }
            return pullResult;
        }

        if (!f.exists()) {
            if (log.isWarnEnabled()) {
                File parent = f.getParentFile();
                log.warn("File not created after successful pull: {} (parent exists: {}, parent writable: {})",
                        localPath, parent != null && parent.exists(), parent != null && parent.canWrite());
            }
            return PullResult.failure(I18n.get(ERROR_FILE_NOT_CREATED));
        }

        return verifyFileSize(f, expectedSize, remotePath);
    }

    private boolean deleteExistingFile(String path) {
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) {
            try {
                Files.delete(filePath);
            } catch (IOException e) {
                if (log.isWarnEnabled()) {
                    log.warn("Failed to delete existing file before pull: {} - {}", path, e.getMessage());
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
            return PullResult.failure(I18n.get(ERROR_SIZE_MISMATCH));
        }

        return PullResult.success();
    }

    private String getExternalStorage(String hardwareId) {
        try {
            return adbClient.getExternalStorage(hardwareId);
        } catch (DeviceDisconnectedException e) {
            disconnectedDevices.add(hardwareId);
            throw new PreflightException(I18n.get(ERROR_DISCONNECTED));
        }
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

    private PullResult pullFile(String hardwareId, String remotePath, String localPath) {
        // Fail fast if device disconnected to avoid unnecessary ADB operations
        if (isDeviceDead(hardwareId)) {
            return PullResult.failure(I18n.get(ERROR_DISCONNECTED));
        }

        if (remotePath.startsWith(MassStorageFileSource.MASS_STORAGE_PREFIX)) {
            String driveLetter = driveLetterCache.get(hardwareId);
            if (driveLetter == null) {
                return PullResult.failure(I18n.get(ERROR_TRANSFER));
            }
            boolean success = massStorageFileSource.copyFile(driveLetter, remotePath, localPath);
            return success ? PullResult.success() : PullResult.failure(I18n.get(ERROR_TRANSFER));
        }
        try {
            boolean success = adbClient.pullFile(hardwareId, remotePath, localPath);
            return success ? PullResult.success() : PullResult.failure(I18n.get(ERROR_ADB_PULL_FAILED));
        } catch (DeviceDisconnectedException e) {
            disconnectedDevices.add(hardwareId);
            return PullResult.failure(I18n.get(ERROR_DISCONNECTED));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith(AdbClient.ERR_DEVICE_NOT_FOUND)) {
                return PullResult.failure(I18n.get(ERROR_BAD_CONNECTION));
            }
            return PullResult.failure(I18n.get(ERROR_ADB_PULL_FAILED));
        }
    }

    private void deleteRemoteFile(String hardwareId, String remotePath) {
        if (remotePath.startsWith(MassStorageFileSource.MASS_STORAGE_PREFIX)) {
            log.debug("[{}] Skipping delete for mass storage file: {}", hardwareId, remotePath);
            return;
        }
        adbClient.deleteRemoteFile(hardwareId, remotePath);
    }

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
                recoveryLock.notifyAll();
            }
        }
    }

    // Check if device disconnected during sync using event-driven flag
    private boolean isDeviceDead(String hardwareId) {
        return disconnectedDevices.contains(hardwareId);
    }

    private String name(String path) {
        return path.substring(path.lastIndexOf("/") + 1);
    }
}
