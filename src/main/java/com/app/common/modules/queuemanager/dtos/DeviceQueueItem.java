package com.app.common.modules.queuemanager.dtos;

import com.app.common.dtos.SyncContext;
import com.app.common.modules.queuemanager.enums.ItemStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a device in the sync queue with its file list.
 */
public class DeviceQueueItem {
    private final String hardwareId;
    private final String deviceName;
    private ItemStatus status;
    private final List<FileQueueItem> files;
    private SyncContext syncContext;
    private int total;
    private int passed;
    private int failed;

    public DeviceQueueItem(String hardwareId, String deviceName) {
        this.hardwareId = hardwareId;
        this.deviceName = deviceName;
        this.status = ItemStatus.QUEUED;
        this.files = new ArrayList<>();
    }

    public String getHardwareId() {
        return hardwareId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public ItemStatus getStatus() {
        return status;
    }

    public void setStatus(ItemStatus status) {
        this.status = status;
    }

    public List<FileQueueItem> getFiles() {
        return files;
    }

    public void addFile(FileQueueItem file) {
        this.files.add(file);
    }

    public SyncContext getSyncContext() {
        return syncContext;
    }

    public void setSyncContext(SyncContext syncContext) {
        this.syncContext = syncContext;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPassed() {
        return passed;
    }

    public void setPassed(int passed) {
        this.passed = passed;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }
}
