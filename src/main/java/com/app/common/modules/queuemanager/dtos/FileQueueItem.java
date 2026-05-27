package com.app.common.modules.queuemanager.dtos;

import com.app.common.modules.queuemanager.enums.ItemStatus;

/**
 * Represents a file leaf row in a queue.
 * <p>
 * The queue manager identifies every visible row by {@code rowId}. File rows
 * store that identity directly in {@code rowId}. Sync uses the full local save
 * path as the leaf row id. Export uses the full destination export path. Backup
 * uses the backup file path as the only row id because backup has no root rows.
 */
public class FileQueueItem {
    private String fileName;
    private final String rowId;
    private final Long fileSize;
    private final Long sortSize;
    private ItemStatus status;
    private int progress;
    private String errorMessage;
    private int retryCount;
    private String rootRowId;

    /**
     * Creates a file leaf row without size metadata.
     *
     * @param fileName display name shown in the queue UI
     * @param rowId    leaf row id; sync uses full local save path, backup uses
     *                 file path, export uses full destination export path
     */
    public FileQueueItem(String fileName, String rowId) {
        this(fileName, rowId, null, null);
    }

    /**
     * Creates a file leaf row where display size and sort size are the same value.
     *
     * @param fileName display name shown in the queue UI
     * @param rowId    leaf row id; sync uses full local save path, backup uses
     *                 file path, export uses full destination export path
     * @param fileSize optional size displayed by the queue UI
     */
    public FileQueueItem(String fileName, String rowId, Long fileSize) {
        this(fileName, rowId, fileSize, fileSize);
    }

    /**
     * Creates a file leaf row with separate display and ordering sizes.
     *
     * @param fileName display name shown in the queue UI
     * @param rowId    leaf row id; sync uses full local save path, backup uses
     *                 file path, export uses full destination export path
     * @param fileSize optional size displayed by the queue UI
     * @param sortSize optional size used when ordering rows
     */
    public FileQueueItem(String fileName, String rowId, Long fileSize, Long sortSize) {
        this.fileName = fileName;
        this.rowId = rowId;
        this.fileSize = fileSize;
        this.sortSize = sortSize;
        this.status = ItemStatus.QUEUED;
        this.progress = 0;
        this.retryCount = 0;
    }

    /**
     * Returns the text displayed for this file leaf row.
     *
     * @return user-facing file name; not used as row identity
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Updates the displayed file name without changing row identity.
     *
     * @param fileName user-facing file name
     */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /**
     * Returns the stable id of this file leaf row.
     *
     * @return sync full local save path, backup file path, or full destination
     *         export path
     */
    public String getRowId() {
        return rowId;
    }

    /**
     * Returns the optional display size for this file leaf row.
     *
     * @return file size in bytes, or null when unavailable
     */
    public Long getFileSize() {
        return fileSize;
    }

    /**
     * Returns the optional ordering size for this file leaf row.
     *
     * @return sort size in bytes, or null when unavailable
     */
    public Long getSortSize() {
        return sortSize;
    }

    /**
     * Returns the current status displayed for this file leaf row.
     *
     * @return current leaf row status
     */
    public ItemStatus getStatus() {
        return status;
    }

    /**
     * Sets the current status displayed for this file leaf row.
     *
     * @param status current leaf row status
     */
    public void setStatus(ItemStatus status) {
        this.status = status;
    }

    /**
     * Returns the current progress displayed for this file leaf row.
     *
     * @return completion percentage from 0 to 100
     */
    public int getProgress() {
        return progress;
    }

    /**
     * Sets the current progress displayed for this file leaf row.
     *
     * @param progress completion percentage from 0 to 100
     */
    public void setProgress(int progress) {
        this.progress = progress;
    }

    /**
     * Returns the reason shown for failed, deferred, or skipped states.
     *
     * @return message or i18n key displayed by the queue UI
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Sets the reason shown for failed, deferred, or skipped states.
     *
     * @param errorMessage message or i18n key displayed by the queue UI
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Returns how many retry attempts have been recorded for this file leaf row.
     *
     * @return retry count maintained by the queue tracker
     */
    public int getRetryCount() {
        return retryCount;
    }

    /**
     * Records one more retry attempt for this file leaf row.
     */
    public void incrementRetryCount() {
        this.retryCount++;
    }

    /**
     * Returns the owning sync root row id when this file belongs to the sync queue.
     *
     * @return camera id for sync leaf rows, or null for non-sync rows
     */
    public String getRootRowId() {
        return rootRowId;
    }

    /**
     * Stores the owning sync root row id for this file row.
     *
     * @param rootRowId camera id of the sync root row that owns this leaf row
     */
    public void setRootRowId(String rootRowId) {
        this.rootRowId = rootRowId;
    }
}
