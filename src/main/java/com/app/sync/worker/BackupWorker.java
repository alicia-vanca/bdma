package com.app.sync.worker;

import com.app.file.service.DataFolderManager;
import com.app.sync.queue.BackupQueue;
import com.app.sync.service.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Worker thread responsible for backing up files from dataDir to backupDir.
 * Flow:
 * BackupQueue.take() → DataFolderManager.backupFromSave() → BackupService.markBackup()
 */
@Component
public class BackupWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(BackupWorker.class);

    private final BackupQueue backupQueue;
    private final DataFolderManager dataFolderManager;
    private final BackupService backupService;

    public BackupWorker(BackupQueue backupQueue,
                        DataFolderManager dataFolderManager,
                        BackupService backupService) {
        this.backupQueue = backupQueue;
        this.dataFolderManager = dataFolderManager;
        this.backupService = backupService;
    }

    @Override
    public void run() {
        log.info("BackupWorker started");

        while (true) {
            String localPath = null;
            try {
                localPath = backupQueue.take();

                if (dataFolderManager.isBackupDirConfigured()) {

                    String relativeName = dataFolderManager.toRelativeDataPath(localPath);

                    dataFolderManager.backupFromSave(relativeName);

                    backupService.markBackup(localPath);

                    log.info("Backed up: {}", relativeName);

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
                    backupQueue.done(localPath);
                }
            }
        }
    }
}
