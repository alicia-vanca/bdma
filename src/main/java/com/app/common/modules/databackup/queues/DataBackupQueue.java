package com.app.common.modules.databackup.queues;

import org.springframework.stereotype.Component;

import com.app.common.modules.queuemanager.services.QueueManagerService;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Queue storing local file paths pending backup to backupDir.
 * Uses a Set to prevent duplicate entries.
 */
@Component
public class DataBackupQueue {

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Set<String> inQueue = ConcurrentHashMap.newKeySet();
    private final QueueManagerService queueManagerService;

    public DataBackupQueue(QueueManagerService queueManagerService) {
        this.queueManagerService = queueManagerService;
    }

    public boolean isActive() {
        return !inQueue.isEmpty();
    }

    /**
     * Adds a file to the queue.
     * If already enqueued, re-adds to tracker to reset status (e.g., from DEFERRED
     * to QUEUED).
     */
    public void add(String localPath) {
        if (inQueue.contains(localPath)) {
            String fileName = extractFileName(localPath);
            queueManagerService.addFileToBackupTracker(localPath, fileName);
            return;
        }
        if (inQueue.add(localPath) && queue.offer(localPath)) {
            String fileName = extractFileName(localPath);
            queueManagerService.addFileToBackupTracker(localPath, fileName);
        } else {
            inQueue.remove(localPath);
        }
    }

    private String extractFileName(String path) {
        if (path == null)
            return "";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * Retrieves the next file from the queue (blocking).
     */
    public String take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Marks a file as processed and removes it from tracking.
     */
    public void done(String localPath) {
        inQueue.remove(localPath);
    }
}
