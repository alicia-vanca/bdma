package com.app.common.modules.queuemanager.trackers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.app.common.modules.dataexport.workers.DataExportWorker;
import com.app.common.modules.queuemanager.dtos.ExportDirectoryQueueItem;
import com.app.common.modules.queuemanager.dtos.FileQueueItem;
import com.app.common.modules.queuemanager.enums.ItemStatus;
import com.app.common.modules.queuemanager.enums.QueueType;
import com.app.common.modules.queuemanager.events.QueueStatusChangedEvent;

/**
 * Tracks export queue progress grouped by destination directory.
 */
@Component
public class ExportProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(ExportProgressTracker.class);

    private final Map<String, FileQueueItem> exportFiles = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Path, ExportDirectoryQueueItem> directories = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ApplicationEventPublisher eventPublisher;

    public ExportProgressTracker(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Add a file to export queue tracking.
     */
    public void addFile(String exportPathId, String fileName) {
        addFile(exportPathId, fileName, null, null);
    }

    /**
     * Add a file to export queue tracking with size metadata for stable ordering.
     */
    public void addFile(String exportPathId, String fileName, Long fileSize) {
        addFile(exportPathId, fileName, fileSize, fileSize);
    }

    /**
     * Add a file to export queue tracking with separate display and sort sizes.
     */
    public void addFile(String exportPathId, String fileName, Long fileSize, Long sortSize) {
        FileQueueItem existing = exportFiles.get(exportPathId);
        if (existing != null) {
            existing.setStatus(ItemStatus.QUEUED);
            existing.setProgress(0);
            existing.setErrorMessage(null);
            ExportDirectoryQueueItem directory = directoryFor(exportPathId);
            directory.sortFiles();
            refreshDirectory(directory);
            publishEvent(exportPathId, "File re-queued for export");
            return;
        }

        FileQueueItem item = new FileQueueItem(fileName, exportPathId, QueueType.EXPORT, fileSize, sortSize);
        exportFiles.put(exportPathId, item);
        directoryFor(exportPathId).addFile(item);
        refreshDirectory(item);
        publishEvent(exportPathId, "File added to export queue");
    }

    /**
     * Mark file as currently exporting.
     */
    public void markProcessing(String exportPathId) {
        FileQueueItem item = exportFiles.get(exportPathId);
        if (item != null) {
            item.setStatus(ItemStatus.PROCESSING);
            refreshDirectory(item);
            publishEvent(exportPathId, "Export in progress");
        }
    }

    /**
     * Update export progress percentage.
     */
    public void updateProgress(String exportPathId, int progress) {
        FileQueueItem item = exportFiles.get(exportPathId);
        if (item != null) {
            item.setProgress(progress);
            publishEvent(exportPathId, "Export progress: " + progress + "%");
        }
    }

    /**
     * Mark file export as completed.
     */
    public void markCompleted(String exportPathId) {
        FileQueueItem item = exportFiles.get(exportPathId);
        if (item != null) {
            item.setStatus(ItemStatus.COMPLETED);
            item.setProgress(100);
            refreshDirectory(item);
            publishEvent(exportPathId, "Export completed");
        }
    }

    /**
     * Replace the displayed export file name after conflict resolution chooses the
     * final target name. Status and progress remain untouched.
     */
    public void renameFile(String exportPathId, String exportedFileName) {
        FileQueueItem item = exportFiles.get(exportPathId);
        if (item != null && exportedFileName != null && !exportedFileName.isBlank()
                && !exportedFileName.equals(item.getFileName())) {
            item.setFileName(exportedFileName);
            publishEvent(exportPathId, "Export file renamed");
        }
    }

    /**
     * Mark file export as failed.
     */
    public void markFailed(String exportPathId, String errorMessage) {
        FileQueueItem item = exportFiles.get(exportPathId);
        if (item != null) {
            item.setStatus(ItemStatus.FAILED);
            item.setErrorMessage(errorMessage);
            refreshDirectory(item);
            publishEvent(exportPathId, "Export failed: " + errorMessage);
        } else {
            log.warn("Export: markFailed — item not found for export path id: {}", exportPathId);
        }
    }

    /**
     * Mark file export as deferred — export drive unavailable, waiting for
     * recovery.
     */
    public void markDeferred(String exportPathId, String reason) {
        FileQueueItem item = exportFiles.get(exportPathId);
        if (item != null) {
            item.setStatus(ItemStatus.DEFERRED);
            item.setErrorMessage(reason);
            refreshDirectory(item);
            publishEvent(exportPathId, "Export deferred: " + reason);
        } else {
            log.warn("Export: markDeferred — item not found for export path id: {}", exportPathId);
        }
    }

    /**
     * Mark file export as skipped (e.g. user chose to skip a conflicting file).
     * The reason is stored as an i18n key so the queue UI can translate it.
     */
    public void markSkipped(String exportPathId, String reason) {
        FileQueueItem item = exportFiles.get(exportPathId);
        if (item != null) {
            item.setStatus(ItemStatus.SKIPPED);
            item.setErrorMessage(reason);
            refreshDirectory(item);
            publishEvent(exportPathId, "Export skipped: " + reason);
        }
    }

    /**
     * Get all files in export queue.
     */
    public List<FileQueueItem> getAllFiles() {
        return new ArrayList<>(exportFiles.values());
    }

    /**
     * Get all export destination directories in insertion order.
     */
    public List<ExportDirectoryQueueItem> getAllDirectories() {
        return new ArrayList<>(directories.values());
    }

    /**
     * Marks an export directory with the final counts reported by the worker. The
     * worker owns directory execution, so its finished totals are the authoritative
     * aggregate result shown by the queue UI.
     */
    public void markDirectoryFinished(Path exportDir, int total, int passed, int failed, Runnable retryAction) {
        if (exportDir == null) {
            return;
        }

        Path normalizedDir = exportDir.toAbsolutePath().normalize();
        ExportDirectoryQueueItem directory = directories.computeIfAbsent(normalizedDir, ExportDirectoryQueueItem::new);
        directory.setTotal(total);
        directory.setPassed(passed);
        directory.setFailed(failed);
        directory.setRetryAction(failed > 0 ? retryAction : null);
        directory.setStatus(failed > 0 ? ItemStatus.COMPLETED_WITH_ERRORS : ItemStatus.COMPLETED);
        publishEvent(normalizedDir.toString(), "Export directory completed");
    }

    /**
     * Clears completed file rows and summary counts before a finished directory is
     * used for a new export run.
     */
    public void resetDirectoryForNewRun(Path exportDir) {
        if (exportDir == null) {
            return;
        }

        Path normalizedDir = exportDir.toAbsolutePath().normalize();
        exportFiles.entrySet().removeIf(entry -> normalizedDir.equals(extractExportDir(entry.getKey())));

        ExportDirectoryQueueItem directory = directories.computeIfAbsent(normalizedDir, ExportDirectoryQueueItem::new);
        directory.getFiles().clear();
        directory.setTotal(0);
        directory.setPassed(0);
        directory.setFailed(0);
        directory.setRetryAction(null);
        directory.setStatus(ItemStatus.QUEUED);
        publishEvent(normalizedDir.toString(), "Export directory reset for new run");
    }

    /**
     * Moves a file entry when the export directory changes after storage recovery.
     * Creates a new item with the updated export path so the queue UI groups it
     * under the new folder, and resets status to QUEUED so the retry is visible as
     * "waiting" rather than inheriting the previous error state.
     */
    public void moveFileToExportPath(String oldExportPathId, String newExportPathId) {
        FileQueueItem old = exportFiles.remove(oldExportPathId);
        if (old != null) {
            ExportDirectoryQueueItem oldDirectory = directoryFor(oldExportPathId);
            oldDirectory.removeFile(old);
            removeEmptyUnfinishedDirectory(oldDirectory);

            FileQueueItem item = new FileQueueItem(old.getFileName(), newExportPathId, QueueType.EXPORT,
                    old.getFileSize(), old.getSortSize());
            exportFiles.put(newExportPathId, item);
            ExportDirectoryQueueItem newDirectory = directoryFor(newExportPathId);
            newDirectory.addFile(item);
            refreshDirectory(newDirectory);
            publishEvent(newExportPathId, "Export file moved to new directory");
        } else {
            log.warn("Export: moveFileToExportPath — item not found for export path id: {} (new export path id: {})",
                    oldExportPathId, newExportPathId);
        }
    }

    /**
     * Removes transient recovery directories that never completed any file, so the
     * queue history does not show zero-count rows for unusable replacement folders.
     */
    private void removeEmptyUnfinishedDirectory(ExportDirectoryQueueItem directory) {
        if (directory.getFiles().isEmpty() && directory.getPassed() == 0 && directory.getFailed() == 0) {
            directories.remove(directory.getExportDir());
            publishEvent(directory.getExportDir().toString(), "Empty export directory removed");
            return;
        }

        refreshDirectory(directory);
    }

    /**
     * Get specific export file by export path id.
     */
    public FileQueueItem getFile(String exportPathId) {
        return exportFiles.get(exportPathId);
    }

    /**
     * Clear all export tracking.
     */
    public void clear() {
        exportFiles.clear();
        directories.clear();
        publishEvent(null, "Export queue cleared");
    }

    private ExportDirectoryQueueItem directoryFor(String exportPathId) {
        Path exportDir = extractExportDir(exportPathId);
        return directories.computeIfAbsent(exportDir, ExportDirectoryQueueItem::new);
    }

    private Path extractExportDir(String exportPathId) {
        String prefix = DataExportWorker.FILE_KEY_PREFIX;
        if (exportPathId != null && exportPathId.startsWith(prefix)) {
            Path parent = Path.of(exportPathId.substring(prefix.length())).getParent();
            if (parent != null) {
                return parent.toAbsolutePath().normalize();
            }
        }
        return Path.of(exportPathId != null ? exportPathId : "").toAbsolutePath().normalize();
    }

    private void refreshDirectory(FileQueueItem item) {
        refreshDirectory(directoryFor(item.getFilePath()));
    }

    private void refreshDirectory(ExportDirectoryQueueItem directory) {
        List<FileQueueItem> files = directory.getFiles();
        int total = files.size();
        int passed = (int) files.stream().filter(file -> file.getStatus() == ItemStatus.COMPLETED).count();
        int failed = (int) files.stream().filter(file -> file.getStatus() == ItemStatus.FAILED).count();

        directory.setTotal(total);
        directory.setPassed(passed);
        directory.setFailed(failed);
        directory.setStatus(computeDirectoryStatus(files));
    }

    private ItemStatus computeDirectoryStatus(List<FileQueueItem> files) {
        if (files.stream().anyMatch(file -> file.getStatus() == ItemStatus.PROCESSING)) {
            return ItemStatus.PROCESSING;
        }
        if (files.stream().anyMatch(file -> file.getStatus() == ItemStatus.QUEUED
                || file.getStatus() == ItemStatus.DEFERRED)) {
            return ItemStatus.QUEUED;
        }
        if (files.stream().anyMatch(file -> file.getStatus() == ItemStatus.FAILED)) {
            return ItemStatus.COMPLETED_WITH_ERRORS;
        }
        return ItemStatus.COMPLETED;
    }

    private void publishEvent(String exportPathId, String message) {
        eventPublisher.publishEvent(new QueueStatusChangedEvent(QueueType.EXPORT, exportPathId, message));
    }
}
