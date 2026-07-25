package com.app.guest.services;

import com.app.common.modules.databackup.DataBackupRunner;
import com.app.common.modules.databackup.services.DataBackupService;
import com.app.common.modules.datasync.DataSyncRunner;
import com.app.common.modules.device.services.DeviceListState;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

@Component
public class AppStartupService {
    private static final Logger log = LoggerFactory.getLogger(AppStartupService.class);
    private static final long DEVICE_LOAD_RETRY_DELAY_MILLIS = 5_000;

    private final FolderManagerService folderManager;
    private final DataSyncRunner syncRunner;
    private final DataBackupRunner backupRunner;
    private final DataBackupService backupService;
    private final DeviceListState deviceListState;
    private volatile Thread deviceListLoader;

    public AppStartupService(
            FolderManagerService folderManager,
            DataSyncRunner syncRunner,
            DataBackupRunner backupRunner,
            DataBackupService backupService,
            DeviceListState deviceListState) {
        this.folderManager = folderManager;
        this.syncRunner = syncRunner;
        this.backupRunner = backupRunner;
        this.backupService = backupService;
        this.deviceListState = deviceListState;
    }

    public void initialize() {

        folderManager.init();
        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        syncRunner.startSyncWorker();
        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        backupRunner.startBackupWorker();
        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        try {
            backupService.recoverPendingBackups();
        } catch (RuntimeException e) {
            log.error("Failed to recover pending backups after UI initialization", e);
        }
        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        Thread loader = Thread.ofPlatform().daemon().name("device-list-loader").unstarted(this::loadSavedDevices);
        deviceListLoader = loader;
        loader.start();

    }

    private void loadSavedDevices() {
        int attempt = 1;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    deviceListState.loadSavedDevicesIfNeeded();
                    log.info("Saved device list loaded after UI initialization");
                    return;
                } catch (RuntimeException e) {
                    if (attempt == 1) {
                        log.warn("Saved device list load failed; retrying in {} ms",
                                DEVICE_LOAD_RETRY_DELAY_MILLIS, e);
                    } else {
                        log.debug("Saved device list load retry {} failed", attempt, e);
                    }
                }

                try {
                    Thread.sleep(DEVICE_LOAD_RETRY_DELAY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                attempt++;
            }
        } finally {
            deviceListLoader = null;
        }
    }

    @PreDestroy
    public void shutdownDeviceListLoader() {
        stopDeviceListLoader();
    }

    public boolean stopDeviceListLoader() {
        Thread loader = deviceListLoader;
        if (loader != null) {
            loader.interrupt();
            try {
                // ponytail: 2 s shutdown ceiling; use cancellable DB access if repository reads can exceed it.
                loader.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean stopped = !loader.isAlive();
            if (!stopped) {
                log.error("Saved device list loader did not stop within 2000 ms");
            }
            return stopped;
        }
        return true;
    }
}
