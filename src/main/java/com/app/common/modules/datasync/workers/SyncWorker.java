package com.app.common.modules.datasync.workers;

import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.definitions.AppConstants;
import com.app.common.services.AppConfigService;
import com.app.common.dtos.FileInfo;
import com.app.common.dtos.SyncContext;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.datasync.services.DataSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class SyncWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

    private static final String SHELL = "shell";
    private static final String INTERNAL_ROOT = System.getProperty("app.internal.root", "/storage/emulated/0/DCIM");
    private static final String EXTERNAL_SUFFIX = "/Android/data/com.bodycamera.nettysocket/cache";

    private static final List<String> TYPES = List.of("audio", "image", "video", "IMP", "SOS");

    private static final int MAX_RETRY = 3;
    private static final long LARGE_FILE_THRESHOLD = 1024L * 1024 * 1024;

    private final DeviceSyncQueue queue;
    private final DataSyncService service;
    private final FolderManagerService folderManagerService;
    private final AppConfigService appConfigService;

    private record LocalFile(String path, String type) {
    }

    private record PendingFile(String remotePath, String localPath, long size, FileInfo info, String type) {
    }

    public SyncWorker(DeviceSyncQueue queue,
            DataSyncService service,
            FolderManagerService folderManagerService,
            AppConfigService appConfigService) {
        this.queue = queue;
        this.service = service;
        this.folderManagerService = folderManagerService;
        this.appConfigService = appConfigService;
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

            boolean autoDelete = appConfigService.getConfigValue(AppConstants.KEY_IS_AUTO_DELETE_AFTER_SYNC)
                    .equalsIgnoreCase("true");

            Set<String> synced = service.loadSyncedPaths(deviceId);

            List<String> allFiles = new ArrayList<>(findFiles(serial, INTERNAL_ROOT));
            String ext = getExternalStorage(serial);
            if (ext != null) {
                allFiles.addAll(findFiles(serial, ext + EXTERNAL_SUFFIX));
            }

            List<PendingFile> failed = new ArrayList<>();

            for (String path : allFiles) {
                if (!alive(serial))
                    break;
                processFile(serial, ctx, path, synced, failed, autoDelete);
            }

            retryFailed(serial, failed, synced, autoDelete);

        } finally {
            queue.done(serial);
        }
    }

    private void processFile(String serial,
            SyncContext ctx,
            String remotePath,
            Set<String> synced,
            List<PendingFile> failed,
            boolean autoDelete) {

        FileInfo info = FileInfo.parse(name(remotePath));
        if (info == null || !ctx.canSync(info.userName()))
            return;

        Long dId = service.resolveDeviceId(info.deviceName());
        Long uId = service.resolveUserId(info.userName());
        if (dId == null || uId == null)
            return;

        LocalFile local = resolveLocalPath(info, remotePath);
        if (local == null || synced.contains(local.path()))
            return;

        long size = getRemoteSize(serial, remotePath);

        if (size > LARGE_FILE_THRESHOLD) {
            service.saveLargeFile(uId, dId,
                    name(remotePath), local.path(),
                    size, info.createDate(), local.type());
            return;
        }

        if (pullAndVerify(serial, remotePath, local.path())) {
            service.saveFile(uId, dId,
                    name(remotePath), local.path(),
                    size, info.createDate(), local.type());

            synced.add(local.path());

            if (autoDelete) {
                deleteRemoteFile(serial, remotePath);
            }
        } else {
            failed.add(new PendingFile(remotePath, local.path(), size, info, local.type()));
        }
    }

    private List<String> findFiles(String serial, String root) {
        List<String> result = new ArrayList<>();
        try {
            List<String> cmd = new ArrayList<>(List.of("adb", "-s", serial, SHELL, "find"));
            TYPES.forEach(t -> cmd.add(root + "/" + t));
            cmd.add("-type");
            cmd.add("f");

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank() && !line.startsWith("find:")) {
                        result.add(line.trim());
                    }
                }
            }

            p.waitFor();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("find error: {}", root, e);
        }
        return result;
    }

    private void retryFailed(String serial,
            List<PendingFile> failed,
            Set<String> synced,
            boolean autoDelete) {

        List<PendingFile> remaining = new ArrayList<>(failed);

        for (int i = 1; i <= MAX_RETRY; i++) {

            if (remaining.isEmpty() || !alive(serial)) {
                break;
            }

            List<PendingFile> next = new ArrayList<>();

            for (PendingFile pf : remaining) {

                if (!alive(serial)) {
                    break;
                }

                if (!retryOneFile(serial, pf, synced, autoDelete)) {
                    next.add(pf);
                }
            }

            remaining = next;
        }

        saveFailedRemaining(remaining);
    }

    private boolean retryOneFile(String serial,
            PendingFile pf,
            Set<String> synced,
            boolean autoDelete) {

        Long dId = service.resolveDeviceId(pf.info().deviceName());
        Long uId = service.resolveUserId(pf.info().userName());

        if (dId == null || uId == null) {
            return false;
        }

        if (!pullAndVerify(serial, pf.remotePath(), pf.localPath())) {
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

    private void saveFailedRemaining(List<PendingFile> remaining) {
        for (PendingFile pf : remaining) {
            Long dId = service.resolveDeviceId(pf.info().deviceName());
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

    private boolean pullAndVerify(String serial, String remote, String local) {
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
                        && verifyIntegrity(serial, remote, local);
            });
        } catch (Exception e) {
            return false;
        }
    }

    private boolean verifyIntegrity(String serial, String remote, String local) {
        long r = getRemoteSize(serial, remote);
        return r < 0 || r == new File(local).length();
    }

    private String getExternalStorage(String serial) {
        try {
            Process p = new ProcessBuilder("adb", "-s", serial, SHELL, "ls /storage")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.matches("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")) {
                        return "/storage/" + line;
                    }
                }
            }

        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private long getRemoteSize(String serial, String remote) {
        try {
            Process p = new ProcessBuilder("adb", "-s", serial,
                    SHELL, "stat -c %s '" + remote + "'")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null && line.matches("\\d+")) {
                    return Long.parseLong(line);
                }
            }

        } catch (Exception e) {
            return -1;
        }
        return -1;
    }

    private boolean pullFile(String serial, String remote, String local) {
        try {
            Process p = new ProcessBuilder("adb", "-s", serial, "pull", remote, local)
                    .redirectErrorStream(true)
                    .start();

            return p.waitFor() == 0;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("pull error: {}", remote, e);
        }
        return false;
    }

    private boolean deleteRemoteFile(String serial, String remote) {
        try {
            Process p = new ProcessBuilder("adb", "-s", serial, SHELL, "rm", "-f", remote)
                    .redirectErrorStream(true)
                    .start();

            return p.waitFor() == 0;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean alive(String serial) {
        try {
            Process p = new ProcessBuilder("adb", "-s", serial, "get-state")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return "device".equals(r.readLine());
            }

        } catch (Exception e) {
            return false;
        }
    }

    private String name(String path) {
        return path.substring(path.lastIndexOf("/") + 1);
    }
}
