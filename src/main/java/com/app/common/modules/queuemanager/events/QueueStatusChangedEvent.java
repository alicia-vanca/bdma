package com.app.common.modules.queuemanager.events;

import com.app.common.modules.queuemanager.enums.QueueType;

/**
 * Event fired when queue status changes (device added, file status updated, etc.)
 */
public class QueueStatusChangedEvent {
    private final QueueType queueType;
    private final String itemId;
    private final String message;

    public QueueStatusChangedEvent(QueueType queueType, String itemId, String message) {
        this.queueType = queueType;
        this.itemId = itemId;
        this.message = message;
    }

    public QueueType getQueueType() {
        return queueType;
    }

    public String getItemId() {
        return itemId;
    }

    public String getMessage() {
        return message;
    }
}
