package com.app.sync.service;

import com.app.sync.queue.BackupQueue;
import com.app.sync.repository.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final FileRepository fileRepo;
    private final BackupQueue backupQueue;

    public BackupService(FileRepository fileRepo, BackupQueue backupQueue) {
        this.fileRepo = fileRepo;
        this.backupQueue = backupQueue;
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
        pending.forEach(backupQueue::add);
    }

    /**
     * Enqueues a file after successful sync.
     */
    public void enqueue(String localPath) {
        backupQueue.add(localPath);
    }

    /**
     * Updates file status to BACKUP after successful backup.
     */
    public void markBackup(String localPath) {
        fileRepo.updateStatus(localPath, "BACKUP");
    }
}
