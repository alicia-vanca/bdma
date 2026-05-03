package com.app.common.modules.databackup.workers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.common.definitions.enums.FolderType;
import com.app.common.modules.databackup.events.FileBackupCompletedEvent;
import com.app.common.modules.databackup.queues.DataBackupQueue;
import com.app.common.modules.databackup.services.DataBackupService;
import com.app.common.modules.foldermanager.events.StorageIssueReason;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.exceptions.FileNotFoundOnAnyDriveException;
import com.app.common.modules.foldermanager.services.FolderManagerService;

/**
 * Worker thread responsible for backing up files from dataDir to backupDir.
 * Processes backup queue independently of sync operations.
 * Flow:
 * BackupQueue.take() → FolderManagerService.backupFromSave() →
 * BackupService.markBackup()
 */
@Component
public class DataBackupWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DataBackupWorker.class);

    private final DataBackupQueue dataBackupQueue;
    private final FolderManagerService folderManager;
    private final DataBackupService dataBackupService;
    private final ApplicationEventPublisher publisher;
    private volatile boolean backupRecoveryDeferred;

    public DataBackupWorker(DataBackupQueue dataBackupQueue,
            FolderManagerService folderManager,
            DataBackupService dataBackupService,
            ApplicationEventPublisher publisher) {
        this.dataBackupQueue = dataBackupQueue;
        this.folderManager = folderManager;
        this.dataBackupService = dataBackupService;
        this.publisher = publisher;
    }

    @Override
    public void run() {
        log.info("BackupWorker started");

        while (true) {
            String nonDriveLetterSyncedPath = null;
            try {
                nonDriveLetterSyncedPath = dataBackupQueue.take();

                if (backupRecoveryDeferred) {
                    if (log.isDebugEnabled()) {
                        log.debug("Backup is deferred by user choice. Skip current cycle: {}",
                                nonDriveLetterSyncedPath);
                    }
                    continue;
                }

                if (folderManager.isBackupDirConfigured()) {
                    if (!folderManager.isBackupDirAccessible()) {
                        publisher.publishEvent(
                                new StorageUnavailableEvent(FolderType.BACKUP, StorageIssueReason.DRIVE_UNAVAILABLE));
                        log.warn("BackupDir drive unavailable, skipping this cycle: {}", nonDriveLetterSyncedPath);
                        continue;
                    }

                    try {
                        long sourceSize = folderManager.resolveExistingFileSize(nonDriveLetterSyncedPath);
                        if (!folderManager.hasSufficientSpace(folderManager.getBackupDir(), sourceSize)) {
                            publisher.publishEvent(
                                    new StorageUnavailableEvent(FolderType.BACKUP, StorageIssueReason.LOW_SPACE,
                                            sourceSize));
                            log.warn("BackupDir low space, skipping this cycle: {}", nonDriveLetterSyncedPath);
                            continue;
                        }

                        String absoluteBackupPath = folderManager.backupFromSave(nonDriveLetterSyncedPath);
                        String nonDriveLetterBackedUpPath = folderManager.stripDriveLetter(absoluteBackupPath);

                        dataBackupService.markBackup(nonDriveLetterSyncedPath, nonDriveLetterBackedUpPath);

                        publisher.publishEvent(new FileBackupCompletedEvent(nonDriveLetterSyncedPath));
                    } catch (FileNotFoundOnAnyDriveException e) {
                        log.info("Synced file not found, skipping this cycle: {}", nonDriveLetterSyncedPath);
                        // skip this file gracefully
                    }

                } else {
                    log.warn("BackupDir not configured, skipping: {}", nonDriveLetterSyncedPath);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("BackupWorker interrupted");
                break;

            } catch (Exception e) {
                log.error("Backup failed: {}", nonDriveLetterSyncedPath, e);

            } finally {
                if (nonDriveLetterSyncedPath != null) {
                    dataBackupQueue.done(nonDriveLetterSyncedPath);
                }
            }
        }
    }

    @EventListener
    public void onStorageRecoveryDeferred(StorageRecoveryDeferredEvent event) {
        if (event != null && event.getTarget() == FolderType.BACKUP) {
            backupRecoveryDeferred = true;
            if (log.isDebugEnabled()) {
                log.debug("Backup recovery deferred by user. target={}", event.getTarget());
            }
        }
    }

    @EventListener
    public void onStorageRestored(StorageRestoredEvent event) {
        if (event != null && event.getTarget() == FolderType.BACKUP) {
            backupRecoveryDeferred = false;
        }
    }
}
