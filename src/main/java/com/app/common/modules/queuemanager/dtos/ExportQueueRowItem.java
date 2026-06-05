package com.app.common.modules.queuemanager.dtos;

import com.app.common.modules.queuemanager.enums.ItemStatus;

/**
 * JavaFX row adapter for the export queue tree.
 * <p>
 * JavaFX tree tables require one value type for root and leaf rows, so this
 * adapter wraps either an {@link ExportDirectoryQueueItem} root row or a
 * {@link FileQueueItem} leaf row. Export root row id is the destination
 * directory path. Export leaf row id is the full destination export path.
 */
public class ExportQueueRowItem {

    private final ExportDirectoryQueueItem directory;
    private final FileQueueItem file;

    /**
     * Creates the invisible JavaFX tree root value.
     * <p>
     * This item is not a real queue row and therefore has no row id or root row id.
     */
    public ExportQueueRowItem() {
        this.directory = null;
        this.file = null;
    }

    /**
     * Creates an export root row wrapper.
     *
     * @param directory directory DTO whose normalized path is the root row id
     */
    public ExportQueueRowItem(ExportDirectoryQueueItem directory) {
        this.directory = directory;
        this.file = null;
    }

    /**
     * Creates an export leaf row wrapper.
     *
     * @param file file DTO whose row id is the full destination export path
     */
    public ExportQueueRowItem(FileQueueItem file) {
        this.directory = null;
        this.file = file;
    }

    /**
     * Returns the text displayed in the first queue column.
     *
     * @return export directory path for root rows, file name for leaf rows, or
     *         "Root" for the invisible JavaFX tree root
     */
    public String getName() {
        if (directory != null) {
            return directory.getDisplayName();
        }
        if (file != null) {
            return file.getFileName();
        }
        return "Root";
    }

    /**
     * Returns the row id for the visible export tree row.
     * <p>
     * Root rows use the normalized export directory path. Leaf file rows use the
     * full destination export path emitted by the export worker.
     */
    public String getRowId() {
        if (directory != null) {
            return directory.getExportDir().toString();
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
    public ExportQueueRowItem copy() {
        if (directory != null) {
            return new ExportQueueRowItem(directory);
        }
        if (file != null) {
            return new ExportQueueRowItem(file);
        }
        return new ExportQueueRowItem();
    }

    /**
     * Returns the status displayed for this export row.
     *
     * @return aggregate directory status for root rows, file status for leaf rows,
     *         or QUEUED for the invisible JavaFX tree root
     */
    public ItemStatus getStatus() {
        if (directory != null) {
            return directory.getStatus();
        }
        if (file != null) {
            return file.getStatus();
        }
        return ItemStatus.QUEUED;
    }

    /**
     * Returns file progress for export leaf rows.
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
     * Indicates whether this wrapper represents an export root row.
     *
     * @return true when this row wraps an export directory root row
     */
    public boolean isDirectory() {
        return directory != null;
    }

    /**
     * Returns the total file count for an export root row.
     *
     * @return aggregate total for directory rows, or 0 for leaf rows
     */
    public int getTotal() {
        return directory != null ? directory.getTotal() : 0;
    }

    /**
     * Returns the successful file count for an export root row.
     *
     * @return aggregate passed count for directory rows, or 0 for leaf rows
     */
    public int getPassed() {
        return directory != null ? directory.getPassed() : 0;
    }

    /**
     * Returns the failed file count for an export root row.
     *
     * @return aggregate failed count for directory rows, or 0 for leaf rows
     */
    public int getFailed() {
        return directory != null ? directory.getFailed() : 0;
    }
}
