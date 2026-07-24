package com.app.common.modules.databackup.workers;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import com.app.common.utils.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.modules.databackup.events.FileBackupCompletedEvent;
import com.app.common.modules.databackup.queues.DataBackupQueue;
import com.app.common.modules.databackup.services.DataBackupService;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.exceptions.FileNotFoundOnAnyDriveException;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.queuemanager.services.QueueManagerService;
import com.app.common.modules.device.services.DeviceMiniStatus;

/**
 * Worker thread responsible for backing up files from syncDir to backupDir.
 * Waits for sync to complete before processing backup queue.
 * Pauses if sync becomes active mid-backup (finishes current file first).
 * Flow:
 * BackupQueue.take() → FolderManagerService.backupFromSave() →
 * BackupService.markBackup()
 */
@Component
public class DataBackupWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DataBackupWorker.class);
    private static final String MSG_STORAGE_UNAVAILABLE = "device.backup.error.storage_unavailable";
    private static final String MSG_STORAGE_FULL = "device.backup.error.storage_full";
    private static final String MSG_FILE_NOT_FOUND = "device.backup.error.file_not_found";

    private final DataBackupQueue dataBackupQueue;
    private final FolderManagerService folderManager;
    private final DataBackupService dataBackupService;
    private final ApplicationEventPublisher publisher;
    private final DeviceMiniStatus deviceMiniStatus;
    private final QueueManagerService queueManagerService;
    private final AtomicReference<RecoveryState> recoveryState = new AtomicReference<>(
            new RecoveryState(RecoveryStatus.RESTORED, null));
    private volatile boolean shutdownRequested;
    private volatile boolean forcedShutdownRequested;
    private final Object recoveryLock = new Object();

    public DataBackupWorker(DataBackupQueue dataBackupQueue,
            FolderManagerService folderManager,
            DataBackupService dataBackupService,
            ApplicationEventPublisher publisher,
            DeviceMiniStatus deviceMiniStatus,
            QueueManagerService queueManagerService) {
        this.dataBackupQueue = dataBackupQueue;
        this.folderManager = folderManager;
        this.dataBackupService = dataBackupService;
        this.publisher = publisher;
        this.deviceMiniStatus = deviceMiniStatus;
        this.queueManagerService = queueManagerService;
    }

    @Override
    public void run() {
        log.info("Backup worker started");

        while (!Thread.currentThread().isInterrupted() && !shutdownRequested) {
            String nonDriveLetterSyncedPath = null;
            try {
                nonDriveLetterSyncedPath = dataBackupQueue.take();

                // Wait until no sync is active before processing backup
                waitForSyncToComplete();

                if (!shutdownRequested) {
                    RecoveryState currentRecoveryState = recoveryState.get();
                    if (currentRecoveryState.status() == RecoveryStatus.DEFERRED) {
                        log.debug("Backup is deferred. Skip current cycle: {}", nonDriveLetterSyncedPath);
                        String deferredMessage = getDeferredMessage(currentRecoveryState.reason());
                        queueManagerService.markBackupFileDeferred(nonDriveLetterSyncedPath, deferredMessage);
                    } else {
                        processBackup(nonDriveLetterSyncedPath);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

            } catch (Exception e) {
                log.error("Backup failed: {}", nonDriveLetterSyncedPath, e);

            } finally {
                if (nonDriveLetterSyncedPath != null) {
                    dataBackupQueue.done(nonDriveLetterSyncedPath);
                }
            }
        }
        if (!forcedShutdownRequested) {
            log.info("Backup worker stopped gracefully");
        }
    }

    /**
     * Get deferred message based on the reason.
     */
    private String getDeferredMessage(StorageIssueReason reason) {
        if (reason == StorageIssueReason.LOW_SPACE) {
            return MSG_STORAGE_FULL;
        } else if (reason == StorageIssueReason.DRIVE_UNAVAILABLE) {
            return MSG_STORAGE_UNAVAILABLE;
        }
        return MSG_STORAGE_UNAVAILABLE;
    }

    /**
     * Process backup for a single file path.
     */
    private void processBackup(String nonDriveLetterSyncedPath) {
        if (!folderManager.isBackupDirConfigured()) {
            log.warn("BackupDir not configured, skipping: {}", nonDriveLetterSyncedPath);
            return;
        }

        if (!checkBackupDirAccessible(nonDriveLetterSyncedPath)) {
            return;
        }

        if (!checkSufficientSpace(nonDriveLetterSyncedPath)) {
            return;
        }

        performBackup(nonDriveLetterSyncedPath);
    }

    /**
     * Verify backup directory is accessible.
     */
    private boolean checkBackupDirAccessible(String nonDriveLetterSyncedPath) {
        if (!folderManager.isBackupDirAccessible()) {
            log.debug("DataBackupWorker.checkBackupDirAccessible firing StorageUnavailableEvent: target={} reason={}",
                    FolderType.BACKUP, StorageIssueReason.DRIVE_UNAVAILABLE);
            beginBackupDirRecovery(StorageIssueReason.DRIVE_UNAVAILABLE);
            publisher.publishEvent(
                    new StorageUnavailableEvent(FolderType.BACKUP, StorageIssueReason.DRIVE_UNAVAILABLE));
            waitForBackupDirRecovery();
            if (shutdownRequested || Thread.currentThread().isInterrupted()) {
                return false;
            }
            if (recoveryState.get().status() == RecoveryStatus.DEFERRED) {
                log.warn("BackupDir drive unavailable, skipping this cycle: {}", nonDriveLetterSyncedPath);
                queueManagerService.markBackupFileDeferred(nonDriveLetterSyncedPath,
                        MSG_STORAGE_UNAVAILABLE);
                return false;
            }
        }
        return true;
    }

    /**
     * Verify sufficient space exists for backup.
     */
    private boolean checkSufficientSpace(String nonDriveLetterSyncedPath) {
        try {
            long sourceSize = folderManager.resolveExistingFileSize(nonDriveLetterSyncedPath);
            if (!folderManager.hasSufficientSpace(folderManager.getBackupDir(), sourceSize)) {
                log.debug(
                        "DataBackupWorker.checkSufficientSpace firing StorageUnavailableEvent: target={} reason={} requiredBytes={}",
                        FolderType.BACKUP, StorageIssueReason.LOW_SPACE, sourceSize);
                beginBackupDirRecovery(StorageIssueReason.LOW_SPACE);
                publisher.publishEvent(
                        new StorageUnavailableEvent(FolderType.BACKUP, StorageIssueReason.LOW_SPACE, sourceSize));
                waitForBackupDirRecovery();
                if (shutdownRequested || Thread.currentThread().isInterrupted()) {
                    return false;
                }
                if (recoveryState.get().status() == RecoveryStatus.DEFERRED) {
                    log.warn("BackupDir low space, skipping this cycle: {}", nonDriveLetterSyncedPath);
                    queueManagerService.markBackupFileDeferred(nonDriveLetterSyncedPath,
                            MSG_STORAGE_FULL);
                    return false;
                }
            }
            return true;
        } catch (FileNotFoundOnAnyDriveException e) {
            queueManagerService.markBackupFileFailed(nonDriveLetterSyncedPath,
                    MSG_FILE_NOT_FOUND);
            log.info("Synced file not found during space check, skipping: {}", nonDriveLetterSyncedPath);
            return false;
        } catch (Exception e) {
            log.error("Failed to check space for: {}", nonDriveLetterSyncedPath, e);
            queueManagerService.markBackupFileFailed(nonDriveLetterSyncedPath, e.getMessage());
            return false;
        }
    }

    /**
     * Execute the actual backup operation and mark completion.
     */
    private void performBackup(String nonDriveLetterSyncedPath) {
        try {
            queueManagerService.markBackupFileProcessing(nonDriveLetterSyncedPath);

            String absoluteBackupPath = folderManager.backupFromSave(nonDriveLetterSyncedPath);
            String nonDriveLetterBackedUpPath = FileUtil.stripDriveLetter(absoluteBackupPath);

            dataBackupService.markBackup(nonDriveLetterSyncedPath, nonDriveLetterBackedUpPath);

            queueManagerService.markBackupFileCompleted(nonDriveLetterSyncedPath);
            publisher.publishEvent(new FileBackupCompletedEvent(nonDriveLetterSyncedPath));
        } catch (FileNotFoundOnAnyDriveException e) {
            queueManagerService.markBackupFileFailed(nonDriveLetterSyncedPath,
                    MSG_FILE_NOT_FOUND);
            log.info("Synced file not found, skipping this cycle: {}", nonDriveLetterSyncedPath);
        } catch (IOException e) {
            // This can occur if backup dir becomes inaccessible mid-backup
            if (!checkBackupDirAccessible(nonDriveLetterSyncedPath)) {
                return;
            }
            if (!checkSufficientSpace(nonDriveLetterSyncedPath)) {
                return;
            }
            queueManagerService.markBackupFileFailed(nonDriveLetterSyncedPath, e.getMessage());
            log.error("IO exception during backup for: {}", nonDriveLetterSyncedPath, e);
        } catch (Exception e) {
            queueManagerService.markBackupFileFailed(nonDriveLetterSyncedPath, e.getMessage());
            log.error("Backup operation failed for: {}", nonDriveLetterSyncedPath, e);
        }
    }

    @EventListener
    public void onStorageRecoveryDeferred(StorageRecoveryDeferredEvent event) {
        if (event != null && event.getTarget() == FolderType.BACKUP) {
            synchronized (recoveryLock) {
                recoveryState.set(new RecoveryState(RecoveryStatus.DEFERRED, event.getReason()));
                recoveryLock.notifyAll();
            }
            if (log.isDebugEnabled()) {
                log.debug("Backup recovery deferred by user. target={} reason={}", event.getTarget(),
                        event.getReason());
            }
        }
    }

    @EventListener
    public void onStorageRestored(StorageRestoredEvent event) {
        if (event != null && event.getTarget() == FolderType.BACKUP) {
            synchronized (recoveryLock) {
                recoveryState.set(new RecoveryState(RecoveryStatus.RESTORED, null));
                recoveryLock.notifyAll();
            }
            dataBackupService.recoverPendingBackups();
        }
    }

    /**
     * Wait for backup directory to be recovered after becoming inaccessible or
     * running out of space.
     * Blocks until StorageRestoredEvent or StorageRecoveryDeferredEvent is fired.
     */
    private void beginBackupDirRecovery(StorageIssueReason reason) {
        synchronized (recoveryLock) {
            recoveryState.set(new RecoveryState(RecoveryStatus.WAITING, reason));
        }
    }

    private void waitForBackupDirRecovery() {
        synchronized (recoveryLock) {
            while (recoveryState.get().status() == RecoveryStatus.WAITING
                    && !shutdownRequested
                    && !Thread.currentThread().isInterrupted()) {
                try {
                    recoveryLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            RecoveryStatus recoveryStatus = recoveryState.get().status();
            if (recoveryStatus == RecoveryStatus.RESTORED) {
                log.info("Backup directory recovered, resuming backup worker");
            } else if (recoveryStatus == RecoveryStatus.DEFERRED) {
                log.info("Backup recovery deferred by user, resuming backup worker in deferred state");
            }
        }
    }

    /**
     * Wait until sync is not active (SYNCING or QUEUED).
     * Polling is intentional because sync state is owned by the device status
     * service.
     */
    @SuppressWarnings("BusyWait")
    private void waitForSyncToComplete() throws InterruptedException {
        while (isSyncActive() && !shutdownRequested) {
            Thread.sleep(5000);
        }
    }

    /**
     * Check if any device has active sync.
     */
    private boolean isSyncActive() {
        return deviceMiniStatus.isAnySyncActive();
    }

    /**
     * Request graceful shutdown. Worker will finish current file then stop.
     */
    public void requestShutdown() {
        log.info("Stopping backup worker");
        shutdownRequested = true;
        notifyRecoveryWaiters();
    }

    public void requestForcedShutdown() {
        forcedShutdownRequested = true;
        shutdownRequested = true;
        notifyRecoveryWaiters();
    }

    private void notifyRecoveryWaiters() {
        synchronized (recoveryLock) {
            recoveryLock.notifyAll();
        }
    }

    /**
     * Returns true when the worker is only waiting for new queue entries.
     */
    public boolean isIdleForShutdown() {
        return !dataBackupQueue.isActive();
    }

    /**
     * Reset shutdown flag when restarting the worker.
     */
    public void resetShutdownFlag() {
        shutdownRequested = false;
        forcedShutdownRequested = false;
    }

    private enum RecoveryStatus {
        WAITING,
        RESTORED,
        DEFERRED
    }

    private record RecoveryState(RecoveryStatus status, StorageIssueReason reason) {
    }
}
