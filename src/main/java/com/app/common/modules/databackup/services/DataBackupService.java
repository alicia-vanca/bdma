package com.app.common.modules.databackup.services;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

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
    private final AtomicBoolean recoveryInProgress = new AtomicBoolean(false);
    private final ScheduledExecutorService recoveryScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("backup-recovery-scheduler").factory());

    public DataBackupService(@Lazy FileRepository fileRepo,
            DataBackupQueue dataBackupQueue,
            ApplicationEventPublisher publisher,
            Session session) {
        this.fileRepo = fileRepo;
        this.dataBackupQueue = dataBackupQueue;
        this.publisher = publisher;
        this.session = session;
        recoveryScheduler.scheduleWithFixedDelay(this::scheduledRecoverySafely, 0, 1, TimeUnit.HOURS);
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

    private void scheduledRecoverySafely() {
        try {
            scheduledRecovery();
        } catch (RuntimeException e) {
            log.warn("Scheduled backup recovery trigger failed", e);
        }
    }

    public void scheduledRecovery() {
        // Periodically fire StorageRestoredEvent so the worker retries deferred files
        // even if no real drive event occurred (e.g. drive was re-plugged silently).
        if (session.getUser() == null) {
            return;
        }
        publisher.publishEvent(new StorageRestoredEvent(FolderType.BACKUP));
    }

    /**
     * Queries the database for files that have been synced but not yet backed up
     * and re-enqueues them. Called on storage restore and by the periodic
     * scheduler.
     */
    public void recoverPendingBackups() {
        // Prevent overlapping scans when multiple triggers fire close together.
        if (!recoveryInProgress.compareAndSet(false, true)) {
            if (log.isDebugEnabled()) {
                log.debug("Recovery scan already running, skip overlapping trigger.");
            }
            return;
        }

        try {
            List<String> pending = fileRepo.loadPendingBackup();

            if (pending.isEmpty()) {
                log.debug("Recovery scan: no pending backup files found.");
                return;
            }

            log.info("Recovery scan: found {} file(s) pending backup, enqueuing...", pending.size());
            pending.forEach(dataBackupQueue::add);
        } finally {
            recoveryInProgress.set(false);
        }
    }

    @PreDestroy
    public void shutdownRecoveryScheduler() {
        recoveryScheduler.shutdownNow();
    }
}
