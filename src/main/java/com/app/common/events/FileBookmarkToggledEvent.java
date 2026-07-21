package com.app.common.events;

import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * Unified event for file bookmark changes - handles both single file and batch operations.
 */
public class FileBookmarkToggledEvent extends ApplicationEvent {

    private final Long userId;
    private final List<Long> fileIds;   // Single toggle: List.of(fileId); Batch: multiple IDs
    private final boolean bookmarked;
    private final String updatedAt;     // null for batch; timestamp for single toggle

    /**
     * Constructor for single file toggle.
     */
    public FileBookmarkToggledEvent(Object source, Long userId, Long fileId, boolean bookmarked, String createdAt) {
        this(source, userId, List.of(fileId), bookmarked, createdAt);
    }

    /**
     * Constructor for batch operations.
     */
    public FileBookmarkToggledEvent(Object source, Long userId, List<Long> fileIds, boolean bookmarked) {
        this(source, userId, fileIds, bookmarked, null);
    }

    /**
     * Private constructor - unified.
     */
    private FileBookmarkToggledEvent(Object source, Long userId, List<Long> fileIds,
                                      boolean bookmarked, String updatedAt) {
        super(source);
        this.userId = userId;
        this.fileIds = List.copyOf(fileIds);
        this.bookmarked = bookmarked;
        this.updatedAt = updatedAt;
    }

    public Long getUserId() {
        return userId;
    }

    public List<Long> getFileIds() {
        return fileIds;
    }

    public boolean isBookmarked() {
        return bookmarked;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }
}
