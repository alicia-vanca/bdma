package com.app.common.modules.queuemanager.dtos;

import com.app.common.modules.queuemanager.enums.ItemStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a sync queue root row and owns the file leaf rows beneath it.
 * <p>
 * In the sync queue, the root row id is the validated camera id. The display
 * name is the device name shown to the user. The connected hardware id is
 * deliberately not stored here because hardware id belongs to ADB/device
 * access, not queue row identity.
 */
public class DeviceQueueItem {
    private final String cameraId;
    private final String deviceName;
    private ItemStatus status;
    private final List<FileQueueItem> files;
    private int total;
    private int passed;
    private int failed;

    /**
     * Creates a sync device root row.
     *
     * @param cameraId   root row id for the sync queue
     * @param deviceName display name shown on the device root row
     */
    public DeviceQueueItem(String cameraId, String deviceName) {
        this.cameraId = cameraId;
        this.deviceName = deviceName;
        this.status = ItemStatus.QUEUED;
        this.files = new ArrayList<>();
    }

    /**
     * Returns the sync root row id.
     *
     * @return validated camera id used to group sync file leaf rows
     */
    public String getCameraId() {
        return cameraId;
    }

    /**
     * Returns the display name for the sync root row.
     *
     * @return user-facing device name; not used as row identity
     */
    public String getDeviceName() {
        return deviceName;
    }

    /**
     * Returns the aggregate status shown on the device root row.
     *
     * @return current device/root row status
     */
    public ItemStatus getStatus() {
        return status;
    }

    /**
     * Sets the aggregate status shown on the device root row.
     *
     * @param status current device/root row status
     */
    public void setStatus(ItemStatus status) {
        this.status = status;
    }

    /**
     * Returns the sync file leaf rows owned by this device root row.
     *
     * @return mutable list of file rows whose root row id is this device
     */
    public List<FileQueueItem> getFiles() {
        return files;
    }

    /**
     * Adds a sync file leaf row under this device root row.
     *
     * @param file file row whose row id is the sync full local save path
     */
    public void addFile(FileQueueItem file) {
        this.files.add(file);
    }

    /**
     * Returns the total number of files reported for this sync root row.
     *
     * @return aggregate total count
     */
    public int getTotal() {
        return total;
    }

    /**
     * Sets the total number of files reported for this sync root row.
     *
     * @param total aggregate total count
     */
    public void setTotal(int total) {
        this.total = total;
    }

    /**
     * Returns the number of successful files for this sync root row.
     *
     * @return aggregate passed count
     */
    public int getPassed() {
        return passed;
    }

    /**
     * Sets the number of successful files for this sync root row.
     *
     * @param passed aggregate passed count
     */
    public void setPassed(int passed) {
        this.passed = passed;
    }

    /**
     * Returns the number of failed files for this sync root row.
     *
     * @return aggregate failed count
     */
    public int getFailed() {
        return failed;
    }

    /**
     * Sets the number of failed files for this sync root row.
     *
     * @param failed aggregate failed count
     */
    public void setFailed(int failed) {
        this.failed = failed;
    }
}
