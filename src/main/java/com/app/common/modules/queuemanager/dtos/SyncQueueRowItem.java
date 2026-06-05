package com.app.common.modules.queuemanager.dtos;

import com.app.common.modules.queuemanager.enums.ItemStatus;

/**
 * JavaFX row adapter for the sync queue tree.
 * <p>
 * JavaFX tree tables require one value type for root and leaf rows, so this
 * adapter wraps either a {@link DeviceQueueItem} root row or a
 * {@link FileQueueItem}
 * leaf row. Sync root row id is the camera id. Sync leaf row id is the full
 * local save path reported by the sync worker.
 */
public class SyncQueueRowItem {

    private final DeviceQueueItem device;
    private final FileQueueItem file;

    /**
     * Creates the invisible JavaFX tree root value.
     * <p>
     * This item is not a real queue row and therefore has no row id or root row id.
     */
    public SyncQueueRowItem() {
        this.device = null;
        this.file = null;
    }

    /**
     * Creates a sync root row wrapper.
     *
     * @param device device DTO whose camera id is the sync root row id
     */
    public SyncQueueRowItem(DeviceQueueItem device) {
        this.device = device;
        this.file = null;
    }

    /**
     * Creates a sync leaf row wrapper.
     *
     * @param file file DTO whose row id is the sync full local save path
     */
    public SyncQueueRowItem(FileQueueItem file) {
        this.device = null;
        this.file = file;
    }

    /**
     * Returns the text displayed in the first queue column.
     *
     * @return device name for root rows, file name for leaf rows, or "Root" for
     *         the invisible JavaFX tree root
     */
    public String getName() {
        if (device != null) {
            return device.getDeviceName();
        }
        if (file != null) {
            return file.getFileName();
        }
        return "Root";
    }

    /**
     * Returns the row id for the visible sync tree row.
     * <p>
     * Root rows use the camera id. Leaf file rows use the sync file's full local
     * save path. The device hardware id is intentionally not part of queue row
     * identity.
     */
    public String getRowId() {
        if (device != null) {
            return device.getCameraId();
        }
        if (file != null) {
            return file.getRowId();
        }
        return null;
    }

    /**
     * Creates a new wrapper around the same mutable row DTO.
     * <p>
     * TreeTableView cells observe wrapper replacement more reliably than internal
     * DTO mutation, so controllers replace row values with copies after updates.
     *
     * @return wrapper preserving the same root or leaf row identity
     */
    public SyncQueueRowItem copy() {
        if (device != null) {
            return new SyncQueueRowItem(device);
        }
        if (file != null) {
            return new SyncQueueRowItem(file);
        }
        return new SyncQueueRowItem();
    }

    /**
     * Returns the status displayed for this sync row.
     *
     * @return aggregate device status for root rows, file status for leaf rows, or
     *         QUEUED for the invisible JavaFX tree root
     */
    public ItemStatus getStatus() {
        if (device != null) {
            return device.getStatus();
        }
        if (file != null) {
            return file.getStatus();
        }
        return ItemStatus.QUEUED;
    }

    /**
     * Returns file progress for leaf rows.
     *
     * @return leaf progress percentage, or 0 for root rows because root rows show
     *         aggregate counts instead
     */
    public int getProgress() {
        return file != null ? file.getProgress() : 0;
    }

    /**
     * Returns the displayed failure/deferred reason for a leaf row.
     *
     * @return file error message, or null for root rows
     */
    public String getReason() {
        return file != null ? file.getErrorMessage() : null;
    }

    /**
     * Indicates whether this wrapper represents a sync root row.
     *
     * @return true when this row wraps a device root row
     */
    public boolean isDevice() {
        return device != null;
    }

    /**
     * Returns the total file count for a sync root row.
     *
     * @return aggregate total for device rows, or 0 for leaf rows
     */
    public int getTotal() {
        return device != null ? device.getTotal() : 0;
    }

    /**
     * Returns the successful file count for a sync root row.
     *
     * @return aggregate passed count for device rows, or 0 for leaf rows
     */
    public int getPassed() {
        return device != null ? device.getPassed() : 0;
    }

    /**
     * Returns the failed file count for a sync root row.
     *
     * @return aggregate failed count for device rows, or 0 for leaf rows
     */
    public int getFailed() {
        return device != null ? device.getFailed() : 0;
    }

    /**
     * Returns the camera id used by sync retry actions.
     *
     * @return root row id for device rows, or null for file rows
     */
    public String getCameraId() {
        return device != null ? device.getCameraId() : null;
    }
}
