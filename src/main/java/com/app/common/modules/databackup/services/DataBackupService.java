package com.app.common.modules.databackup.services;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.modules.databackup.queues.DataBackupQueue;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.session.Session;
import com.app.common.repositories.FileRepository;

@Service
public class DataBackupService {

    private static final Logger log = LoggerFactory.getLogger(DataBackupService.class);

    private final FileRepository fileRepo;
    private final DataBackupQueue dataBackupQueue;
    private final ApplicationEventPublisher publisher;
    private final Session session;

    public DataBackupService(FileRepository fileRepo,
            DataBackupQueue dataBackupQueue,
            ApplicationEventPublisher publisher,
            Session session) {
        this.fileRepo = fileRepo;
        this.dataBackupQueue = dataBackupQueue;
        this.publisher = publisher;
        this.session = session;
    }

    /**
     * Enqueues a file after successful sync.
     */
    public void enqueue(String nonDriverLetterSyncedPath) {
        dataBackupQueue.add(nonDriverLetterSyncedPath);
    }

    /**
     * Updates file status to BACKEDUP and sets backed_up_path after successful
     * backup.
     */
    public void markBackup(String syncedPath, String backedUpPath) {
        fileRepo.updateStatusAndBackupPath(syncedPath, backedUpPath, AppConstants.FILE_STATUS_BACKEDUP);
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void recoverPendingBackups() {
        // Skip recovery if user is not logged in
        if (session.getUser() == null) {
            return;
        }

        // Re-open backup attempts once per recovery window so user can be notified
        // again if backup storage is still unavailable.
        publisher.publishEvent(new StorageRestoredEvent(FolderType.BACKUP));

        List<String> pending = fileRepo.loadPendingBackup();

        if (pending.isEmpty()) {
            log.debug("Recovery scan: no pending backup files found.");
            return;
        }

        log.info("Recovery scan: found {} file(s) pending backup, enqueuing...", pending.size());
        pending.forEach(dataBackupQueue::add);
    }
}
