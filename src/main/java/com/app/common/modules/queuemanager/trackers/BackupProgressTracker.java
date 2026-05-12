package com.app.common.modules.queuemanager.trackers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.app.common.modules.queuemanager.dtos.FileQueueItem;
import com.app.common.modules.queuemanager.enums.ItemStatus;
import com.app.common.modules.queuemanager.enums.QueueType;
import com.app.common.modules.queuemanager.events.QueueStatusChangedEvent;
import com.app.common.modules.session.Session;
import com.app.common.dtos.FileInfo;
import com.app.common.models.User;

import java.io.File;

/**
 * Tracks backup queue progress for individual files.
 */
@Component
public class BackupProgressTracker {

    private final Map<String, FileQueueItem> backupFiles = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ApplicationEventPublisher eventPublisher;
    private final Session session;

    public BackupProgressTracker(ApplicationEventPublisher eventPublisher, Session session) {
        this.eventPublisher = eventPublisher;
        this.session = session;
    }

    /**
     * Add a file to backup queue tracking.
     * If file already exists, resets status to QUEUED (e.g., recovering from
     * DEFERRED).
     */
    public void addFile(String filePath, String fileName) {
        FileQueueItem existing = backupFiles.get(filePath);
        if (existing != null) {
            existing.setStatus(ItemStatus.QUEUED);
            existing.setProgress(0);
            existing.setErrorMessage(null);
            publishEvent(filePath, "File re-queued for backup");
        } else {
            FileQueueItem item = new FileQueueItem(fileName, filePath, QueueType.BACKUP);
            backupFiles.put(filePath, item);
            publishEvent(filePath, "File added to backup queue");
        }
    }

    /**
     * Mark file as currently backing up.
     */
    public void markProcessing(String filePath) {
        FileQueueItem item = backupFiles.get(filePath);
        if (item != null) {
            item.setStatus(ItemStatus.PROCESSING);
            publishEvent(filePath, "Backup in progress");
        }
    }

    /**
     * Update backup progress percentage.
     */
    public void updateProgress(String filePath, int progress) {
        FileQueueItem item = backupFiles.get(filePath);
        if (item != null) {
            item.setProgress(progress);
            publishEvent(filePath, "Backup progress: " + progress + "%");
        }
    }

    /**
     * Mark file backup as completed.
     */
    public void markCompleted(String filePath) {
        FileQueueItem item = backupFiles.get(filePath);
        if (item != null) {
            item.setStatus(ItemStatus.COMPLETED);
            item.setProgress(100);
            publishEvent(filePath, "Backup completed");
        }
    }

    /**
     * Mark file backup as failed.
     */
    public void markFailed(String filePath, String errorMessage) {
        FileQueueItem item = backupFiles.get(filePath);
        if (item != null) {
            item.setStatus(ItemStatus.FAILED);
            item.setErrorMessage(errorMessage);
            publishEvent(filePath, "Backup failed: " + errorMessage);
        }
    }

    /**
     * Mark file backup as deferred until next auto-backup scan.
     */
    public void markDeferred(String filePath, String reason) {
        FileQueueItem item = backupFiles.get(filePath);
        if (item != null) {
            item.setStatus(ItemStatus.DEFERRED);
            item.setErrorMessage(reason);
            item.setProgress(0);
            publishEvent(filePath, "Backup deferred: " + reason);
        }
    }

    /**
     * Remove file from tracking after completion or failure.
     */
    public void removeFile(String filePath) {
        backupFiles.remove(filePath);
        publishEvent(filePath, "File removed from backup queue");
    }

    /**
     * Get all files currently in backup queue.
     * For non-admin users, only show files belonging to current user.
     */
    public List<FileQueueItem> getAllFiles() {
        // Admin sees all files
        if (session.isAdmin()) {
            return new ArrayList<>(backupFiles.values());
        }

        // Non-admin: filter by username parsed from filename
        User currentUser = session.getUser();
        if (currentUser == null) {
            return new ArrayList<>();
        }

        String currentUsername = currentUser.getUsername();
        List<FileQueueItem> result = new ArrayList<>();

        for (FileQueueItem item : backupFiles.values()) {
            String fileName = new File(item.getFilePath()).getName();
            FileInfo fileInfo = FileInfo.parse(fileName);

            if (fileInfo != null && currentUsername.equals(fileInfo.username())) {
                result.add(item);
            }
        }

        return result;
    }

    /**
     * Get specific file status.
     */
    public FileQueueItem getFile(String filePath) {
        return backupFiles.get(filePath);
    }

    /**
     * Clear all backup tracking.
     */
    public void clear() {
        backupFiles.clear();
        publishEvent(null, "Backup queue cleared");
    }

    private void publishEvent(String filePath, String message) {
        eventPublisher.publishEvent(new QueueStatusChangedEvent(QueueType.BACKUP, filePath, message));
    }
}
