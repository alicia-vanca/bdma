package com.app.sync.tracker;

import javafx.application.Platform;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SyncProgressTracker {

    private Runnable onProgressChanged;

    public enum SyncStatus {
        IDLE,
        QUEUED,
        SYNCING,
        COMPLETED,
        CANCELLED
    }

    public record SyncProgress(SyncStatus status, int total, int passed, int failed) {
        public static SyncProgress idle() {
            return new SyncProgress(SyncStatus.IDLE, 0, 0, 0);
        }

        public static SyncProgress queued() {
            return new SyncProgress(SyncStatus.QUEUED, 0, 0, 0);
        }

        public static SyncProgress syncing(int total, int passed, int failed) {
            return new SyncProgress(SyncStatus.SYNCING, total, passed, failed);
        }

        public static SyncProgress cancelled(int total, int passed, int failed) {
            return new SyncProgress(SyncStatus.CANCELLED, total, passed, failed);
        }

        public static SyncProgress completed(int total, int passed, int failed) {
            return new SyncProgress(SyncStatus.COMPLETED, total, passed, failed);
        }
    }

    private final Map<String, SyncProgress> progressMap = new ConcurrentHashMap<>();

    public void markQueued(String serial) {
        progressMap.put(serial, SyncProgress.queued());
        notifyProgressChanged();
    }

    public void markSyncing(String serial, int total, int passed, int failed) {
        progressMap.put(serial, SyncProgress.syncing(total, passed, failed));
        notifyProgressChanged();
    }

    public void markCancelled(String serial) {
        SyncProgress current = progressMap.get(serial);
        if (current != null && current.status() == SyncStatus.SYNCING) {
            progressMap.put(serial, SyncProgress.cancelled(current.total(), current.passed(), current.failed()));
        } else {
            progressMap.remove(serial);
        }
        notifyProgressChanged();
    }

    public void markDone(String serial) {
        SyncProgress current = progressMap.get(serial);
        if (current != null && current.status() == SyncStatus.SYNCING) {
            progressMap.put(serial, SyncProgress.completed(current.total(), current.passed(), current.failed()));
        } else if (current != null && current.status() == SyncStatus.CANCELLED) {
            // keep cancelled status if already set
        } else {
            progressMap.remove(serial);
        }
        notifyProgressChanged();
    }

    public SyncProgress getProgress(String serial) {
        return progressMap.getOrDefault(serial, SyncProgress.idle());
    }

    public void setOnProgressChanged(Runnable callback) {
        this.onProgressChanged = callback;
    }

    private void notifyProgressChanged() {
        if (onProgressChanged != null) {
            Platform.runLater(onProgressChanged);
        }
    }
}
