package com.app.common.modules.databackup.workers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.app.common.modules.databackup.events.FileBackupCompletedEvent;
import com.app.common.modules.databackup.queues.DataBackupQueue;
import com.app.common.modules.databackup.services.DataBackupService;
import com.app.common.modules.foldermanager.services.FolderManagerService;

/**
 * Worker thread responsible for backing up files from dataDir to backupDir.
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
            String localPath = null;
            try {
                localPath = dataBackupQueue.take();

                if (folderManager.isBackupDirConfigured()) {

                    String relativeName = folderManager.toRelativeDataPath(localPath);

                    folderManager.backupFromSave(localPath);

                    String absoluteBackupPath = folderManager.getBackupPath(relativeName);
                    String relativeBackupPath = folderManager.stripDriveLetter(absoluteBackupPath);

                    dataBackupService.markBackup(localPath, relativeBackupPath);

                    publisher.publishEvent(new FileBackupCompletedEvent(localPath));

                } else {
                    log.warn("BackupDir not configured, skipping: {}", localPath);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("BackupWorker interrupted");
                break;

            } catch (Exception e) {
                log.error("Backup failed: {}", localPath, e);

            } finally {
                if (localPath != null) {
                    dataBackupQueue.done(localPath);
                }
            }
        }
    }
}
