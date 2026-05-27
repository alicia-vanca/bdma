package com.app.common.modules.queuemanager.trackers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

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

    private final Map<String, FileQueueItem> exportFiles = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Path, ExportDirectoryQueueItem> directories = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ApplicationEventPublisher eventPublisher;

    public ExportProgressTracker(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
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
            publishRowChangedEvent(exportPathId, directory.getExportDir().toString(), "File re-queued for export");
            return;
        }

        FileQueueItem item = new FileQueueItem(fileName, exportPathId, fileSize, sortSize);
        exportFiles.put(exportPathId, item);
        ExportDirectoryQueueItem directory = directoryFor(exportPathId);
        directory.addFile(item);
        refreshDirectory(directory);
        publishRowChangedEvent(exportPathId, directory.getExportDir().toString(), "File added to export queue");
    }

    /**
     * Mark file as currently exporting.
     */
    public void markProcessing(String exportPathId) {
        FileQueueItem item = exportFiles.get(exportPathId);
        if (item != null) {
            item.setStatus(ItemStatus.PROCESSING);
            ExportDirectoryQueueItem directory = refreshDirectory(item);
            publishRowChangedEvent(exportPathId, directory.getExportDir().toString(), "Export in progress");
        }
    }

    /**
     * Update export progress percentage.
     */
    public void updateProgress(String exportPathId, int progress) {
        FileQueueItem item = exportFiles.get(exportPathId);
        if (item != null) {
            item.setProgress(progress);
            publishRowChangedEvent(exportPathId, directoryFor(exportPathId).getExportDir().toString(),
                    "Export progress: " + progress + "%");
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
            ExportDirectoryQueueItem directory = refreshDirectory(item);
            publishCounterChangedEvent(exportPathId, directory.getExportDir().toString(), "Export completed");
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
            publishRowChangedEvent(exportPathId, directoryFor(exportPathId).getExportDir().toString(),
                    "Export file renamed");
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
            ExportDirectoryQueueItem directory = refreshDirectory(item);
            publishCounterChangedEvent(exportPathId, directory.getExportDir().toString(),
                    "Export failed: " + errorMessage);
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
            ExportDirectoryQueueItem directory = refreshDirectory(item);
            publishRowChangedEvent(exportPathId, directory.getExportDir().toString(), "Export deferred: " + reason);
        }
    }

    /**
     * Get all export destination directories in insertion order.
     */
    public List<ExportDirectoryQueueItem> getAllDirectories() {
        return new ArrayList<>(directories.values());
    }

    /**
     * Get a specific export file.
     */
    public FileQueueItem getFile(String exportPathId) {
        return exportFiles.get(exportPathId);
    }

    /**
     * Marks an export directory with the final counts reported by the worker. The
     * worker owns directory execution, so its finished totals are the authoritative
     * aggregate result shown by the queue UI.
     */
    public void markDirectoryFinished(Path exportDir, int total, int passed, int failed) {
        if (exportDir == null) {
            return;
        }

        Path normalizedDir = exportDir.toAbsolutePath().normalize();
        ExportDirectoryQueueItem directory = directories.computeIfAbsent(normalizedDir, ExportDirectoryQueueItem::new);
        directory.setTotal(total);
        directory.setPassed(passed);
        directory.setFailed(failed);
        directory.setStatus(failed > 0 ? ItemStatus.COMPLETED_WITH_ERRORS : ItemStatus.COMPLETED);
        publishRowChangedEvent(normalizedDir.toString(), "Export directory completed");
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

        ExportDirectoryQueueItem directory = directories.remove(normalizedDir);
        if (directory == null) {
            directory = new ExportDirectoryQueueItem(normalizedDir);
        }
        directory.getFiles().clear();
        directory.setTotal(0);
        directory.setPassed(0);
        directory.setFailed(0);
        directory.setStatus(ItemStatus.QUEUED);
        // Reinsert the root row so a reused export directory appears after older
        // history rows instead of keeping its previous insertion position.
        directories.put(normalizedDir, directory);
        publishQueueChangedEvent(normalizedDir.toString(), "Export directory reset for new run");
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

            FileQueueItem item = new FileQueueItem(old.getFileName(), newExportPathId,
                    old.getFileSize(), old.getSortSize());
            exportFiles.put(newExportPathId, item);
            ExportDirectoryQueueItem newDirectory = directoryFor(newExportPathId);
            newDirectory.addFile(item);
            refreshDirectory(newDirectory);
            publishQueueChangedEvent(newExportPathId, "Export file moved to new directory");
        }
    }

    /**
     * Removes transient recovery directories that never completed any file, so the
     * queue history does not show zero-count rows for unusable replacement folders.
     */
    private void removeEmptyUnfinishedDirectory(ExportDirectoryQueueItem directory) {
        if (directory.getFiles().isEmpty() && directory.getPassed() == 0 && directory.getFailed() == 0) {
            directories.remove(directory.getExportDir());
            publishQueueChangedEvent(directory.getExportDir().toString(), "Empty export directory removed");
            return;
        }

        refreshDirectory(directory);
    }

    /**
     * Clear all export tracking.
     */
    public void clear() {
        exportFiles.clear();
        directories.clear();
        publishQueueChangedEvent(null, "Export queue cleared");
    }

    private ExportDirectoryQueueItem directoryFor(String exportPathId) {
        Path exportDir = extractExportDir(exportPathId);
        return directories.computeIfAbsent(exportDir, ExportDirectoryQueueItem::new);
    }

    private Path extractExportDir(String exportPathId) {
        String normalizedExportPathId = exportPathId != null ? exportPathId : "";
        Path exportPath = Path.of(normalizedExportPathId);
        Path parent = exportPath.getParent();
        if (parent != null) {
            return parent.toAbsolutePath().normalize();
        }
        return exportPath.toAbsolutePath().normalize();
    }

    private ExportDirectoryQueueItem refreshDirectory(FileQueueItem item) {
        ExportDirectoryQueueItem directory = directoryFor(item.getRowId());
        refreshDirectory(directory);
        return directory;
    }

    private void refreshDirectory(ExportDirectoryQueueItem directory) {
        List<FileQueueItem> files = directory.getFiles();
        int total = files.size();
        int passed = (int) files.stream().filter(file -> file.getStatus() == ItemStatus.COMPLETED).count();
        int failed = (int) files.stream().filter(file -> file.getStatus() == ItemStatus.FAILED).count();

        directory.setTotal(total);
        directory.setPassed(passed);
        directory.setFailed(failed);
        directory.setStatus(computeDirectoryStatus(directory.getStatus(), files));
    }

    /**
     * Keeps an in-flight export root in processing state while storage recovery
     * defers remaining files. The root should only return to queued when it has not
     * started processing yet or when a retry explicitly re-queues files.
     */
    private ItemStatus computeDirectoryStatus(ItemStatus currentStatus, List<FileQueueItem> files) {
        if (files.stream().anyMatch(file -> file.getStatus() == ItemStatus.PROCESSING)) {
            return ItemStatus.PROCESSING;
        }
        if (files.stream().anyMatch(file -> file.getStatus() == ItemStatus.DEFERRED)
                && currentStatus == ItemStatus.PROCESSING) {
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

    private void publishRowChangedEvent(String rowId, String message) {
        publishRowChangedEvent(rowId, null, message);
    }

    private void publishRowChangedEvent(String rowId, String rootRowId, String message) {
        eventPublisher
                .publishEvent(new QueueStatusChangedEvent(QueueType.EXPORT, rowId, rootRowId, false, message));
    }

    private void publishCounterChangedEvent(String rowId, String rootRowId, String message) {
        eventPublisher.publishEvent(new QueueStatusChangedEvent(QueueType.EXPORT, rowId, rootRowId, true, message));
    }

    private void publishQueueChangedEvent(String rowId, String message) {
        eventPublisher.publishEvent(new QueueStatusChangedEvent(QueueType.EXPORT, rowId, true, message));
    }
}
