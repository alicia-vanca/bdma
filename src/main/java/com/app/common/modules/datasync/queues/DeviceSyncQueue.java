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
import com.app.common.modules.queuemanager.services.QueueManagerService;
import com.app.common.services.DeviceMiniStatus;

@Component
public class DeviceSyncQueue {

    private static final Logger log = LoggerFactory.getLogger(DeviceSyncQueue.class);

    private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private final Set<String> inQueue = ConcurrentHashMap.newKeySet();

    private volatile String current = null;

    private final DeviceMiniStatus deviceMiniStatus;
    private final QueueManagerService queueManagerService;

    public DeviceSyncQueue(DeviceMiniStatus deviceMiniStatus, QueueManagerService queueManagerService) {
        this.deviceMiniStatus = deviceMiniStatus;
        this.queueManagerService = queueManagerService;
    }

    public record Entry(String hardwareId, SyncContext context) {
    }

    public boolean isActive() {
        return current != null || !queue.isEmpty();
    }

    // Returns false if input is invalid or the device is already syncing/queued,
    // avoiding duplicate entries.
    public boolean add(String hardwareId, SyncContext context) {
        if (hardwareId == null || hardwareId.isBlank() || context == null) {
            return false;
        }
        if (hardwareId.equals(current) || !inQueue.add(hardwareId)) {
            return false;
        }

        if (queue.offer(new Entry(hardwareId, context))) {
            deviceMiniStatus.markQueued(hardwareId);
            queueManagerService.addDeviceToSyncTracker(hardwareId, context.deviceName());
            log.info("Added to Device sync queue: {}", hardwareId);
            return true;
        } else {
            inQueue.remove(hardwareId);
            return false;
        }
    }

    public Entry take() throws InterruptedException {
        Entry entry = queue.take();
        current = entry.hardwareId();
        inQueue.remove(entry.hardwareId());
        return entry;
    }

    public void done(String hardwareId) {
        if (hardwareId == null || hardwareId.isBlank()) {
            return;
        }
        if (hardwareId.equals(current)) {
            current = null;
        }
        deviceMiniStatus.markDone(hardwareId);
    }

    // Cancel a queued or in-progress sync when the device disconnects.
    public void remove(String hardwareId) {
        if (hardwareId == null || hardwareId.isBlank()) {
            return;
        }

        queue.removeIf(e -> e.hardwareId().equals(hardwareId));
        inQueue.remove(hardwareId);
        if (hardwareId.equals(current)) {
            deviceMiniStatus.markCancelled(hardwareId);
        } else {
            deviceMiniStatus.markDone(hardwareId);
        }
        log.info("Removed from Device sync queue: {}", hardwareId);
    }

    public void clearAll() {
        List<Entry> entries = new ArrayList<>();
        queue.drainTo(entries);
        for (Entry entry : entries) {
            deviceMiniStatus.markDone(entry.hardwareId());
        }
        inQueue.clear();
        current = null;
        log.info("Device sync queue cleared");
    }

    // Check if device is currently in sync queue or actively syncing
    public boolean isInQueue(String hardwareId) {
        if (hardwareId == null || hardwareId.isBlank()) {
            return false;
        }
        return hardwareId.equals(current) || inQueue.contains(hardwareId);
    }
}
