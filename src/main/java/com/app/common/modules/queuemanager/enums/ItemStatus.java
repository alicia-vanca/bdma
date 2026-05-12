package com.app.common.modules.queuemanager.enums;

public enum ItemStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    SKIPPED,
    CANCELLED,
    DEFERRED
}
