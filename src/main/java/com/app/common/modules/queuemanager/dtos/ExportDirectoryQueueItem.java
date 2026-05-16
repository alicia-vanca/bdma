package com.app.common.modules.queuemanager.dtos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.app.common.modules.queuemanager.enums.ItemStatus;

/**
 * Represents one export destination directory in the export queue with its file
 * list and aggregate completion counts.
 */
public class ExportDirectoryQueueItem {
    private final Path exportDir;
    private ItemStatus status;
    private final List<FileQueueItem> files;
    private int total;
    private int passed;
    private int failed;
    private Runnable retryAction;

    public ExportDirectoryQueueItem(Path exportDir) {
        this.exportDir = exportDir;
        this.status = ItemStatus.QUEUED;
        this.files = new ArrayList<>();
    }

    public Path getExportDir() {
        return exportDir;
    }

    public String getDisplayName() {
        return exportDir.toString();
    }

    public ItemStatus getStatus() {
        return status;
    }

    public void setStatus(ItemStatus status) {
        this.status = status;
    }

    public List<FileQueueItem> getFiles() {
        return files;
    }

    /**
     * Adds a file and keeps queued export rows ordered by size so the UI mirrors
     * the worker's processing order whenever files are appended to a directory.
     */
    public void addFile(FileQueueItem file) {
        files.add(file);
        sortFiles();
    }

    public void removeFile(FileQueueItem file) {
        files.remove(file);
    }

    public void sortFiles() {
        files.sort(Comparator.comparingLong(file -> file.getSortSize() != null
                ? file.getSortSize()
                : Long.MAX_VALUE));
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPassed() {
        return passed;
    }

    public void setPassed(int passed) {
        this.passed = passed;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public Runnable getRetryAction() {
        return retryAction;
    }

    public void setRetryAction(Runnable retryAction) {
        this.retryAction = retryAction;
    }
}
