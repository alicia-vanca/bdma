package com.app.common.modules.queuemanager.trackers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

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
    private final Map<String, Map<String, FileQueueItem>> deviceFiles = new ConcurrentHashMap<>();

    public SyncProgressTracker(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Add device to sync queue.
     */
    public void addDevice(String hardwareId, String deviceName) {
        DeviceQueueItem device = new DeviceQueueItem(hardwareId, deviceName);
        devices.put(hardwareId, device);
        deviceFiles.put(hardwareId, Collections.synchronizedMap(new LinkedHashMap<>()));
        publishEvent(hardwareId, "Device added to sync queue");
    }

    /**
     * Add file to device's sync list.
     */
    public void addFileToDevice(String hardwareId, String fileName, String filePath) {
        Map<String, FileQueueItem> files = deviceFiles.get(hardwareId);
        if (files != null) {
            FileQueueItem fileItem = new FileQueueItem(fileName, filePath, QueueType.SYNC);
            fileItem.setDeviceId(hardwareId);
            files.put(filePath, fileItem);

            DeviceQueueItem device = devices.get(hardwareId);
            if (device != null) {
                device.addFile(fileItem);
            }
            publishEvent(hardwareId, "File added: " + fileName);
        }
    }

    /**
     * Mark file as skipped (already synced).
     */
    public void markFileSkipped(String hardwareId, String filePath) {
        Map<String, FileQueueItem> files = deviceFiles.get(hardwareId);
        if (files != null) {
            FileQueueItem file = files.get(filePath);
            if (file != null) {
                file.setStatus(ItemStatus.SKIPPED);
                publishEvent(hardwareId, "File skipped: " + file.getFileName());
            }
        }
    }

    /**
     * Mark file as currently syncing.
     */
    public void markFileProcessing(String hardwareId, String filePath) {
        Map<String, FileQueueItem> files = deviceFiles.get(hardwareId);
        if (files != null) {
            FileQueueItem file = files.get(filePath);
            if (file != null) {
                file.setStatus(ItemStatus.PROCESSING);
                publishEvent(hardwareId, "Syncing: " + file.getFileName());
            }
        }
    }

    /**
     * Update file sync progress.
     */
    public void updateFileProgress(String hardwareId, String filePath, int progress) {
        Map<String, FileQueueItem> files = deviceFiles.get(hardwareId);
        if (files != null) {
            FileQueueItem file = files.get(filePath);
            if (file != null) {
                file.setProgress(progress);
                publishEvent(hardwareId, "Progress: " + file.getFileName() + " " + progress + "%");
            }
        }
    }

    /**
     * Mark file sync as completed.
     */
    public void markFileCompleted(String hardwareId, String filePath) {
        if (log.isDebugEnabled()) {
            log.debug("markFileCompleted called: hardwareId={} filePath={}", hardwareId, filePath);
        }
        Map<String, FileQueueItem> files = deviceFiles.get(hardwareId);
        if (files != null) {
            FileQueueItem file = files.get(filePath);
            if (file != null) {
                file.setStatus(ItemStatus.COMPLETED);
                file.setProgress(100);
                publishEvent(hardwareId, "Completed: " + file.getFileName());
            } else {
                log.warn("File not found in deviceFiles map: hardwareId={} filePath={}", hardwareId,
                        filePath);
            }
        } else {
            log.warn("No files map found for hardwareId={}", hardwareId);
        }
    }

    /**
     * Mark file sync as failed.
     */
    public void markFileFailed(String hardwareId, String filePath, String errorMessage) {
        Map<String, FileQueueItem> files = deviceFiles.get(hardwareId);
        if (files != null) {
            FileQueueItem file = files.get(filePath);
            if (file != null) {
                file.setStatus(ItemStatus.FAILED);
                file.setErrorMessage(errorMessage);
                file.incrementRetryCount();
                publishEvent(hardwareId, "Failed: " + file.getFileName() + " - " + errorMessage);
            } else {
                log.warn("File not found in deviceFiles map: hardwareId={} filePath={}", hardwareId,
                        filePath);
            }
        } else {
            log.warn("No files map found for hardwareId={}", hardwareId);
        }
    }

    /**
     * Mark device sync as processing.
     */
    public void markDeviceProcessing(String hardwareId) {
        DeviceQueueItem device = devices.get(hardwareId);
        if (device != null) {
            device.setStatus(ItemStatus.PROCESSING);
            publishEvent(hardwareId, "Device sync started");
        }
    }

    /**
     * Mark device sync as completed.
     */
    public void markDeviceCompleted(String hardwareId, int total, int passed, int failed) {
        DeviceQueueItem device = devices.get(hardwareId);
        if (device != null) {
            device.setTotal(total);
            device.setPassed(passed);
            device.setFailed(failed);
            device.setStatus(failed > 0 ? ItemStatus.COMPLETED_WITH_ERRORS : ItemStatus.COMPLETED);
            publishEvent(hardwareId,
                    failed > 0 ? "Device sync completed with errors" : "Device sync completed");
        }
    }

    /**
     * Remove device from tracking.
     */
    public void removeDevice(String hardwareId) {
        devices.remove(hardwareId);
        deviceFiles.remove(hardwareId);
        publishEvent(hardwareId, "Device removed from sync queue");
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
    public DeviceQueueItem getDevice(String hardwareId) {
        return devices.get(hardwareId);
    }

    /**
     * Get all files for a device.
     */
    public List<FileQueueItem> getDeviceFiles(String hardwareId) {
        Map<String, FileQueueItem> files = deviceFiles.get(hardwareId);
        return files != null ? new ArrayList<>(files.values()) : new ArrayList<>();
    }

    /**
     * Clear all sync tracking.
     */
    public void clear() {
        devices.clear();
        deviceFiles.clear();
        publishEvent(null, "Sync queue cleared");
    }

    private void publishEvent(String hardwareId, String message) {
        eventPublisher.publishEvent(new QueueStatusChangedEvent(QueueType.SYNC, hardwareId, message));
    }
}
