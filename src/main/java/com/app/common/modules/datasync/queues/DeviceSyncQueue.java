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

    private volatile String currentCameraId = null;

    private final DeviceMiniStatus deviceMiniStatus;
    private final QueueManagerService queueManagerService;

    public DeviceSyncQueue(DeviceMiniStatus deviceMiniStatus, QueueManagerService queueManagerService) {
        this.deviceMiniStatus = deviceMiniStatus;
        this.queueManagerService = queueManagerService;
    }

    public record Entry(String hardwareId, SyncContext context) {
    }

    public boolean isActive() {
        return currentCameraId != null || !queue.isEmpty();
    }

    // Returns false if input is invalid or the camera is already syncing/queued,
    // avoiding duplicate entries across hardware reconnects.
    public boolean add(SyncContext context) {
        if (context == null || context.cameraId() == null || context.cameraId().isBlank()
                || context.hardwareId() == null || context.hardwareId().isBlank()) {
            return false;
        }

        String cameraId = context.cameraId();
        String hardwareId = context.hardwareId();
        if (cameraId.equals(currentCameraId) || !inQueue.add(cameraId)) {
            return false;
        }

        if (queue.offer(new Entry(hardwareId, context))) {
            deviceMiniStatus.markQueued(cameraId);
            queueManagerService.addDeviceToSyncTracker(context);
            log.info("Added camera {} to device sync queue using hardware {}", cameraId, hardwareId);
            return true;
        } else {
            inQueue.remove(cameraId);
            return false;
        }
    }

    public Entry take() throws InterruptedException {
        Entry entry = queue.take();
        currentCameraId = entry.context().cameraId();
        inQueue.remove(entry.context().cameraId());
        return entry;
    }

    public void done(String cameraId) {
        if (cameraId == null || cameraId.isBlank()) {
            return;
        }
        if (cameraId.equals(currentCameraId)) {
            currentCameraId = null;
        }
        deviceMiniStatus.markDone(cameraId);
    }

    // Cancel a queued or in-progress sync when the camera disconnects.
    public void remove(String cameraId) {
        if (cameraId == null || cameraId.isBlank()) {
            return;
        }

        List<Entry> removedEntries = new ArrayList<>();
        queue.removeIf(entry -> {
            boolean matches = cameraId.equals(entry.context().cameraId());
            if (matches) {
                removedEntries.add(entry);
            }
            return matches;
        });
        inQueue.remove(cameraId);

        if (!removedEntries.isEmpty()) {
            deviceMiniStatus.markDone(cameraId);
        }
        if (cameraId.equals(currentCameraId)) {
            currentCameraId = null;
            deviceMiniStatus.markCancelled(cameraId);
        }
        log.info("Removed camera {} from device sync queue", cameraId);
    }

    public void clearAll() {
        List<Entry> entries = new ArrayList<>();
        queue.drainTo(entries);
        for (Entry entry : entries) {
            deviceMiniStatus.markDone(entry.context().cameraId());
        }
        inQueue.clear();
        currentCameraId = null;
        log.info("Device sync queue cleared");
    }

    // Check if camera is currently in sync queue or actively syncing.
    public boolean isInQueue(String cameraId) {
        if (cameraId == null || cameraId.isBlank()) {
            return false;
        }
        return cameraId.equals(currentCameraId) || inQueue.contains(cameraId);
    }
}
