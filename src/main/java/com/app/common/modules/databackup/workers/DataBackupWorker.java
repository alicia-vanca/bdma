package com.app.common.modules.databackup.workers;

import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.databackup.queues.DataBackupQueue;
import com.app.common.modules.databackup.services.DataBackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    public DataBackupWorker(DataBackupQueue dataBackupQueue,
            FolderManagerService folderManager,
            DataBackupService dataBackupService) {
        this.dataBackupQueue = dataBackupQueue;
        this.folderManager = folderManager;
        this.dataBackupService = dataBackupService;
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

                    folderManager.backupFromSave(relativeName);

                    dataBackupService.markBackup(localPath);

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
                    dataBackupQueue.done(localPath);
                }
            }
        }
    }
}
