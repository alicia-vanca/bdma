package com.app.common.modules.queuemanager.services;

import java.util.List;

import org.springframework.stereotype.Service;

import com.app.common.modules.queuemanager.dtos.DeviceQueueItem;
import com.app.common.modules.queuemanager.dtos.FileQueueItem;
import com.app.common.modules.queuemanager.trackers.BackupProgressTracker;
import com.app.common.modules.queuemanager.trackers.SyncProgressTracker;

/**
 * Centralized queue management service that aggregates sync and backup
 * operations.
 * Provides unified interface for tracking all queue operations across the
 * application.
 */
@Service
public class QueueManagerService {

    private final SyncProgressTracker syncTracker;
    private final BackupProgressTracker backupTracker;

    public QueueManagerService(SyncProgressTracker syncTracker,
            BackupProgressTracker backupTracker) {
        this.syncTracker = syncTracker;
        this.backupTracker = backupTracker;
    }

    // ── Sync Operations ──────────────────────────────────────────────────────

    /**
     * Add device to sync queue.
     */
    public void addDeviceToSyncTracker(String hardwareId, String deviceName) {
        syncTracker.addDevice(hardwareId, deviceName);
    }

    /**
     * Add file to device's sync list.
     */
    public void addFileToSyncTracker(String hardwareId, String fileName, String filePath) {
        syncTracker.addFileToDevice(hardwareId, fileName, filePath);
    }

    /**
     * Mark file as skipped during sync.
     */
    public void markSyncFileSkipped(String hardwareId, String filePath) {
        syncTracker.markFileSkipped(hardwareId, filePath);
    }

    /**
     * Mark file as currently syncing.
     */
    public void markSyncFileProcessing(String hardwareId, String filePath) {
        syncTracker.markFileProcessing(hardwareId, filePath);
    }

    /**
     * Update sync file progress.
     */
    public void updateSyncFileProgress(String hardwareId, String filePath, int progress) {
        syncTracker.updateFileProgress(hardwareId, filePath, progress);
    }

    /**
     * Mark sync file as completed.
     */
    public void markSyncFileCompleted(String hardwareId, String filePath) {
        syncTracker.markFileCompleted(hardwareId, filePath);
    }

    /**
     * Mark sync file as failed.
     */
    public void markSyncFileFailed(String hardwareId, String filePath, String errorMessage) {
        syncTracker.markFileFailed(hardwareId, filePath, errorMessage);
    }

    /**
     * Mark device sync as processing.
     */
    public void markDeviceSyncProcessing(String hardwareId) {
        syncTracker.markDeviceProcessing(hardwareId);
    }

    /**
     * Mark device sync as completed.
     */
    public void markDeviceSyncCompleted(String hardwareId, int total, int passed, int failed) {
        syncTracker.markDeviceCompleted(hardwareId, total, passed, failed);
    }

    /**
     * Get all devices in sync queue.
     */
    public List<DeviceQueueItem> getAllTrackingSyncDevices() {
        return syncTracker.getAllDevices();
    }

    /**
     * Get specific device from sync queue.
     */
    public DeviceQueueItem getTrackingSyncDevice(String hardwareId) {
        return syncTracker.getDevice(hardwareId);
    }

    /**
     * Get all files for a device in sync queue.
     */
    public List<FileQueueItem> getTrackingSyncFiles(String hardwareId) {
        return syncTracker.getDeviceFiles(hardwareId);
    }

    // ── Backup Operations ────────────────────────────────────────────────────

    /**
     * Add file to backup queue.
     */
    public void addFileToBackupTracker(String filePath, String fileName) {
        backupTracker.addFile(filePath, fileName);
    }

    /**
     * Mark backup file as processing.
     */
    public void markBackupFileProcessing(String filePath) {
        backupTracker.markProcessing(filePath);
    }

    /**
     * Update backup file progress.
     */
    public void updateBackupFileProgress(String filePath, int progress) {
        backupTracker.updateProgress(filePath, progress);
    }

    /**
     * Mark backup file as completed.
     */
    public void markBackupFileCompleted(String filePath) {
        backupTracker.markCompleted(filePath);
    }

    /**
     * Mark backup file as failed.
     */
    public void markBackupFileFailed(String filePath, String errorMessage) {
        backupTracker.markFailed(filePath, errorMessage);
    }

    /**
     * Mark backup file as deferred until next auto-backup scan.
     */
    public void markBackupFileDeferred(String filePath, String reason) {
        backupTracker.markDeferred(filePath, reason);
    }

    /**
     * Get all files in backup queue.
     */
    public List<FileQueueItem> getAllTrackingBackupFiles() {
        return backupTracker.getAllFiles();
    }

    /**
     * Get specific backup file.
     */
    public FileQueueItem getTrackingBackupFile(String filePath) {
        return backupTracker.getFile(filePath);
    }

    // ── General Operations ───────────────────────────────────────────────────

    /**
     * Clear all queues.
     */
    public void clearAll() {
        syncTracker.clear();
        backupTracker.clear();
    }
}
