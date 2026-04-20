package com.app.common.modules.databackup.services;

import com.app.common.definitions.AppConstants;
import com.app.common.modules.databackup.queues.DataBackupQueue;
import com.app.common.repositories.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DataBackupService {

    private static final Logger log = LoggerFactory.getLogger(DataBackupService.class);

    private final FileRepository fileRepo;
    private final DataBackupQueue dataBackupQueue;

    public DataBackupService(FileRepository fileRepo, DataBackupQueue dataBackupQueue) {
        this.fileRepo = fileRepo;
        this.dataBackupQueue = dataBackupQueue;
    }

    /**
     * Initializes backup queue on application startup.
     * Loads files with status = SYNCED and enqueues them for backup.
     * Handles recovery after unexpected shutdown.
     */
    public void init() {
        List<String> pending = fileRepo.loadPendingBackup();

        if (pending.isEmpty()) {
            log.info("No pending backup files found.");
            return;
        }

        log.info("Found {} file(s) pending backup, enqueuing...", pending.size());
        pending.forEach(dataBackupQueue::add);
    }

    /**
     * Enqueues a file after successful sync.
     */
    public void enqueue(String localPath) {
        dataBackupQueue.add(localPath);
    }

    /**
     * Updates file status to BACKEDUP after successful backup.
     */
    public void markBackup(String localPath) {
        fileRepo.updateStatus(localPath, AppConstants.FILE_STATUS_BACKEDUP);
    }
}
