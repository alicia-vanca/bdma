package com.app.common.modules.queuemanager.dtos;

/**
 * Compact progress summary for the footer progress bar.
 *
 * @param kind     queue source represented by the progress bar
 * @param name     source display name, such as device name for sync
 * @param total    total logical files in the compact progress
 * @param finished completed logical files in the compact progress
 * @param complete true only when the producer explicitly reaches a terminal
 *                 state
 */
public record QueueProgressSummary(Kind kind, String name, int total, int finished, boolean complete) {

    public enum Kind {
        SYNC, EXPORT
    }

    public QueueProgressSummary {
        total = Math.max(0, total);
        finished = Math.clamp(finished, 0, total);
    }

    public int progress() {
        if (complete) {
            return 100;
        }
        if (total <= 0) {
            return 0;
        }
        return Math.clamp((int) Math.round((finished * 100.0) / total), 0, 100);
    }
}
