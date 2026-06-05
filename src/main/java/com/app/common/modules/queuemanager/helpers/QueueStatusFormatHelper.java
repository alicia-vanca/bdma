package com.app.common.modules.queuemanager.helpers;

import com.app.common.modules.i18n.I18n;
import com.app.common.modules.queuemanager.dtos.FileQueueItem;
import com.app.common.modules.queuemanager.enums.ItemStatus;

/**
 * Shared queue display rules used by queue tab controllers.
 */
public final class QueueStatusFormatHelper {

    public static final String I18N_QUEUE_EMPTY = "queue.empty";
    public static final String I18N_QUEUE_COLUMN_STATUS = "queue.column.status";
    public static final String I18N_COMMON_RETRY = "common.retry";
    public static final String STYLE_LAST_CELL = "last-cell";
    public static final String STYLE_QUEUE_CELL_CONTENT = "queue-cell-content";
    public static final String STYLE_REASON_LABEL = "-fx-text-fill: #c62828; -fx-font-size: 13px;";

    private QueueStatusFormatHelper() {
    }

    public static String formatStatus(ItemStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case QUEUED -> "⏳ " + I18n.get("queue.status.queued");
            case PROCESSING -> "⟳ " + I18n.get("queue.status.processing");
            case COMPLETED -> "✓ " + I18n.get("queue.status.completed");
            case COMPLETED_WITH_ERRORS -> "⚠ " + I18n.get("queue.status.completed_with_errors");
            case FAILED -> "✕ " + I18n.get("queue.status.failed");
            case SKIPPED -> "⊘ " + I18n.get("queue.status.skipped");
            case CANCELLED -> "✕ " + I18n.get("queue.status.cancelled");
            case DEFERRED -> "⏸ " + I18n.get("queue.status.deferred");
        };
    }

    public static String formatStatusWithProgress(ItemStatus status, int progress) {
        String formattedStatus = formatStatus(status);
        if (status == ItemStatus.PROCESSING && progress > 0) {
            formattedStatus += " (" + progress + "%)";
        }
        return formattedStatus;
    }

    public static String formatStatusWithProgress(FileQueueItem file) {
        return formatStatusWithProgress(file.getStatus(), file.getProgress());
    }

    /**
     * Renders a root row with its current status and aggregate child counters.
     * Finished roots show only the final summary because the counts already imply
     * the final outcome.
     */
    public static String formatRootStatusSummary(ItemStatus status, int total, int passed, int failed) {
        String summary = I18n.get("queue.status.summary", total, passed, failed);
        if (isFinishedStatus(status)) {
            return summary;
        }

        String formattedStatus = formatStatus(status);
        if (formattedStatus.isBlank()) {
            return summary;
        }
        return formattedStatus + "\n" + summary;
    }

    public static boolean hasErrorReason(ItemStatus status, String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return status == ItemStatus.FAILED || status == ItemStatus.COMPLETED_WITH_ERRORS
                || status == ItemStatus.DEFERRED || status == ItemStatus.SKIPPED;
    }

    public static boolean isFinishedStatus(ItemStatus status) {
        return status == ItemStatus.COMPLETED || status == ItemStatus.COMPLETED_WITH_ERRORS
                || status == ItemStatus.FAILED || status == ItemStatus.CANCELLED
                || status == ItemStatus.SKIPPED;
    }

    /**
     * Translates an i18n key while preserving literal messages that are not keys.
     */
    public static String translateIfKey(String messageOrKey) {
        if (messageOrKey == null || messageOrKey.isBlank()) {
            return messageOrKey;
        }
        try {
            return I18n.get(messageOrKey);
        } catch (Exception e) {
            return messageOrKey;
        }
    }
}
