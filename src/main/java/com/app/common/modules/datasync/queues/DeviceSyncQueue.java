package com.app.common.modules.datasync.queues;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.common.dtos.SyncContext;
import com.app.common.services.SyncProgressTracker;

@Component
public class DeviceSyncQueue {

    private static final Logger log = LoggerFactory.getLogger(DeviceSyncQueue.class);

    public DeviceSyncQueue(SyncProgressTracker progressTracker) {
        this.progressTracker = progressTracker;
    }

    public record Entry(String serial, SyncContext context) {
    }

    private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private final Set<String> inQueue = ConcurrentHashMap.newKeySet();

    private volatile String current = null;

    private final SyncProgressTracker progressTracker;

    // Returns false if input is invalid or the device is already syncing/queued,
    // avoiding duplicate entries.
    public boolean add(String serial, SyncContext context) {
        if (serial == null || serial.isBlank() || context == null) {
            return false;
        }
        if (serial.equals(current) || !inQueue.add(serial)) {
            return false;
        }

        if (queue.offer(new Entry(serial, context))) {
            progressTracker.markQueued(serial);
            log.info("Queue add: {}", serial);
            return true;
        } else {
            inQueue.remove(serial);
            return false;
        }
    }

    public Entry take() throws InterruptedException {
        Entry entry = queue.take();
        current = entry.serial();
        inQueue.remove(entry.serial());
        return entry;
    }

    public void done(String serial) {
        if (serial == null || serial.isBlank()) {
            return;
        }
        if (serial.equals(current)) {
            current = null;
        }
        progressTracker.markDone(serial);
    }

    // Cancel a queued or in-progress sync when the device disconnects.
    public void remove(String serial) {
        if (serial == null || serial.isBlank()) {
            return;
        }

        queue.removeIf(e -> e.serial().equals(serial));
        inQueue.remove(serial);
        if (serial.equals(current)) {
            progressTracker.markCancelled(serial);
        } else {
            progressTracker.markDone(serial);
        }
        log.info("Queue remove: {}", serial);
    }

    public void clearAll() {
        List<Entry> entries = new ArrayList<>();
        queue.drainTo(entries);
        for (Entry entry : entries) {
            progressTracker.markDone(entry.serial());
        }
        inQueue.clear();
        current = null;
        log.info("Queue cleared");
    }
}
