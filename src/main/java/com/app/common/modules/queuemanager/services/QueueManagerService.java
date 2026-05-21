package com.app.common.modules.queuemanager.services;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;

import com.app.common.dtos.SyncContext;
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
     * Add device to sync queue using the full context required by queue actions.
     *
     * @param syncContext sync request context containing camera and hardware
     *                    identifiers
     */
    public void addDeviceToSyncTracker(SyncContext syncContext) {
        syncTracker.addDevice(syncContext);
    }

    /**
     * Adds a sync file leaf row under a device root row.
     *
     * @param rootRowId camera id of the sync device root row
     * @param fileName  display name shown for the file row
     * @param rowId     sync leaf row id; this is the full local save path
     */
    public void addFileToSyncTracker(String rootRowId, String fileName, String rowId) {
        syncTracker.addFileToDevice(rootRowId, fileName, rowId);
    }

    /**
     * Marks a sync file leaf row as skipped.
     *
     * @param rootRowId camera id of the sync device root row
     * @param rowId     sync leaf row id; this is the full local save path
     */
    public void markSyncFileSkipped(String rootRowId, String rowId) {
        syncTracker.markFileSkipped(rootRowId, rowId);
    }

    /**
     * Marks a sync file leaf row as currently processing.
     *
     * @param rootRowId camera id of the sync device root row
     * @param rowId     sync leaf row id; this is the full local save path
     */
    public void markSyncFileProcessing(String rootRowId, String rowId) {
        syncTracker.markFileProcessing(rootRowId, rowId);
    }

    /**
     * Updates progress for a sync file leaf row.
     *
     * @param rootRowId camera id of the sync device root row
     * @param rowId     sync leaf row id; this is the full local save path
     * @param progress  completion percentage from 0 to 100
     */
    public void updateSyncFileProgress(String rootRowId, String rowId, int progress) {
        syncTracker.updateFileProgress(rootRowId, rowId, progress);
    }

    /**
     * Marks a sync file leaf row as completed.
     *
     * @param rootRowId camera id of the sync device root row
     * @param rowId     sync leaf row id; this is the full local save path
     */
    public void markSyncFileCompleted(String rootRowId, String rowId) {
        syncTracker.markFileCompleted(rootRowId, rowId);
    }

    /**
     * Marks a sync file leaf row as failed.
     *
     * @param rootRowId    camera id of the sync device root row
     * @param rowId        sync leaf row id; this is the full local save path
     * @param errorMessage reason displayed in the queue UI
     */
    public void markSyncFileFailed(String rootRowId, String rowId, String errorMessage) {
        syncTracker.markFileFailed(rootRowId, rowId, errorMessage);
    }

    /**
     * Mark device sync as processing.
     */
    public void markDeviceSyncProcessing(String rootRowId) {
        syncTracker.markDeviceProcessing(rootRowId);
    }

    /**
     * Mark device sync as completed.
     */
    public void markDeviceSyncCompleted(String rootRowId, int total, int passed, int failed) {
        syncTracker.markDeviceCompleted(rootRowId, total, passed, failed);
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
    public DeviceQueueItem getTrackingSyncDevice(String rootRowId) {
        return syncTracker.getDevice(rootRowId);
    }

    /**
     * Get all files for a device in sync queue.
     */
    public List<FileQueueItem> getTrackingSyncFiles(String rootRowId) {
        return syncTracker.getDeviceFiles(rootRowId);
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
     * Move an export file entry when the export directory changes after storage
     * recovery.
     */
    public void moveExportFile(String oldExportPathId, String newExportPathId) {
        exportTracker.moveFileToExportPath(oldExportPathId, newExportPathId);
    }

    /**
     * Mark an export directory as finished with worker-provided aggregate counts.
     */
    public void markExportDirectoryFinished(Path exportDir, int total, int passed, int failed) {
        exportTracker.markDirectoryFinished(exportDir, total, passed, failed);
    }

    /**
     * Reset a finished export directory before a new run starts for that directory.
     */
    public void resetExportDirectoryForNewRun(Path exportDir) {
        exportTracker.resetDirectoryForNewRun(exportDir);
    }

    /**
     * Get export destination directories with aggregate counts.
     */
    public List<ExportDirectoryQueueItem> getAllTrackingExportDirectories() {
        return exportTracker.getAllDirectories();
    }

    /**
     * Get a specific export file.
     */
    public FileQueueItem getTrackingExportFile(String exportPathId) {
        return exportTracker.getFile(exportPathId);
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
