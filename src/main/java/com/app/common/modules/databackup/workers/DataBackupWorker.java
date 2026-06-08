package com.app.common.modules.databackup.workers;

import java.io.IOException;

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
import com.app.common.services.DeviceMiniStatus;

/**
 * Worker thread responsible for backing up files from dataDir to backupDir.
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
    private volatile boolean backupRecoveryDeferred;
    private volatile StorageIssueReason deferredReason;
    private volatile boolean shutdownRequested;
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

                if (shutdownRequested) {
                    log.info("BackupWorker stopped gracefully");
                    break;
                }

                if (shouldSkipDueToDeferred(nonDriveLetterSyncedPath)) {
                    String deferredMessage = getDeferredMessage();
                    queueManagerService.markBackupFileDeferred(nonDriveLetterSyncedPath, deferredMessage);
                } else {
                    processBackup(nonDriveLetterSyncedPath);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("BackupWorker interrupted");

            } catch (Exception e) {
                log.error("Backup failed: {}", nonDriveLetterSyncedPath, e);

            } finally {
                if (nonDriveLetterSyncedPath != null) {
                    dataBackupQueue.done(nonDriveLetterSyncedPath);
                }
            }
        }

    }

    /**
     * Check if backup should be skipped due to deferred recovery.
     */
    private boolean shouldSkipDueToDeferred(String nonDriveLetterSyncedPath) {
        if (backupRecoveryDeferred) {
            if (log.isDebugEnabled()) {
                log.debug("Backup is deferred. Skip current cycle: {}",
                        nonDriveLetterSyncedPath);
            }
            return true;
        }
        return false;
    }

    /**
     * Get deferred message based on the reason.
     */
    private String getDeferredMessage() {
        if (deferredReason == StorageIssueReason.LOW_SPACE) {
            return MSG_STORAGE_FULL;
        } else if (deferredReason == StorageIssueReason.DRIVE_UNAVAILABLE) {
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
            publisher.publishEvent(
                    new StorageUnavailableEvent(FolderType.BACKUP, StorageIssueReason.DRIVE_UNAVAILABLE));
            waitForBackupDirRecovery(StorageIssueReason.DRIVE_UNAVAILABLE);
            if (backupRecoveryDeferred) {
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
                publisher.publishEvent(
                        new StorageUnavailableEvent(FolderType.BACKUP, StorageIssueReason.LOW_SPACE, sourceSize));
                waitForBackupDirRecovery(StorageIssueReason.LOW_SPACE);
                if (backupRecoveryDeferred) {
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
                backupRecoveryDeferred = true;
                deferredReason = event.getReason();
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
                backupRecoveryDeferred = false;
                deferredReason = null;
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
    private void waitForBackupDirRecovery(StorageIssueReason reason) {
        deferredReason = reason;
        synchronized (recoveryLock) {
            backupRecoveryDeferred = false;

            while (!backupRecoveryDeferred && !shutdownRequested && !Thread.currentThread().isInterrupted()) {
                try {
                    recoveryLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                // Event fired, check which one
                if (!backupRecoveryDeferred) {
                    if (log.isInfoEnabled()) {
                        log.info("Backup directory recovered, resuming backup worker");
                    }
                    return;
                }
            }

            if (backupRecoveryDeferred) {
                log.info("Backup recovery deferred by user, resuming backup worker in deferred state");
            }
        }
    }

    /**
     * Wait until sync is not active (SYNCING or QUEUED).
     */
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
        log.info("Shutdown requested, stopping backup worker");
        shutdownRequested = true;
    }

    /**
     * Reset shutdown flag when restarting the worker.
     */
    public void resetShutdownFlag() {
        shutdownRequested = false;
    }
}
