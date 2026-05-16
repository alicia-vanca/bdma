package com.app.common.modules.queuemanager.dtos;

import com.app.common.modules.queuemanager.enums.ItemStatus;
import com.app.common.modules.queuemanager.enums.QueueType;

/**
 * Represents a file in any queue (sync or backup).
 */
public class FileQueueItem {
    private String fileName;
    private final String filePath;
    private final QueueType queueType;
    private final Long fileSize;
    private final Long sortSize;
    private ItemStatus status;
    private int progress;
    private String errorMessage;
    private int retryCount;
    private String deviceId;

    public FileQueueItem(String fileName, String filePath, QueueType queueType) {
        this(fileName, filePath, queueType, null, null);
    }

    public FileQueueItem(String fileName, String filePath, QueueType queueType, Long fileSize) {
        this(fileName, filePath, queueType, fileSize, fileSize);
    }

    public FileQueueItem(String fileName, String filePath, QueueType queueType, Long fileSize, Long sortSize) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.queueType = queueType;
        this.fileSize = fileSize;
        this.sortSize = sortSize;
        this.status = ItemStatus.QUEUED;
        this.progress = 0;
        this.retryCount = 0;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public QueueType getQueueType() {
        return queueType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public Long getSortSize() {
        return sortSize;
    }

    public ItemStatus getStatus() {
        return status;
    }

    public void setStatus(ItemStatus status) {
        this.status = status;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }
}
