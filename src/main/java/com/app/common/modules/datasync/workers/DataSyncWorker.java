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
import com.app.common.dtos.FileInfo;
import com.app.common.dtos.SyncContext;
import com.app.common.events.DeviceEvent;
import com.app.common.exceptions.DeviceDisconnectedException;
import com.app.common.modules.datasync.events.FileSyncCompletedEvent;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.datasync.services.DataSyncService;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.events.StorageIssueReason;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.foldermanager.utils.FilePathHasher;
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
            LookupCache lookupCache) {
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

            adbClient.stopCameraService(hardwareId);
            adbClient.setUsbFunctionsNone(hardwareId);

            boolean autoDelete = syncContext.autoDelete();

            SyncPreparation preparedSyncFiles = prepareSyncFiles(hardwareId, deviceId, syncContext, autoDelete);

            List<SyncFile> filesToBeProcessed = autoDelete ? preparedSyncFiles.fileCollection().allSyncFiles()
                    : preparedSyncFiles.fileCollection().unsyncedFiles();

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
                    filesToBeProcessed, failedList, counters);

            if (shouldRetryFailures && !failedList.isEmpty()) {
                retryFailed(hardwareId, syncContext, preparedSyncFiles, failedList, counters);
            }

            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.info("Finished sync for {}: total={}, passed={}, failed={}, elapsedMs={}",
                    hardwareId, counters.total, counters.passed, counters.failed, elapsedMs);
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
                lookupCache, autoDelete);

        return new SyncPreparation(fileCollection, relativeSyncedPaths, lookupCache);
    }

    private List<String> findFilesFromMassStorage(String hardwareId) {
        String driveLetter = driveLetterMapper.resolve(hardwareId);
        if (driveLetter == null) {
            throw new PreflightException(I18n.get("device.sync.error.bad_connection"));
        }
        driveLetterCache.put(hardwareId, driveLetter);
        log.info("[{}] MassStorage fallback: using drive {}", hardwareId, driveLetter);
        return massStorageFileSource.findFiles(driveLetter, AppConstants.MEDIA_TYPES);
    }

    // Process each file in the sync list
    private boolean processSyncFiles(String hardwareId, SyncContext syncContext, SyncPreparation prep,
            List<SyncFile> syncFiles,
            List<PendingFile> failedList, SyncCounters counters) {

        for (int i = 0; i < syncFiles.size(); i++) {
            SyncFile file = syncFiles.get(i);

            if (shutdownRequested || Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId)) {
                if (log.isWarnEnabled()) {
                    log.warn("Logged out or Device disconnected. Break sync file loop");
                }
                String reason = isDeviceDead(hardwareId)
                        ? I18n.get("device.sync.error.disconnected")
                        : I18n.get("device.sync.error.exception");
                failRemainingWithSingleNotice(hardwareId, syncFiles, i, failedList, counters, reason);
                return false;
            }

            int current = counters.passed + counters.failed + 1;
            if (log.isInfoEnabled()) {
                log.info("Processing file ({}/{}): {}", current, counters.total, name(file.remotePath()));
            }

            if (handleAlreadySyncedFile(hardwareId, syncContext, prep.syncedPaths(), file)) {
                counters.passed++;
                progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
                continue;
            }

            if (!folderManagerService.isDataDirAccessible(syncContext.saveDir())) {
                if (log.isWarnEnabled()) {
                    log.warn("[{}] Save directory became inaccessible during sync: {}",
                            hardwareId,
                            syncContext.saveDir() != null ? syncContext.saveDir().getAbsolutePath() : "null");
                }
                publisher.publishEvent(
                        new StorageUnavailableEvent(FolderType.SAVE, StorageIssueReason.DRIVE_UNAVAILABLE));
                SyncContext resumedContext = waitForDataDirRecovery(hardwareId, syncContext, syncFiles, i, 0L);
                if (resumedContext == null) {
                    String failureReason = I18n.get("device.sync.error.storage_unavailable");
                    if (log.isWarnEnabled()) {
                        log.warn("[{}] Data directory recovery aborted while waiting for accessible save dir",
                                hardwareId);
                    }
                    failRemainingWithSingleNotice(hardwareId, syncFiles, i, failedList, counters, failureReason);
                    return false;
                }
                if (log.isInfoEnabled()) {
                    log.info("[{}] Save directory recovered, resuming sync from index {} using {}",
                            hardwareId,
                            i,
                            resumedContext.saveDir() != null ? resumedContext.saveDir().getAbsolutePath() : "null");
                }
                syncContext = resumedContext;
                i--;
                continue;
            }

            // Resolve remote size up front and persist it in FileInfo so we can
            // reuse the same expected size through pull + verify and retries.
            SyncFile sizedFile = withResolvedRemoteSize(hardwareId, file, failedList);
            if (sizedFile == null) {
                continue;
            }
            syncFiles.set(i, sizedFile);
            file = sizedFile;

            long requiredBytes = file.info().size();
            if (!prep.syncedPaths().contains(file.relativeLocalPath())
                    && requiredBytes > 0
                    && !folderManagerService.hasSufficientSpace(syncContext.saveDir(), requiredBytes)) {
                if (log.isWarnEnabled()) {
                    log.warn("[{}] Insufficient space detected for {} (required={} bytes, saveDir={})",
                            hardwareId,
                            name(file.remotePath()),
                            requiredBytes,
                            syncContext.saveDir() != null ? syncContext.saveDir().getAbsolutePath() : "null");
                }
                publisher.publishEvent(
                        new StorageUnavailableEvent(FolderType.SAVE, StorageIssueReason.LOW_SPACE, requiredBytes));
                SyncContext resumedContext = waitForDataDirRecovery(hardwareId, syncContext, syncFiles, i,
                        requiredBytes);
                if (resumedContext == null) {
                    String failureReason = I18n.get("device.sync.error.storage_full");
                    if (log.isWarnEnabled()) {
                        log.warn("[{}] Data directory recovery aborted while waiting for enough free space",
                                hardwareId);
                    }
                    failRemainingWithSingleNotice(hardwareId, syncFiles, i, failedList, counters, failureReason);
                    return false;
                }
                if (log.isInfoEnabled()) {
                    log.info("[{}] Save directory has recovered capacity, resuming sync from index {} using {}",
                            hardwareId,
                            i,
                            resumedContext.saveDir() != null ? resumedContext.saveDir().getAbsolutePath() : "null");
                }
                syncContext = resumedContext;
                i--;
                continue;
            }

            ProcessResult result = processFile(hardwareId, syncContext, prep, file, failedList, counters);

            if (result != ProcessResult.FAILED) {
                counters.passed++;
            }

            if (result == ProcessResult.SUCCESS) {
                progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
            }
        }

        return true;
    }

    private SyncContext waitForDataDirRecovery(String hardwareId,
            SyncContext currentContext,
            List<SyncFile> syncFiles,
            int fromIndex,
            long requiredBytes) {
        int attempts = 0;
        String lastRejectedPath = null;

        while (!shutdownRequested && !Thread.currentThread().isInterrupted() && !isDeviceDead(hardwareId)) {
            if (saveRecoveryDeferred) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Recovery wait canceled because user selected 'Later'", hardwareId);
                }
                return null;
            }

            attempts++;
            File latestDataDir = folderManagerService.getDataDir();
            String rejectionReason = validateRecoveryDataDir(latestDataDir, requiredBytes);
            if (rejectionReason == null) {
                if (log.isInfoEnabled()) {
                    log.info(
                            "[{}] Data directory recovery completed after {} checks. activeSaveDir={} requiredBytes={}",
                            hardwareId,
                            attempts,
                            latestDataDir != null ? latestDataDir.getAbsolutePath() : "null",
                            requiredBytes);
                }
                SyncContext updatedContext = new SyncContext(
                        currentContext.username(),
                        currentContext.isAdmin(),
                        latestDataDir,
                        currentContext.autoDelete());
                rebasePendingSyncFiles(syncFiles, fromIndex, latestDataDir);
                return updatedContext;
            }

            String candidatePath = latestDataDir != null ? latestDataDir.getAbsolutePath() : "null";
            boolean pathChanged = !candidatePath.equals(lastRejectedPath);
            if (pathChanged || attempts % 10 == 0) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[{}] Waiting for save directory recovery: attempt={} candidate={} requiredBytes={} reason={}",
                            hardwareId, attempts, candidatePath, requiredBytes, rejectionReason);
                }
                lastRejectedPath = candidatePath;
            }

            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private String validateRecoveryDataDir(File dataDir, long requiredBytes) {
        if (dataDir == null) {
            return "save_dir_not_configured";
        }

        if (!folderManagerService.isDataDirAccessible(dataDir)) {
            return "save_dir_drive_unavailable";
        }

        if (requiredBytes > 0 && !folderManagerService.hasSufficientSpace(dataDir, requiredBytes)) {
            return "save_dir_insufficient_space";
        }

        try {
            folderManagerService.withSpecificDirPrepared(dataDir, () -> Boolean.TRUE);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Save directory failed readiness check: {} - {}",
                        dataDir.getAbsolutePath(),
                        e.getMessage());
            }
            return "save_dir_not_ready";
        }

        return null;
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

    private void failRemainingWithSingleNotice(String hardwareId,
            List<SyncFile> syncFiles,
            int fromIndex,
            List<PendingFile> failedList,
            SyncCounters counters,
            String reason) {
        int remainingCount = syncFiles.size() - fromIndex;
        failRemainingFiles(syncFiles, fromIndex, failedList, reason);
        counters.failed = failedList.size();
        progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
        appNoticeService.showError(reason);

        if (log.isWarnEnabled()) {
            log.warn("[{}] Storage recovery aborted. Marked remaining {} files as failed without retry",
                    hardwareId,
                    remainingCount);
        }
    }

    // Parse remote file paths into SyncFile objects and categorize by sync status
    private SyncFileCollection collectSyncFiles(SyncContext syncContext,
            List<String> remoteFilePaths,
            Set<String> syncedPaths,
            LookupCache lookupCache,
            boolean autoDelete) {

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
                    I18n.get("device.sync.error.disconnected")));
            return null;
        } catch (IOException e) {
            failedList.add(new PendingFile(file.remotePath(), file.localPath(), file.relativeLocalPath(), file.info(),
                    I18n.get("device.sync.error.remote_size_unavailable")));
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
            String reason = dId == null ? I18n.get("device.sync.error.device_not_found")
                    : I18n.get("device.sync.error.user_not_found");
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
            throw new PreflightException(I18n.get("device.sync.error.disconnected"));
        }
    }

    // Retry failed file transfers up to MAX_RETRY times, updating counters directly
    private void retryFailed(String hardwareId, SyncContext syncContext, SyncPreparation prep,
            List<PendingFile> failedList,
            SyncCounters counters) {
        // If device disconnected during initial sync, mark all remaining failed with
        // single notice
        if (isDeviceDead(hardwareId)) {
            counters.failed += failedList.size();
            progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
            appNoticeService.showError(I18n.get("device.sync.error.disconnected"));
            logAndCleanupFailedFiles(failedList);
            return;
        }

        List<PendingFile> failedFilesRemaining = new ArrayList<>();

        // Retry each file N times before moving to next file
        for (PendingFile pf : failedList) {
            if (shutdownRequested || Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId)) {
                // Device disconnected mid-retry, mark all remaining failed and show single
                // notice
                for (int i = failedList.indexOf(pf); i < failedList.size(); i++) {
                    failedFilesRemaining.add(failedList.get(i));
                }
                counters.failed += failedFilesRemaining.size();
                progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
                appNoticeService.showError(I18n.get("device.sync.error.disconnected"));
                logAndCleanupFailedFiles(failedFilesRemaining);
                return;
            }

            boolean success = retryFileWithAttempts(hardwareId, pf, prep, syncContext, counters);
            if (success) {
                counters.passed++;
            } else {
                failedFilesRemaining.add(pf);
                counters.failed += 1;
                showErrorNotification(pf);
            }

            // Update progress after each file completes (success or exhausted retries)
            progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
        }

        logAndCleanupFailedFiles(failedFilesRemaining);
    }

    // Attempt to retry a single file up to MAX_RETRY times
    private boolean retryFileWithAttempts(String hardwareId, PendingFile pf, SyncPreparation prep,
            SyncContext syncContext, SyncCounters counters) {
        for (int attempt = 1; attempt <= AppConstants.MAX_RETRY; attempt++) {
            if (shutdownRequested || Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId)) {
                return false;
            }

            if (retryOneFile(hardwareId, pf, prep, syncContext, counters)) {
                return true;
            }

            if (attempt < AppConstants.MAX_RETRY && log.isInfoEnabled()) {
                log.info("Retry attempt {}/{} failed for: {}", attempt, AppConstants.MAX_RETRY,
                        name(pf.remotePath()));
            }
        }
        return false;
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
        String reason = pf.failureReason() != null ? pf.failureReason() : I18n.get("device.sync.error.unknown");
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
        String logicalPath = new File(dir, parts[parts.length - 1]).getAbsolutePath();
        return FilePathHasher.toPhysicalPath(logicalPath);
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
            return PullResult.failure(I18n.get("device.sync.error.exception"));
        }
    }

    private PullResult pullAndVerifyInternal(String hardwareId, String remotePath, String localPath,
            long expectedSize) {
        File f = new File(localPath);

        if (!deleteExistingFile(localPath)) {
            return PullResult.failure(I18n.get("device.sync.error.permission_denied"));
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
            return PullResult.failure(I18n.get("device.sync.error.file_not_created"));
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
            return PullResult.failure(I18n.get("device.sync.error.size_mismatch"));
        }

        return PullResult.success();
    }

    private String getExternalStorage(String hardwareId) {
        try {
            return adbClient.getExternalStorage(hardwareId);
        } catch (DeviceDisconnectedException e) {
            disconnectedDevices.add(hardwareId);
            throw new PreflightException(I18n.get("device.sync.error.disconnected"));
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
            return PullResult.failure(I18n.get("device.sync.error.disconnected"));
        }

        if (remotePath.startsWith(MassStorageFileSource.MASS_STORAGE_PREFIX)) {
            String driveLetter = driveLetterCache.get(hardwareId);
            if (driveLetter == null) {
                return PullResult.failure(I18n.get("device.sync.error.transfer"));
            }
            boolean success = massStorageFileSource.copyFile(driveLetter, remotePath, localPath);
            return success ? PullResult.success() : PullResult.failure(I18n.get("device.sync.error.transfer"));
        }
        try {
            boolean success = adbClient.pullFile(hardwareId, remotePath, localPath);
            return success ? PullResult.success() : PullResult.failure(I18n.get("device.sync.error.adb_pull_failed"));
        } catch (DeviceDisconnectedException e) {
            disconnectedDevices.add(hardwareId);
            return PullResult.failure(I18n.get("device.sync.error.disconnected"));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith(AdbClient.ERR_DEVICE_NOT_FOUND)) {
                return PullResult.failure(I18n.get("device.sync.error.bad_connection"));
            }
            return PullResult.failure(I18n.get("device.sync.error.adb_pull_failed"));
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
        if (event != null && event.getTarget() == FolderType.SAVE) {
            saveRecoveryDeferred = true;
            if (log.isDebugEnabled()) {
                log.debug("Storage recovery was deferred by user for target={}", event.getTarget());
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
