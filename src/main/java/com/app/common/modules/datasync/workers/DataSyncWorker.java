package com.app.common.modules.datasync.workers;

import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.definitions.AppConstants;
import com.app.common.services.AdbClient;
import com.app.common.services.AppConfigService;
import com.app.common.dtos.FileInfo;
import com.app.common.dtos.SyncContext;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.datasync.services.DataSyncService;
import com.app.common.services.SyncProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Component
public class DataSyncWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DataSyncWorker.class);

    private static final String INTERNAL_ROOT = System.getProperty("app.internal.root", "/storage/emulated/0/DCIM");
    private static final String EXTERNAL_SUFFIX = "/Android/data/com.bodycamera.nettysocket/cache";

    private static final List<String> TYPES = List.of("audio", "image", "video", "IMP", "SOS");

    private static final int MAX_RETRY = 3;
    private static final long LARGE_FILE_THRESHOLD = 1024L * 1024 * 1024;

    private final DeviceSyncQueue queue;
    private final DataSyncService service;
    private final FolderManagerService folderManagerService;
    private final AppConfigService appConfigService;
    private final AdbClient adbClient;
    private final SyncProgressTracker progressTracker;

    private record LocalFile(String path, String type) {
    }

    private record SyncFile(String remotePath, LocalFile local, FileInfo info) {
    }

    private record PendingFile(String remotePath, String localPath, long size, FileInfo info, String type) {
    }

    private record RetryStats(int passedDelta, int failedDelta) {
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
                          SyncProgressTracker progressTracker) {
        this.queue = queue;
        this.service = service;
        this.folderManagerService = folderManagerService;
        this.appConfigService = appConfigService;
        this.adbClient = adbClient;
        this.progressTracker = progressTracker;
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
        String serial = entry.serial();
        SyncContext ctx = entry.context();

        try {
            Long deviceId = service.validateDevice(serial);
            Long userId = service.resolveUserId(ctx.username());
            if (deviceId == null || userId == null)
                return;

            String isAutoDeleteStr = appConfigService.getConfigValue(AppConstants.KEY_IS_AUTO_DELETE_AFTER_SYNC);
            boolean autoDelete = "true".equalsIgnoreCase(isAutoDeleteStr);

            Set<String> synced = service.loadSyncedPaths(deviceId);

            List<String> allFiles = new ArrayList<>(findFiles(serial, INTERNAL_ROOT));
            String ext = getExternalStorage(serial);
            if (ext != null) {
                allFiles.addAll(findFiles(serial, ext + EXTERNAL_SUFFIX));
            }

            LookupCache lookupCache = new LookupCache();
            List<SyncFile> syncFiles = collectSyncFiles(ctx, allFiles, synced, lookupCache);

            int total = syncFiles.size();
            int[] passed = {0};
            int[] failed = {0};

            progressTracker.markSyncing(serial, total, 0, 0);

            List<PendingFile> failedList = new ArrayList<>();

            for (SyncFile file : syncFiles) {
                if (Thread.currentThread().isInterrupted() || !alive(serial)) break;

                ProcessResult result = processFile(serial, file, synced, failedList, autoDelete, lookupCache);

                if (result == ProcessResult.FAILED) {
                    failed[0]++;
                } else {
                    passed[0]++;
                }

                progressTracker.markSyncing(serial, total, passed[0], failed[0]);
            }

            RetryStats retryStats = retryFailed(serial, failedList, synced, autoDelete, lookupCache);
            if (retryStats.passedDelta() != 0 || retryStats.failedDelta() != 0) {
                passed[0] += retryStats.passedDelta();
                failed[0] += retryStats.failedDelta();
                progressTracker.markSyncing(serial, total, passed[0], failed[0]);
            }

        } finally {
            queue.done(serial);
        }
    }

    private List<SyncFile> collectSyncFiles(SyncContext ctx,
                                             List<String> allFiles,
                                             Set<String> synced,
                                             LookupCache lookupCache) {

        List<SyncFile> syncFiles = new ArrayList<>();

        for (String path : allFiles) {
            FileInfo info = FileInfo.parse(name(path));

            if (info != null
                    && ctx.canSync(info.userName())) {

                LocalFile local = resolveLocalPath(info, path);

                if (local != null
                        && !synced.contains(local.path())
                        && lookupCache.deviceId(info.deviceName()) != null
                        && lookupCache.userId(info.userName()) != null) {

                    syncFiles.add(new SyncFile(path, local, info));
                }
            }
        }

        return syncFiles;
    }

    private ProcessResult processFile(String serial,
                                      SyncFile file,
                                      Set<String> synced,
                                      List<PendingFile> failed,
                                      boolean autoDelete,
                                      LookupCache lookupCache) {
        Long dId = lookupCache.deviceId(file.info().deviceName());
        Long uId = lookupCache.userId(file.info().userName());
        if (dId == null || uId == null || synced.contains(file.local().path())) {
            return ProcessResult.FAILED;
        }

        long size = getRemoteSize(serial, file.remotePath());

        if (size > LARGE_FILE_THRESHOLD) {
            service.saveLargeFile(uId, dId,
                    name(file.remotePath()), file.local().path(),
                    size, file.info().createDate(), file.local().type());
            return ProcessResult.SUCCESS;
        }

        if (pullAndVerify(serial, file.remotePath(), file.local().path(), size)) {
            service.saveFile(uId, dId,
                    name(file.remotePath()), file.local().path(),
                    size, file.info().createDate(), file.local().type());
            synced.add(file.local().path());
            if (autoDelete) {
                deleteRemoteFile(serial, file.remotePath());
            }
            return ProcessResult.SUCCESS;
        }

        failed.add(new PendingFile(file.remotePath(), file.local().path(), size, file.info(), file.local().type()));
        return ProcessResult.FAILED;
    }

    private List<String> findFiles(String serial, String root) {
        return adbClient.findFiles(serial, root, TYPES);
    }

    private RetryStats retryFailed(String serial,
                                   List<PendingFile> failed,
                                   Set<String> synced,
                                   boolean autoDelete,
                                   LookupCache lookupCache) {
        List<PendingFile> remaining = new ArrayList<>(failed);
        int recovered = 0;

        for (int i = 1; i <= MAX_RETRY; i++) {
            if (remaining.isEmpty() || Thread.currentThread().isInterrupted() || !alive(serial)) {
                break;
            }

            List<PendingFile> next = new ArrayList<>();

            for (PendingFile pf : remaining) {
                if (Thread.currentThread().isInterrupted() || !alive(serial)) {
                    break;
                }

                if (!retryOneFile(serial, pf, synced, autoDelete, lookupCache)) {
                    next.add(pf);
                } else {
                    recovered++;
                }
            }

            remaining = next;
        }

        saveFailedRemaining(remaining, lookupCache);
        return new RetryStats(recovered, -recovered);
    }

    private boolean retryOneFile(String serial,
                                 PendingFile pf,
                                 Set<String> synced,
                                 boolean autoDelete,
                                 LookupCache lookupCache) {

        Long dId = lookupCache.deviceId(pf.info().deviceName());
        Long uId = lookupCache.userId(pf.info().userName());

        if (dId == null || uId == null) {
            return false;
        }

        if (!pullAndVerify(serial, pf.remotePath(), pf.localPath(), pf.size())) {
            return false;
        }

        service.saveFile(uId, dId,
                name(pf.remotePath()), pf.localPath(),
                pf.size(), pf.info().createDate(), pf.type());
        synced.add(pf.localPath());
        if (autoDelete) {
            deleteRemoteFile(serial, pf.remotePath());
        }

        return true;
    }

    private void saveFailedRemaining(List<PendingFile> remaining, LookupCache lookupCache) {
        for (PendingFile pf : remaining) {
            Long dId = lookupCache.deviceId(pf.info().deviceName());
            if (dId != null) {
                service.saveFailed(dId, pf.localPath());
            }
        }
    }

    private LocalFile resolveLocalPath(FileInfo info, String remotePath) {
        String[] parts = remotePath.split("/");
        if (parts.length < 3)
            return null;

        File base = folderManagerService.getDataDir();
        if (base == null)
            return null;

        File dir = new File(new File(base, info.userName()), parts[parts.length - 3]);
        return new LocalFile(new File(dir, parts[parts.length - 1]).getAbsolutePath(),
                parts[parts.length - 3]);
    }

    private boolean pullAndVerify(String serial, String remote, String local, long remoteSize) {
        try {
            return folderManagerService.withDataDirUnlocked(() -> {
                File f = new File(local);
                File parent = f.getParentFile();

                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                if (f.exists()) {
                    try {
                        java.nio.file.Files.delete(f.toPath());
                    } catch (IOException e) {
                        log.warn("Delete failed: {}", local, e);
                    }
                }

                return pullFile(serial, remote, local)
                        && verifyIntegrity(remoteSize, local);
            });
        } catch (Exception e) {
            return false;
        }
    }

    private boolean verifyIntegrity(long remoteSize, String local) {
        return remoteSize < 0 || remoteSize == new File(local).length();
    }

    private String getExternalStorage(String serial) {
        return adbClient.getExternalStorage(serial);
    }

    private long getRemoteSize(String serial, String remote) {
        return adbClient.getRemoteSize(serial, remote);
    }

    private boolean pullFile(String serial, String remote, String local) {
        // Add timeout to avoid hanging on slow devices
        try {
            return adbClient.pullFile(serial, remote, local);
        } catch (Exception e) {
            log.warn("Pull file timeout or error for {} -> {}", remote, local, e);
            return false;
        }
    }

    private boolean deleteRemoteFile(String serial, String remote) {
        return adbClient.deleteRemoteFile(serial, remote);
    }

    private boolean alive(String serial) {
        return adbClient.isDeviceAlive(serial);
    }

    private String name(String path) {
        return path.substring(path.lastIndexOf("/") + 1);
    }
}
