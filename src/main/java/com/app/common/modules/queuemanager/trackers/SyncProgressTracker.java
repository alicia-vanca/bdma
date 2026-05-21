package com.app.common.modules.queuemanager.trackers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.app.common.dtos.SyncContext;
import com.app.common.modules.queuemanager.dtos.DeviceQueueItem;
import com.app.common.modules.queuemanager.dtos.FileQueueItem;
import com.app.common.modules.queuemanager.enums.ItemStatus;
import com.app.common.modules.queuemanager.enums.QueueType;
import com.app.common.modules.queuemanager.events.QueueStatusChangedEvent;

/**
 * Adapter that wraps existing SyncProgressTracker and adds file-level tracking.
 * Bridges
 * device-level sync progress with queue manager's file-level tracking.
 */
@Component
public class SyncProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(SyncProgressTracker.class);

    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, DeviceQueueItem> devices = Collections.synchronizedMap(new LinkedHashMap<>());

    public SyncProgressTracker(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Add device to sync queue using camera id as the stable root row id.
     *
     * @param syncContext context containing the stable camera id and display name
     */
    public void addDevice(SyncContext syncContext) {
        if (syncContext == null) {
            return;
        }

        String rootRowId = syncContext.cameraId();
        DeviceQueueItem device = new DeviceQueueItem(rootRowId, syncContext.deviceName());
        devices.put(rootRowId, device);
        publishRowChangedEvent(rootRowId, "Device added to sync queue");
    }

    /**
     * Adds a file leaf row under a sync device root row.
     *
     * @param rootRowId camera id of the device root row that owns the file
     * @param fileName  display name shown in the queue UI
     * @param rowId     leaf row id; for sync this is the file's full local save
     *                  path, not the device hardware id
     */
    public void addFileToDevice(String rootRowId, String fileName, String rowId) {
        DeviceQueueItem device = devices.get(rootRowId);
        if (device != null) {
            FileQueueItem fileItem = new FileQueueItem(fileName, rowId);
            fileItem.setRootRowId(rootRowId);
            device.addFile(fileItem);
            publishRowChangedEvent(rowId, rootRowId, "File added: " + fileName);
        }
    }

    /**
     * Marks a sync file leaf row as skipped because it was already synced.
     *
     * @param rootRowId camera id of the device root row
     * @param rowId     leaf row id; for sync this is the file's full local save
     *                  path
     */
    public void markFileSkipped(String rootRowId, String rowId) {
        FileQueueItem file = findFile(rootRowId, rowId);
        if (file != null) {
            file.setStatus(ItemStatus.SKIPPED);
            publishCounterChangedEvent(rowId, rootRowId, "File skipped: " + file.getFileName());
        }
    }

    /**
     * Marks a sync file leaf row as currently processing.
     *
     * @param rootRowId camera id of the device root row
     * @param rowId     leaf row id; for sync this is the file's full local save
     *                  path
     */
    public void markFileProcessing(String rootRowId, String rowId) {
        FileQueueItem file = findFile(rootRowId, rowId);
        if (file != null) {
            file.setStatus(ItemStatus.PROCESSING);
            publishRowChangedEvent(rowId, rootRowId, "Syncing: " + file.getFileName());
        }
    }

    /**
     * Updates progress for a sync file leaf row.
     *
     * @param rootRowId camera id of the device root row
     * @param rowId     leaf row id; for sync this is the file's full local save
     *                  path
     * @param progress  completion percentage from 0 to 100
     */
    public void updateFileProgress(String rootRowId, String rowId, int progress) {
        FileQueueItem file = findFile(rootRowId, rowId);
        if (file != null) {
            file.setProgress(progress);
            publishRowChangedEvent(rowId, rootRowId, "Progress: " + file.getFileName() + " " + progress + "%");
        }
    }

    /**
     * Marks a sync file leaf row as completed.
     *
     * @param rootRowId camera id of the device root row
     * @param rowId     leaf row id; for sync this is the file's full local save
     *                  path
     */
    public void markFileCompleted(String rootRowId, String rowId) {
        FileQueueItem file = findFile(rootRowId, rowId);
        if (file != null) {
            file.setStatus(ItemStatus.COMPLETED);
            file.setProgress(100);
            publishCounterChangedEvent(rowId, rootRowId, "Completed: " + file.getFileName());
        } else {
            log.warn("File not found: rootRowId={} rowId={}", rootRowId, rowId);
        }
    }

    /**
     * Marks a sync file leaf row as failed and stores the failure reason.
     *
     * @param rootRowId    camera id of the device root row
     * @param rowId        leaf row id; for sync this is the file's full local save
     *                     path
     * @param errorMessage reason displayed in the queue UI
     */
    public void markFileFailed(String rootRowId, String rowId, String errorMessage) {
        FileQueueItem file = findFile(rootRowId, rowId);
        if (file != null) {
            file.setStatus(ItemStatus.FAILED);
            file.setErrorMessage(errorMessage);
            file.incrementRetryCount();
            publishCounterChangedEvent(rowId, rootRowId, "Failed: " + file.getFileName() + " - " + errorMessage);
        } else {
            log.warn("File not found: rootRowId={} rowId={}", rootRowId, rowId);
        }
    }

    /**
     * Mark device sync as processing.
     */
    public void markDeviceProcessing(String rootRowId) {
        DeviceQueueItem device = devices.get(rootRowId);
        if (device != null) {
            device.setStatus(ItemStatus.PROCESSING);
            publishRowChangedEvent(rootRowId, "Device sync started");
        }
    }

    /**
     * Mark device sync as completed.
     */
    public void markDeviceCompleted(String rootRowId, int total, int passed, int failed) {
        DeviceQueueItem device = devices.get(rootRowId);
        if (device != null) {
            device.setTotal(total);
            device.setPassed(passed);
            device.setFailed(failed);
            device.setStatus(failed > 0 ? ItemStatus.COMPLETED_WITH_ERRORS : ItemStatus.COMPLETED);
            publishRowChangedEvent(rootRowId,
                    failed > 0 ? "Device sync completed with errors" : "Device sync completed");
        }
    }

    /**
     * Remove device from tracking.
     */
    public void removeDevice(String rootRowId) {
        devices.remove(rootRowId);
        publishQueueChangedEvent(rootRowId, "Device removed from sync queue");
    }

    /**
     * Get all devices in sync queue.
     */
    public List<DeviceQueueItem> getAllDevices() {
        return new ArrayList<>(devices.values());
    }

    /**
     * Get specific device.
     */
    public DeviceQueueItem getDevice(String rootRowId) {
        return devices.get(rootRowId);
    }

    /**
     * Get all files for a device.
     */
    public List<FileQueueItem> getDeviceFiles(String rootRowId) {
        DeviceQueueItem device = devices.get(rootRowId);
        return device != null ? new ArrayList<>(device.getFiles()) : new ArrayList<>();
    }

    /**
     * Clear all sync tracking.
     */
    public void clear() {
        devices.clear();
        publishQueueChangedEvent(null, "Sync queue cleared");
    }

    private FileQueueItem findFile(String rootRowId, String rowId) {
        DeviceQueueItem device = devices.get(rootRowId);
        if (device == null) {
            return null;
        }
        return device.getFiles().stream()
                .filter(file -> rowId.equals(file.getRowId()))
                .findFirst()
                .orElse(null);
    }

    private void publishRowChangedEvent(String rowId, String message) {
        publishRowChangedEvent(rowId, null, message);
    }

    private void publishRowChangedEvent(String rowId, String rootRowId, String message) {
        eventPublisher.publishEvent(new QueueStatusChangedEvent(QueueType.SYNC, rowId, rootRowId, false, message));
    }

    private void publishCounterChangedEvent(String rowId, String rootRowId, String message) {
        eventPublisher.publishEvent(new QueueStatusChangedEvent(QueueType.SYNC, rowId, rootRowId, true, message));
    }

    private void publishQueueChangedEvent(String rowId, String message) {
        eventPublisher.publishEvent(new QueueStatusChangedEvent(QueueType.SYNC, rowId, true, message));
    }
}
