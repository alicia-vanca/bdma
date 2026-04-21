package com.app.common.modules.datasync.workers;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.app.common.definitions.AppConstants;
import com.app.common.dtos.FileInfo;
import com.app.common.dtos.SyncContext;
import com.app.common.modules.datasync.events.DeviceSyncCompletedEvent;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.datasync.services.DataSyncService;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.services.AdbClient;
import com.app.common.services.AppConfigService;
import com.app.common.services.AppNoticeService;
import com.app.common.services.SyncProgressTracker;

@Component
public class DataSyncWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DataSyncWorker.class);

    private static final String INTERNAL_ROOT = System.getProperty("app.internal.root", "/storage/emulated/0/DCIM");
    private static final String EXTERNAL_SUFFIX = "/Android/data/com.bodycamera.nettysocket/cache";

    private final DeviceSyncQueue queue;
    private final DataSyncService service;
    private final FolderManagerService folderManagerService;
    private final AppConfigService appConfigService;
    private final AdbClient adbClient;
    private final SyncProgressTracker progressTracker;
    private final ApplicationEventPublisher publisher;
    private final AppNoticeService appNoticeService;

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

    private record RetryStats(int passedDelta, int failedDelta) {
    }

    // Track progress during sync operations
    private record Progress(int succeeded, int failed) {
        Progress increment(boolean success) {
            return success ? new Progress(succeeded + 1, failed) : new Progress(succeeded, failed + 1);
        }
    }

    private enum ProcessResult {
        SUCCESS,
        FAILED
    }

    private final class LookupCache {
        private final Map<String, Long> deviceIds = new HashMap<>();
        private final Map<String, Long> userIds = new HashMap<>();

        private Long deviceId(String deviceName) {
            return deviceIds.computeIfAbsent(deviceName, service::resolveDeviceId);
        }

        private Long userId(String userName) {
            return userIds.computeIfAbsent(userName, service::resolveUserId);
        }
    }

    public DataSyncWorker(DeviceSyncQueue queue,
            DataSyncService service,
            FolderManagerService folderManagerService,
            AppConfigService appConfigService,
            AdbClient adbClient,
            SyncProgressTracker progressTracker,
            ApplicationEventPublisher publisher,
            AppNoticeService appNoticeService) {
        this.queue = queue;
        this.service = service;
        this.folderManagerService = folderManagerService;
        this.appConfigService = appConfigService;
        this.adbClient = adbClient;
        this.progressTracker = progressTracker;
        this.publisher = publisher;
        this.appNoticeService = appNoticeService;
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
        SyncContext ctx = entry.context();
        boolean syncStarted = false;
        long startedAt = System.currentTimeMillis();

        try {
            Long deviceId = service.validateDevice(hardwareId);
            Long userId = service.resolveUserId(ctx.username());
            if (deviceId == null || userId == null)
                return;

            syncStarted = true;

            String isAutoDeleteStr = appConfigService.getConfigValue(AppConstants.KEY_IS_AUTO_DELETE_AFTER_SYNC);
            boolean autoDelete = "true".equalsIgnoreCase(isAutoDeleteStr);

            SyncPreparation prep = prepareSyncFiles(hardwareId, deviceId, ctx, autoDelete);

            int total = prep.fileCollection().allSyncFiles().size();
            SyncCounters counters = new SyncCounters(
                    autoDelete ? 0 : prep.fileCollection().alreadySyncedCount(),
                    0);

            List<SyncFile> syncFiles = autoDelete ? prep.fileCollection().allSyncFiles()
                    : prep.fileCollection().unsyncedFiles();

            progressTracker.markSyncing(hardwareId, total, counters.passed, counters.failed);

            List<PendingFile> failedList = new ArrayList<>();
            SyncProcessContext processCtx = new SyncProcessContext(hardwareId, prep.syncedPaths(), failedList,
                    autoDelete, prep.lookupCache(), total, counters);
            processSyncFiles(syncFiles, processCtx);

            RetryStats retryStats = retryFailed(hardwareId, failedList, prep.syncedPaths(), autoDelete,
                    prep.lookupCache(), total,
                    counters.passed);
            counters.passed += retryStats.passedDelta();
            counters.failed += retryStats.failedDelta();

            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.info("Finished sync for {}: total={}, passed={}, failed={}, elapsedMs={}",
                    hardwareId, total, counters.passed, counters.failed, elapsedMs);
        } finally {
            queue.done(hardwareId);
            if (syncStarted) {
                publisher.publishEvent(new DeviceSyncCompletedEvent(hardwareId));
            }
        }
    }

    private record SyncPreparation(SyncFileCollection fileCollection, Set<String> syncedPaths,
            LookupCache lookupCache) {
    }

    private static class SyncCounters {
        int passed;
        int failed;

        SyncCounters(int passed, int failed) {
            this.passed = passed;
            this.failed = failed;
        }
    }

    private record SyncProcessContext(String hardwareId, Set<String> syncedPaths, List<PendingFile> failedList,
            boolean autoDelete, LookupCache lookupCache, int total, SyncCounters counters) {
    }

    // Discover and prepare files for sync from device
    private SyncPreparation prepareSyncFiles(String hardwareId, Long deviceId, SyncContext ctx, boolean autoDelete) {
        long t1 = System.currentTimeMillis();
        Set<String> syncedPaths = service.loadSyncedPaths(deviceId);
        log.info("loadSyncedPaths took {}ms", System.currentTimeMillis() - t1);

        long t2 = System.currentTimeMillis();
        List<String> remoteFilePaths = new ArrayList<>(findFiles(hardwareId, INTERNAL_ROOT));
        log.info("findFiles(INTERNAL) took {}ms, found {} files", System.currentTimeMillis() - t2,
                remoteFilePaths.size());

        long t3 = System.currentTimeMillis();
        String ext = getExternalStorage(hardwareId);
        log.info("getExternalStorage took {}ms", System.currentTimeMillis() - t3);

        if (ext != null) {
            long t4 = System.currentTimeMillis();
            remoteFilePaths.addAll(findFiles(hardwareId, ext + EXTERNAL_SUFFIX));
            log.info("findFiles(EXTERNAL) took {}ms, found {} files", System.currentTimeMillis() - t4,
                    remoteFilePaths.size());
        }

        long t5 = System.currentTimeMillis();
        LookupCache lookupCache = new LookupCache();
        SyncFileCollection fileCollection = collectSyncFiles(hardwareId, ctx, remoteFilePaths, syncedPaths,
                lookupCache, autoDelete);
        log.info("collectSyncFiles took {}ms", System.currentTimeMillis() - t5);

        return new SyncPreparation(fileCollection, syncedPaths, lookupCache);
    }

    // Process each file in the sync list
    private void processSyncFiles(List<SyncFile> syncFiles, SyncProcessContext ctx) {
        Progress progress = new Progress(0, 0);
        for (SyncFile file : syncFiles) {
            if (Thread.currentThread().isInterrupted() || isDeviceDead(ctx.hardwareId()))
                break;

            int current = ctx.counters().passed + ctx.counters().failed + 1;
            if (log.isInfoEnabled()) {
                log.info("Processing file ({}/{}): {}", current, ctx.total(), name(file.remotePath()));
            }
            ProcessResult result = processFile(ctx.hardwareId(), file, ctx.syncedPaths(), ctx.failedList(),
                    ctx.autoDelete(), ctx.lookupCache(), progress);

            if (result == ProcessResult.FAILED) {
                progress = progress.increment(false);
            } else {
                ctx.counters().passed++;
                progress = progress.increment(true);
            }

            if (result == ProcessResult.SUCCESS) {
                progressTracker.markSyncing(ctx.hardwareId(), ctx.total(), ctx.counters().passed, 0);
            }
        }
    }

    private record SyncFileCollection(List<SyncFile> allSyncFiles, List<SyncFile> unsyncedFiles,
            int alreadySyncedCount) {
    }

    // Parse remote file paths into SyncFile objects and categorize by sync status
    private SyncFileCollection collectSyncFiles(String hardwareId,
            SyncContext ctx,
            List<String> remoteFilePaths,
            Set<String> syncedPaths,
            LookupCache lookupCache,
            boolean autoDelete) {

        List<SyncFile> allSyncFiles = new ArrayList<>();
        List<SyncFile> unsyncedFiles = new ArrayList<>();
        int alreadySyncedCount = 0;

        for (String path : remoteFilePaths) {
            SyncFile syncFile = buildSyncFile(path, ctx, lookupCache);
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
        FileInfo updatedInfo = new FileInfo(file.info().deviceName(), file.info().userName(),
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
    private SyncFile buildSyncFile(String path, SyncContext ctx, LookupCache lookupCache) {
        String type = extractType(path);
        FileInfo info = FileInfo.parse(name(path), 0, type);

        if (info == null || !ctx.canSync(info.userName())) {
            return null;
        }

        String localPath = resolveLocalPath(info, path);
        if (localPath == null
                || lookupCache.deviceId(info.deviceName()) == null
                || lookupCache.userId(info.userName()) == null) {
            return null;
        }

        return new SyncFile(path, localPath, info);
    }

    private ProcessResult processFile(String hardwareId,
            SyncFile file,
            Set<String> syncedPaths,
            List<PendingFile> failed,
            boolean autoDelete,
            LookupCache lookupCache,
            Progress progress) {

        // If file already synced, handle based on auto-delete setting
        if (syncedPaths.contains(file.localPath())) {
            if (autoDelete) {
                deleteRemoteFile(hardwareId, file.remotePath());
            }
            return ProcessResult.SUCCESS;
        }

        Long dId = lookupCache.deviceId(file.info().deviceName());
        Long uId = lookupCache.userId(file.info().userName());
        if (dId == null || uId == null) {
            String reason = dId == null ? I18n.get("device.sync.error.device_not_found")
                    : I18n.get("device.sync.error.user_not_found");
            failed.add(new PendingFile(file.remotePath(), file.localPath(), file.info(), reason));
            return ProcessResult.FAILED;
        }

        PullResult result = pullAndVerify(hardwareId, file.remotePath(), file.localPath(), file.info().size());
        if (result.isSuccess()) {
            service.saveFile(uId, dId, name(file.remotePath()), file.localPath(), file.info());
            syncedPaths.add(file.localPath());
            logSyncedFile(file.localPath(), progress.succeeded(), progress.failed());
            if (autoDelete) {
                deleteRemoteFile(hardwareId, file.remotePath());
            }
            return ProcessResult.SUCCESS;
        }

        failed.add(new PendingFile(file.remotePath(), file.localPath(), file.info(), result.failureReason()));
        return ProcessResult.FAILED;
    }

    private List<String> findFiles(String hardwareId, String root) {
        return adbClient.findFiles(hardwareId, root, AppConstants.MEDIA_TYPES);
    }

    // Retry failed file transfers up to MAX_RETRY times, returning recovery stats
    private RetryStats retryFailed(String hardwareId,
            List<PendingFile> failed,
            Set<String> syncedPaths,
            boolean autoDelete,
            LookupCache lookupCache,
            int total,
            int baseCount) {
        List<PendingFile> remaining = new ArrayList<>();
        int recovered = 0;

        // Retry each file N times before moving to next file
        for (PendingFile pf : failed) {
            if (Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId)) {
                remaining.add(pf);
                continue;
            }

            boolean success = retryFileWithAttempts(hardwareId, pf, syncedPaths, autoDelete, lookupCache, total,
                    baseCount + recovered);
            if (success) {
                recovered++;
            } else {
                remaining.add(pf);
                showErrorNotification(pf);
            }

            // Update progress after each file completes (success or exhausted retries)
            int currentPassed = baseCount + recovered;
            int currentFailed = remaining.size();
            progressTracker.markSyncing(hardwareId, total, currentPassed, currentFailed);
        }

        saveFailedRemaining(remaining, lookupCache);
        return new RetryStats(recovered, remaining.size());
    }

    // Attempt to retry a single file up to MAX_RETRY times
    private boolean retryFileWithAttempts(String hardwareId, PendingFile pf, Set<String> syncedPaths,
            boolean autoDelete, LookupCache lookupCache, int total, int current) {
        for (int attempt = 1; attempt <= AppConstants.MAX_RETRY; attempt++) {
            if (Thread.currentThread().isInterrupted() || isDeviceDead(hardwareId)) {
                return false;
            }

            int[] progress = { current + 1, total };
            if (retryOneFile(hardwareId, pf, syncedPaths, autoDelete, lookupCache, progress)) {
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
    private boolean retryOneFile(String hardwareId,
            PendingFile pf,
            Set<String> syncedPaths,
            boolean autoDelete,
            LookupCache lookupCache,
            int[] progress) {

        Long dId = lookupCache.deviceId(pf.info().deviceName());
        Long uId = lookupCache.userId(pf.info().userName());

        if (dId == null || uId == null) {
            return false;
        }

        PullResult result = pullAndVerify(hardwareId, pf.remotePath(), pf.localPath(), pf.info().size());
        if (!result.isSuccess()) {
            return false;
        }

        service.saveFile(uId, dId, name(pf.remotePath()), pf.localPath(), pf.info());
        syncedPaths.add(pf.localPath());
        logSyncedFile(pf.localPath(), progress[0], progress[1]);
        if (autoDelete) {
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

    // Save permanently failed files to database for manual review
    private void saveFailedRemaining(List<PendingFile> remaining, LookupCache lookupCache) {
        for (PendingFile pf : remaining) {
            Long dId = lookupCache.deviceId(pf.info().deviceName());
            Long uId = lookupCache.userId(pf.info().userName());
            if (dId != null) {
                if (log.isWarnEnabled()) {
                    String reason = pf.failureReason() != null ? pf.failureReason() : "Unknown error";
                    log.warn("File failed after retries: {} - Reason: {}", name(pf.remotePath()), reason);
                }
                service.saveFailed(uId, dId, pf.localPath(), pf.info());
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

    // Build local file path from FileInfo and remote path structure
    private String resolveLocalPath(FileInfo info, String remotePath) {
        String[] parts = remotePath.split("/");
        if (parts.length < 3)
            return null;

        File base = folderManagerService.getDataDir();
        if (base == null)
            return null;

        File dir = new File(new File(base, info.userName()), parts[parts.length - 3]);
        return new File(dir, parts[parts.length - 1]).getAbsolutePath();
    }

    // Extract folder type from remote path (third-to-last path component)
    private String extractType(String remotePath) {
        String[] parts = remotePath.split("/");
        return parts.length >= 3 ? parts[parts.length - 3] : null;
    }

    // Pull file from device and verify size matches expected
    private PullResult pullAndVerify(String hardwareId, String remote, String local, long remoteSize) {
        try {
            return folderManagerService
                    .withDataDirUnlocked(() -> pullAndVerifyInternal(hardwareId, remote, local, remoteSize));
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

        if (!pullFile(hardwareId, remote, local)) {
            if (log.isWarnEnabled()) {
                log.warn("ADB pull failed for: {} -> {}", name(remote), local);
            }
            return PullResult.failure(I18n.get("device.sync.error.adb_pull_failed"));
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
        try {
            return adbClient.getRemoteSize(hardwareId, remote);
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean pullFile(String hardwareId, String remote, String local) {
        // Add timeout to avoid hanging on slow devices
        try {
            return adbClient.pullFile(hardwareId, remote, local);
        } catch (Exception e) {
            log.warn("Pull file timeout or error for {} -> {}", remote, local, e);
            return false;
        }
    }

    private void deleteRemoteFile(String hardwareId, String remote) {
        adbClient.deleteRemoteFile(hardwareId, remote);
    }

    private boolean isDeviceDead(String hardwareId) {
        return !adbClient.isDeviceAlive(hardwareId);
    }

    private String name(String path) {
        return path.substring(path.lastIndexOf("/") + 1);
    }
}
