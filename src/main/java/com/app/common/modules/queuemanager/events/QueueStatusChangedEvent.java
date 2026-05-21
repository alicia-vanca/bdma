package com.app.common.modules.queuemanager.events;

import com.app.common.modules.queuemanager.enums.QueueType;

/**
 * Event fired when a queue row changes.
 * <p>
 * {@code rowId} always identifies the changed row. {@code rootRowId} is only
 * set when the changed row is a leaf and the UI needs its parent root row to
 * find it efficiently. Sync example: root row id is camera id, leaf row id is
 * full local save path. Export example: root row id is export directory path,
 * leaf row id is full destination export path. Backup has no root rows, so its
 * file path is the row id and {@code rootRowId} stays {@code null}.
 */
public class QueueStatusChangedEvent {
    private final QueueType queueType;
    private final String rowId;
    private final String rootRowId;
    private final boolean fullRefreshRequired;
    private final boolean rootCountersChanged;
    private final String message;

    /**
     * Creates a single-row update event for a root row or a flat queue row.
     * <p>
     * Use this when the changed row can be addressed directly by {@code rowId} and
     * there is no parent root row to refresh. Examples: a sync device root starts
     * processing, an export directory finishes, or a backup file row changes
     * because backup is a flat queue with no root/leaf hierarchy.
     *
     * @param queueType queue tab that owns the changed row
     * @param rowId     id of the changed row
     * @param message   diagnostic message describing the change
     */
    public QueueStatusChangedEvent(QueueType queueType, String rowId, String message) {
        this(queueType, rowId, null, false, false, message);
    }

    /**
     * Creates an event that forces the owning queue tab to rebuild its rows.
     * <p>
     * Use this when the row shape changed and targeted row repainting is not
     * enough, such as clearing a queue, removing a root row, or
     * resetting/reordering export directory roots. {@code rootCountersChanged}
     * remains false because a full refresh already rebuilds every visible counter.
     *
     * @param queueType           queue tab that owns the changed rows
     * @param rowId               id related to the structural change, or null when
     *                            the whole queue changed
     * @param fullRefreshRequired true when listeners must rebuild the whole tab
     * @param message             diagnostic message describing the change
     */
    public QueueStatusChangedEvent(QueueType queueType, String rowId, boolean fullRefreshRequired, String message) {
        this(queueType, rowId, null, fullRefreshRequired, false, message);
    }

    /**
     * Creates a leaf-row update event that does not affect root counters.
     * <p>
     * Use this for leaf changes where only the leaf display changes, such as
     * progress percentage, processing status, deferred state, rename text, or error
     * text that does not change the passed/failed/skipped totals. The listener uses
     * {@code rootRowId} only to locate the leaf under its parent; it should not
     * repaint the root summary for this event.
     *
     * @param queueType queue tab that owns the changed leaf row
     * @param rowId     id of the changed leaf row
     * @param rootRowId id of the parent root row that contains the leaf
     * @param message   diagnostic message describing the change
     */
    public QueueStatusChangedEvent(QueueType queueType, String rowId, String rootRowId, String message) {
        this(queueType, rowId, rootRowId, false, false, message);
    }

    /**
     * Creates a leaf-row update event that may also require parent counter
     * repainting.
     * <p>
     * Set {@code rootCountersChanged} to true only for terminal result changes that
     * alter aggregate root counts, such as completed, failed, or skipped file rows.
     * Keep it false for processing, progress, pause/defer, and other transient
     * updates so the UI does not repaint root rows unnecessarily.
     *
     * @param queueType           queue tab that owns the changed leaf row
     * @param rowId               id of the changed leaf row
     * @param rootRowId           id of the parent root row that contains the leaf
     * @param rootCountersChanged true when parent total/passed/failed counters must
     *                            be repainted
     * @param message             diagnostic message describing the change
     */
    public QueueStatusChangedEvent(QueueType queueType, String rowId, String rootRowId,
                                   boolean rootCountersChanged, String message) {
        this(queueType, rowId, rootRowId, false, rootCountersChanged, message);
    }

    /**
     * @param queueType           queue tab that owns the changed row
     * @param rowId               id of the row that changed; for leaf rows this is
     *                            the leaf id
     * @param rootRowId           id of the root row containing the changed leaf, or
     *                            null when the changed row is itself a root row
     * @param fullRefreshRequired true when the queue shape changed enough to
     *                            require a full tab rebuild
     * @param rootCountersChanged true when the leaf change updates aggregate root
     *                            counters and the root row must be repainted
     * @param message             diagnostic message describing the queue change
     */
    public QueueStatusChangedEvent(QueueType queueType, String rowId, String rootRowId,
                                   boolean fullRefreshRequired, boolean rootCountersChanged, String message) {
        this.queueType = queueType;
        this.rowId = rowId;
        this.rootRowId = rootRowId;
        this.fullRefreshRequired = fullRefreshRequired;
        this.rootCountersChanged = rootCountersChanged;
        this.message = message;
    }

    public QueueType getQueueType() {
        return queueType;
    }

    public String getRowId() {
        return rowId;
    }

    /**
     * Returns the root queue row id when a leaf row changed, such as a sync file
     * under a device. Null means the changed row is a root queue row.
     */
    public String getRootRowId() {
        return rootRowId;
    }

    /**
     * Indicates that the queue shape changed and the listener must rebuild the tab
     * instead of repainting a single existing row.
     */
    public boolean isFullRefreshRequired() {
        return fullRefreshRequired;
    }

    /**
     * Indicates that a leaf row change affects aggregate root counters.
     */
    public boolean isRootCountersChanged() {
        return rootCountersChanged;
    }

    public String getMessage() {
        return message;
    }
}
