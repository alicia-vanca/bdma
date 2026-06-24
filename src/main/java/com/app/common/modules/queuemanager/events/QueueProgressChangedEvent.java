package com.app.common.modules.queuemanager.events;

import com.app.common.modules.queuemanager.dtos.QueueProgressSummary;

/**
 * Event fired when compact queue progress changes.
 * <p>
 * Queue row events remain responsible for the detailed queue dialog. Consumers
 * of this event own display priority, completion grace, and idle rendering.
 */
public record QueueProgressChangedEvent(QueueProgressSummary summary) {
}
