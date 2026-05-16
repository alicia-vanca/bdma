package com.app.common.modules.queuemanager.services;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;

import com.app.common.modules.queuemanager.dtos.DeviceQueueItem;
import com.app.common.modules.queuemanager.dtos.ExportDirectoryQueueItem;
import com.app.common.modules.queuemanager.dtos.FileQueueItem;
import com.app.common.modules.queuemanager.trackers.BackupProgressTracker;
import com.app.common.modules.queuemanager.trackers.ExportProgressTracker;
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
    private final ExportProgressTracker exportTracker;

    public QueueManagerService(SyncProgressTracker syncTracker,
            BackupProgressTracker backupTracker,
            ExportProgressTracker exportTracker) {
        this.syncTracker = syncTracker;
        this.backupTracker = backupTracker;
        this.exportTracker = exportTracker;
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

    // ── Export Operations ────────────────────────────────────────────────────

    /**
     * Add file to export queue.
     */
    public void addFileToExportTracker(String exportPathId, String fileName) {
        exportTracker.addFile(exportPathId, fileName);
    }

    /**
     * Add file to export queue with size metadata used for directory ordering.
     */
    public void addFileToExportTracker(String exportPathId, String fileName, Long fileSize) {
        exportTracker.addFile(exportPathId, fileName, fileSize);
    }

    /**
     * Add file to export queue with separate display and sort sizes.
     */
    public void addFileToExportTracker(String exportPathId, String fileName, Long fileSize, Long sortSize) {
        exportTracker.addFile(exportPathId, fileName, fileSize, sortSize);
    }

    /**
     * Mark export file as processing.
     */
    public void markExportFileProcessing(String exportPathId) {
        exportTracker.markProcessing(exportPathId);
    }

    /**
     * Update export file progress.
     */
    public void updateExportFileProgress(String exportPathId, int progress) {
        exportTracker.updateProgress(exportPathId, progress);
    }

    /**
     * Mark export file as completed.
     */
    public void markExportFileCompleted(String exportPathId) {
        exportTracker.markCompleted(exportPathId);
    }

    /**
     * Replace the displayed export file name after conflict resolution.
     */
    public void renameExportFile(String exportPathId, String exportedFileName) {
        exportTracker.renameFile(exportPathId, exportedFileName);
    }

    /**
     * Mark export file as failed.
     */
    public void markExportFileFailed(String exportPathId, String errorMessage) {
        exportTracker.markFailed(exportPathId, errorMessage);
    }

    /**
     * Mark export file as deferred — export drive unavailable, waiting for
     * recovery.
     */
    public void markExportFileDeferred(String exportPathId, String reason) {
        exportTracker.markDeferred(exportPathId, reason);
    }

    /**
     * Mark export file as skipped (e.g. user chose to skip a conflict).
     * The reason is stored as an i18n key and shown in the queue UI.
     */
    public void markExportFileSkipped(String exportPathId, String reason) {
        exportTracker.markSkipped(exportPathId, reason);
    }

    /**
     * Move an export file entry when the export directory changes after storage
     * recovery.
     */
    public void moveExportFile(String oldExportPathId, String newExportPathId) {
        exportTracker.moveFileToExportPath(oldExportPathId, newExportPathId);
    }

    /**
     * Mark an export directory as finished with worker-provided aggregate counts.
     */
    public void markExportDirectoryFinished(Path exportDir, int total, int passed, int failed, Runnable retryAction) {
        exportTracker.markDirectoryFinished(exportDir, total, passed, failed, retryAction);
    }

    /**
     * Reset a finished export directory before a new run starts for that directory.
     */
    public void resetExportDirectoryForNewRun(Path exportDir) {
        exportTracker.resetDirectoryForNewRun(exportDir);
    }

    /**
     * Get all files in export queue.
     */
    public List<FileQueueItem> getAllTrackingExportFiles() {
        return exportTracker.getAllFiles();
    }

    /**
     * Get export destination directories with aggregate counts.
     */
    public List<ExportDirectoryQueueItem> getAllTrackingExportDirectories() {
        return exportTracker.getAllDirectories();
    }

    // ── General Operations ───────────────────────────────────────────────────

    /**
     * Clear all queues.
     */
    public void clearAll() {
        syncTracker.clear();
        backupTracker.clear();
        exportTracker.clear();
    }
}
