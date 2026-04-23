package com.app.common.modules.datasync.workers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
import com.app.common.dtos.DeviceEvent;
import com.app.common.dtos.FileInfo;
import com.app.common.dtos.SyncContext;
import com.app.common.modules.datasync.events.DeviceSyncCompletedEvent;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.datasync.services.DataSyncService;
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

    private final DeviceSyncQueue queue;
    private final DataSyncService service;
    private final FolderManagerService folderManagerService;
    private final AdbClient adbClient;
    private final SyncProgressTracker progressTracker;
    private final ApplicationEventPublisher publisher;
    private final AppNoticeService appNoticeService;
    private final DriveLetterMapper driveLetterMapper;
    private final MassStorageFileSource massStorageFileSource;

    private final Map<String, String> driveLetterCache = new ConcurrentHashMap<>();
    private final Set<String> disconnectedDevices = ConcurrentHashMap.newKeySet();

    // Records and inner classes
    private record SyncFile(String remotePath, String localPath, FileInfo info) {
    }

    private record PendingFile(String remotePath, String localPath, FileInfo info, String failureReason) {
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
            return deviceIds.computeIfAbsent(cameraId, service::resolveDeviceId);
        }

        private Long userId(String userName) {
            return userIds.computeIfAbsent(userName, service::resolveUserId);
        }
    }

    private enum ProcessResult {
        SUCCESS,
        FAILED
    }

    public DataSyncWorker(DeviceSyncQueue queue,
            DataSyncService service,
            FolderManagerService folderManagerService,
            AdbClient adbClient,
            SyncProgressTracker progressTracker,
            ApplicationEventPublisher publisher,
            AppNoticeService appNoticeService,
            DriveLetterMapper driveLetterMapper,
            MassStorageFileSource massStorageFileSource) {
        this.queue = queue;
        this.service = service;
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
        while (!Thread.currentThread().isInterrupted()) {
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
    }

    private void processDevice(DeviceSyncQueue.Entry entry) {
        String hardwareId = entry.hardwareId();
        SyncContext syncContext = entry.context();
        boolean syncStarted = false;
        long startedAt = System.currentTimeMillis();

        try {
            Long deviceId = service.validateDevice(hardwareId);
            Long userId = service.resolveUserId(syncContext.username());
            if (deviceId == null || userId == null)
                return;

            syncStarted = true;

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
            processSyncFiles(hardwareId, syncContext, preparedSyncFiles, filesToBeProcessed, failedList, counters);

            retryFailed(hardwareId, syncContext, preparedSyncFiles, failedList, counters);

            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.info("Finished sync for {}: total={}, passed={}, failed={}, elapsedMs={}",
                    hardwareId, counters.total, counters.passed, counters.failed, elapsedMs);
        } finally {
            disconnectedDevices.remove(hardwareId);
            driveLetterCache.remove(hardwareId);
            queue.done(hardwareId);
            if (syncStarted) {
                publisher.publishEvent(new DeviceSyncCompletedEvent(hardwareId));
            }
        }
    }

    // Discover and prepare files for sync from device
    private SyncPreparation prepareSyncFiles(String hardwareId, Long deviceId, SyncContext syncContext,
            boolean autoDelete) {
        Set<String> syncedPaths = service.loadSyncedPaths(deviceId);

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
        SyncFileCollection fileCollection = collectSyncFiles(hardwareId, syncContext, remoteFilePaths, syncedPaths,
                lookupCache, autoDelete);

        return new SyncPreparation(fileCollection, syncedPaths, lookupCache);
    }

    private List<String> findFilesFromMassStorage(String hardwareId) {
        String driveLetter = driveLetterMapper.resolve(hardwareId);
        if (driveLetter == null) {
            log.warn("[{}] SD card not accessible via ADB and no drive letter found", hardwareId);
            return List.of();
        }
        driveLetterCache.put(hardwareId, driveLetter);
        log.info("[{}] MassStorage fallback: using drive {}", hardwareId, driveLetter);
        return massStorageFileSource.findFiles(driveLetter, AppConstants.MEDIA_TYPES);
    }

    // Process each file in the sync list
    private void processSyncFiles(String hardwareId, SyncContext syncContext, SyncPreparation prep,
            List<SyncFile> syncFiles,
            List<PendingFile> failedList, SyncCounters counters) {

        for (SyncFile file : syncFiles) {

            if (Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId)) {
                if (log.isWarnEnabled()) {
                    log.warn("Device disconnected. Break sync file loop");
                }
                break;
            }

            int current = counters.passed + counters.failed + 1;
            if (log.isInfoEnabled()) {
                log.info("Processing file ({}/{}): {}", current, counters.total, name(file.remotePath()));
            }
            ProcessResult result = processFile(hardwareId, syncContext, prep, file, failedList, counters);

            if (result != ProcessResult.FAILED) {
                counters.passed++;
            }

            if (result == ProcessResult.SUCCESS) {
                progressTracker.markSyncing(hardwareId, counters.total, counters.passed, counters.failed);
            }
        }

    }

    // Parse remote file paths into SyncFile objects and categorize by sync status
    private SyncFileCollection collectSyncFiles(String hardwareId,
            SyncContext syncContext,
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
                if (!syncedPaths.contains(syncFile.localPath())) {
                    unsyncedFiles.add(syncFile);
                } else {
                    alreadySyncedCount++;
                }
            }
        }

        retrieveAndUpdateFileSizes(hardwareId, unsyncedFiles, allSyncFiles, autoDelete);
        sortSyncFiles(allSyncFiles, unsyncedFiles, autoDelete);

        return new SyncFileCollection(allSyncFiles, unsyncedFiles, alreadySyncedCount);
    }

    // Retrieve remote file sizes and update both unsynced and all files lists
    private void retrieveAndUpdateFileSizes(String hardwareId, List<SyncFile> unsyncedFiles,
            List<SyncFile> allSyncFiles, boolean autoDelete) {
        for (int i = 0; i < unsyncedFiles.size(); i++) {
            SyncFile file = unsyncedFiles.get(i);
            long size = getRemoteSizeQuiet(hardwareId, file.remotePath());
            SyncFile updatedFile = createFileWithSize(file, size);
            unsyncedFiles.set(i, updatedFile);

            if (autoDelete) {
                updateFileInList(allSyncFiles, file.remotePath(), updatedFile);
            }
        }
    }

    // Create a new SyncFile with updated size information
    private SyncFile createFileWithSize(SyncFile file, long size) {
        FileInfo updatedInfo = new FileInfo(file.info().cameraId(), file.info().username(),
                file.info().createDate(), size, file.info().type());
        return new SyncFile(file.remotePath(), file.localPath(), updatedInfo);
    }

    // Update a file in the list by matching remote path
    private void updateFileInList(List<SyncFile> list, String remotePath, SyncFile updatedFile) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).remotePath().equals(remotePath)) {
                list.set(i, updatedFile);
                break;
            }
        }
    }

    // Sort files by size, choosing the appropriate list based on autoDelete mode
    private void sortSyncFiles(List<SyncFile> allSyncFiles, List<SyncFile> unsyncedFiles, boolean autoDelete) {
        List<SyncFile> listToSort = autoDelete ? allSyncFiles : unsyncedFiles;
        listToSort.sort((a, b) -> Long.compare(a.info().size(), b.info().size()));
    }

    // Build SyncFile from remote path, validating user permissions and database
    // references. Size is retrieved later only for unsynced files.
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

        return new SyncFile(path, localPath, info);
    }

    private ProcessResult processFile(String hardwareId, SyncContext syncContext, SyncPreparation prep, SyncFile file,
            List<PendingFile> failed, SyncCounters counters) {

        Set<String> syncedPaths = prep.syncedPaths();
        LookupCache lookupCache = prep.lookupCache();

        // If file already synced, handle based on auto-delete setting
        if (syncedPaths.contains(file.localPath())) {
            if (syncContext.autoDelete()) {
                deleteRemoteFile(hardwareId, file.remotePath());
            }
            return ProcessResult.SUCCESS;
        }

        Long dId = lookupCache.deviceId(file.info().cameraId());
        Long uId = lookupCache.userId(file.info().username());
        if (dId == null || uId == null) {
            String reason = dId == null ? I18n.get("device.sync.error.device_not_found")
                    : I18n.get("device.sync.error.user_not_found");
            failed.add(new PendingFile(file.remotePath(), file.localPath(), file.info(), reason));
            return ProcessResult.FAILED;
        }

        PullResult result = pullAndVerify(hardwareId, file.remotePath(), file.localPath(), file.info().size(),
                syncContext.saveDir());
        if (result.isSuccess()) {
            service.saveFile(uId, dId, name(file.remotePath()), file.localPath(), file.info());
            syncedPaths.add(file.localPath());
            logSyncedFile(file.localPath(), counters.passed + 1, counters.total);
            if (syncContext.autoDelete()) {
                deleteRemoteFile(hardwareId, file.remotePath());
            }
            return ProcessResult.SUCCESS;
        }

        cleanupIncompleteFile(file.localPath());
        failed.add(new PendingFile(file.remotePath(), file.localPath(), file.info(), result.failureReason()));
        return ProcessResult.FAILED;
    }

    private List<String> findFiles(String hardwareId, String root) {
        return adbClient.findFiles(hardwareId, root, AppConstants.MEDIA_TYPES);
    }

    // Retry failed file transfers up to MAX_RETRY times, updating counters directly
    private void retryFailed(String hardwareId, SyncContext syncContext, SyncPreparation prep,
            List<PendingFile> failedList,
            SyncCounters counters) {
        List<PendingFile> failedFilesRemaining = new ArrayList<>();

        // Retry each file N times before moving to next file
        for (PendingFile pf : failedList) {
            if (Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId)) {
                // Device disconnected mid-retry, skip remaining files
                failedFilesRemaining.add(pf);
                counters.failed += 1;
                continue;
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
            if (Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId)) {
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

        PullResult result = pullAndVerify(hardwareId, pf.remotePath(), pf.localPath(), pf.info().size(),
                syncContext.saveDir());
        if (!result.isSuccess()) {
            return false;
        }

        service.saveFile(uId, dId, name(pf.remotePath()), pf.localPath(), pf.info());
        prep.syncedPaths().add(pf.localPath());
        logSyncedFile(pf.localPath(), counters.passed + 1, counters.total);
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
        try {
            File file = new File(localPath);
            if (file.exists()) {
                Files.delete(file.toPath());
                if (log.isDebugEnabled()) {
                    log.debug("Cleaned up failed file: {}", localPath);
                }
            }
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("Failed to delete incomplete file: {} - {}", localPath, e.getMessage());
            }
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
    // saveDir
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

    // Pull file from device and verify size matches expected
    private PullResult pullAndVerify(String hardwareId, String remote, String local, long remoteSize, File saveDir) {
        try {
            return folderManagerService
                    .withSpecificDirUnlocked(saveDir,
                            () -> pullAndVerifyInternal(hardwareId, remote, local, remoteSize));
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Exception during pullAndVerify for {} -> {}: {}", name(remote), local, e.getMessage(), e);
            }
            return PullResult.failure(I18n.get("device.sync.error.exception"));
        }
    }

    private PullResult pullAndVerifyInternal(String hardwareId, String remote, String local, long remoteSize) {
        File f = new File(local);
        File parent = f.getParentFile();

        if (!ensureParentDirectory(parent)) {
            return PullResult.failure(I18n.get("device.sync.error.directory_creation_failed"));
        }

        if (!deleteExistingFile(f, local)) {
            return PullResult.failure(I18n.get("device.sync.error.permission_denied"));
        }

        PullResult pullResult = pullFile(hardwareId, remote, local);
        if (!pullResult.isSuccess()) {
            if (log.isWarnEnabled()) {
                log.warn("ADB pull failed for: {} -> {} - Reason: {}", name(remote), local, pullResult.failureReason());
            }
            return pullResult;
        }

        if (!f.exists()) {
            if (log.isWarnEnabled()) {
                log.warn("File not created after successful pull: {} (parent exists: {}, parent writable: {})",
                        local, parent != null && parent.exists(), parent != null && parent.canWrite());
            }
            return PullResult.failure(I18n.get("device.sync.error.file_not_created"));
        }

        return verifyFileSize(f, remoteSize, remote);
    }

    private boolean ensureParentDirectory(File parent) {
        if (parent != null && !parent.exists()) {
            boolean created = parent.mkdirs();
            if (!created && !parent.exists()) {
                if (log.isWarnEnabled()) {
                    log.warn("Failed to create parent directory: {}", parent.getAbsolutePath());
                }
                return false;
            }
        }
        return true;
    }

    private boolean deleteExistingFile(File file, String path) {
        if (file.exists()) {
            try {
                java.nio.file.Files.delete(file.toPath());
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

        if (actualSize == 0) {
            if (log.isWarnEnabled()) {
                log.warn("File has zero bytes: {}", name(remotePath));
            }
            return PullResult.failure(I18n.get("device.sync.error.zero_bytes"));
        }

        if (expectedSize >= 0 && actualSize != expectedSize) {
            if (log.isWarnEnabled()) {
                log.warn("File size verification failed: {} (expected: {} bytes, actual: {} bytes)",
                        name(remotePath), expectedSize, actualSize);
            }
            return PullResult.failure(I18n.get("device.sync.error.size_mismatch"));
        }

        return PullResult.success();
    }

    private String getExternalStorage(String hardwareId) {
        return adbClient.getExternalStorage(hardwareId);
    }

    // Get remote file size without throwing exceptions, returns 0 on error
    private long getRemoteSizeQuiet(String hardwareId, String remote) {
        if (remote.startsWith(MassStorageFileSource.MASS_STORAGE_PREFIX)) {
            String driveLetter = driveLetterCache.get(hardwareId);
            if (driveLetter == null)
                return -1;
            return massStorageFileSource.getFileSize(driveLetter, remote);
        }
        try {
            return adbClient.getRemoteSize(hardwareId, remote);
        } catch (Exception e) {
            return 0;
        }
    }

    private PullResult pullFile(String hardwareId, String remote, String local) {
        // Fail fast if device disconnected to avoid unnecessary ADB operations
        if (isDeviceDead(hardwareId)) {
            return PullResult.failure("Device disconnected");
        }

        if (remote.startsWith(MassStorageFileSource.MASS_STORAGE_PREFIX)) {
            String driveLetter = driveLetterCache.get(hardwareId);
            if (driveLetter == null) {
                return PullResult.failure("No drive letter cached for mass storage");
            }
            boolean success = massStorageFileSource.copyFile(driveLetter, remote, local);
            return success ? PullResult.success() : PullResult.failure("Mass storage copy failed");
        }
        try {
            boolean success = adbClient.pullFile(hardwareId, remote, local);
            return success ? PullResult.success() : PullResult.failure("ADB pull command returned false");
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return PullResult.failure(errorMsg);
        }
    }

    private void deleteRemoteFile(String hardwareId, String remote) {
        if (remote.startsWith(MassStorageFileSource.MASS_STORAGE_PREFIX)) {
            log.debug("[{}] Skipping delete for mass storage file: {}", hardwareId, remote);
            return;
        }
        adbClient.deleteRemoteFile(hardwareId, remote);
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

    // Check if device disconnected during sync using event-driven flag
    private boolean isDeviceDead(String hardwareId) {
        return disconnectedDevices.contains(hardwareId);
    }

    private String name(String path) {
        return path.substring(path.lastIndexOf("/") + 1);
    }
}
