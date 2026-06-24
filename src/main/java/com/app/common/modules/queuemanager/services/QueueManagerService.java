package com.app.common.modules.queuemanager.services;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.app.common.dtos.SyncContext;
import com.app.common.modules.queuemanager.dtos.DeviceQueueItem;
import com.app.common.modules.queuemanager.dtos.ExportDirectoryQueueItem;
import com.app.common.modules.queuemanager.dtos.FileQueueItem;
import com.app.common.modules.queuemanager.dtos.QueueProgressSummary;
import com.app.common.modules.queuemanager.enums.ItemStatus;
import com.app.common.modules.queuemanager.events.QueueProgressChangedEvent;
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
    private final ApplicationEventPublisher eventPublisher;

    private final Object exportProgressLock = new Object();
    private int exportProgressTotal;
    private int exportProgressDone;
    private boolean exportProgressRunning;

    public QueueManagerService(SyncProgressTracker syncTracker,
            BackupProgressTracker backupTracker,
            ExportProgressTracker exportTracker,
            ApplicationEventPublisher eventPublisher) {
        this.syncTracker = syncTracker;
        this.backupTracker = backupTracker;
        this.exportTracker = exportTracker;
        this.eventPublisher = eventPublisher;
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
     * Marks a sync file leaf row as currently processing.
     *
     * @param rootRowId camera id of the sync device root row
     * @param rowId     sync leaf row id; this is the full local save path
     */
    public void markSyncFileProcessing(String rootRowId, String rowId) {
        syncTracker.markFileProcessing(rootRowId, rowId);
    }

    /**
     * Marks a sync file leaf row as completed.
     *
     * @param rootRowId camera id of the sync device root row
     * @param rowId     sync leaf row id; this is the full local save path
     */
    public void markSyncFileCompleted(String rootRowId, String rowId) {
        syncTracker.markFileCompleted(rootRowId, rowId);
        publishSyncQueueProgressChanged(rootRowId);
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
        publishSyncQueueProgressChanged(rootRowId);
    }

    /**
     * Mark device sync as processing.
     */
    public void markDeviceSyncProcessing(String rootRowId) {
        syncTracker.markDeviceProcessing(rootRowId);
        publishSyncQueueProgressChanged(rootRowId);
    }

    /**
     * Updates root counters during sync so queue UI does not wait for completion.
     */
    public void updateDeviceSyncProgress(String rootRowId, int total, int passed, int failed) {
        if (!hasTrackedSyncDevice(rootRowId)) {
            return;
        }
        syncTracker.updateDeviceProgress(rootRowId, total, passed, failed);
        publishSyncQueueProgressChanged(rootRowId);
    }

    /**
     * Mark device sync as completed.
     */
    public void markDeviceSyncCompleted(String rootRowId, int total, int passed, int failed) {
        if (!hasTrackedSyncDevice(rootRowId)) {
            return;
        }
        syncTracker.markDeviceCompleted(rootRowId, total, passed, failed);
        publishSyncQueueProgressChanged(rootRowId, true);
    }

    /**
     * Remove device from sync tracker.
     */
    public void removeDeviceFromSyncTracker(String rootRowId) {
        if (!hasTrackedSyncDevice(rootRowId)) {
            return;
        }
        syncTracker.removeDevice(rootRowId);
        publishSyncQueueProgressChanged(rootRowId, false);
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

    private boolean hasTrackedSyncDevice(String rootRowId) {
        return rootRowId != null && !rootRowId.isBlank() && syncTracker.getDevice(rootRowId) != null;
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
     * All export files were added to the tracker.
     * Notify listeners of the update.
     *
     * @param acceptedCount number of logical files accepted into the export queue
     */
    public void allExportFilesAddedToTracker(int acceptedCount) {
        if (acceptedCount <= 0) {
            return;
        }
        synchronized (exportProgressLock) {
            if (!exportProgressRunning && exportProgressDone >= exportProgressTotal) {
                exportProgressTotal = 0;
                exportProgressDone = 0;
            }
            exportProgressTotal += acceptedCount;
            exportProgressRunning = true;
        }
        publishExportQueueProgressChanged();
    }

    /**
     * Records one accepted file reaching a final state and advances compact
     * export progress: copied, skipped, already-existing, failed, or canceled.
     */
    public void finishExportForSingleFile() {
        synchronized (exportProgressLock) {
            if (exportProgressTotal <= 0) {
                return;
            }
            exportProgressDone = Math.min(exportProgressTotal, exportProgressDone + 1);
            exportProgressRunning = exportProgressDone < exportProgressTotal;
        }
        publishExportQueueProgressChanged();
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
     * Check SyncProgressTracker for running processes
     */
    public boolean hasRunningSyncTask() {
        return syncTracker.getAllDevices()
                .stream()
                .anyMatch(device -> device.getStatus() == ItemStatus.PROCESSING);
    }

    /**
     * Check ExportProgressTracker for running processes
     */
    public boolean hasRunningExportTask() {
        return exportTracker.getAllDirectories()
                .stream()
                .anyMatch(directory -> directory.getStatus() == ItemStatus.PROCESSING);
    }

    /**
     * Get a specific export file.
     */
    public FileQueueItem getTrackingExportFile(String exportPathId) {
        return exportTracker.getFile(exportPathId);
    }

    private QueueProgressSummary buildSyncProgressSummary(DeviceQueueItem device, boolean complete) {
        List<FileQueueItem> files = snapshotFiles(device.getFiles());
        int total = device.getTotal();
        int finished = files.isEmpty()
                ? device.getPassed() + device.getFailed()
                : countFinishedFiles(files);
        return new QueueProgressSummary(QueueProgressSummary.Kind.SYNC, device.getDeviceName(), total, finished,
                complete);
    }

    private Optional<QueueProgressSummary> findExportProgressSummary() {
        synchronized (exportProgressLock) {
            if (exportProgressTotal <= 0) {
                return Optional.empty();
            }
            return Optional.of(new QueueProgressSummary(
                    QueueProgressSummary.Kind.EXPORT,
                    "",
                    exportProgressTotal,
                    exportProgressDone,
                    !exportProgressRunning && exportProgressDone >= exportProgressTotal));
        }
    }

    private List<FileQueueItem> snapshotFiles(List<FileQueueItem> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }

        return new ArrayList<>(files);
    }

    private int countFinishedFiles(List<FileQueueItem> files) {
        return (int) files.stream()
                .filter(file -> isFinishedStatus(file.getStatus()))
                .count();
    }

    private boolean isFinishedStatus(ItemStatus status) {
        return status == ItemStatus.COMPLETED
                || status == ItemStatus.COMPLETED_WITH_ERRORS
                || status == ItemStatus.FAILED
                || status == ItemStatus.SKIPPED
                || status == ItemStatus.CANCELLED;
    }

    private void publishSyncQueueProgressChanged(String rootRowId) {
        publishSyncQueueProgressChanged(rootRowId, false);
    }

    private void publishSyncQueueProgressChanged(String rootRowId, boolean complete) {
        DeviceQueueItem device = syncTracker.getDevice(rootRowId);
        if (device == null) {
            return;
        }
        eventPublisher.publishEvent(new QueueProgressChangedEvent(buildSyncProgressSummary(device, complete)));
    }

    private void publishExportQueueProgressChanged() {
        findExportProgressSummary()
                .ifPresent(summary -> eventPublisher.publishEvent(new QueueProgressChangedEvent(summary)));
    }

    private void clearExportProgress() {
        synchronized (exportProgressLock) {
            exportProgressTotal = 0;
            exportProgressDone = 0;
            exportProgressRunning = false;
        }
    }

    // ── General Operations ───────────────────────────────────────────────────

    /**
     * Clear all queues.
     */
    public void clearAll() {
        syncTracker.clear();
        backupTracker.clear();
        exportTracker.clear();
        clearExportProgress();
    }
}
