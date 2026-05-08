package com.app.common.modules.databackup.queues;

import org.springframework.stereotype.Component;

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

    public boolean isActive() {
        return !inQueue.isEmpty();
    }

    /**
     * Adds a file to the queue.
     * Skips if the file is already enqueued.
     */
    public void add(String localPath) {
        if (inQueue.add(localPath) && !queue.offer(localPath)) {
            inQueue.remove(localPath);
        }
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
