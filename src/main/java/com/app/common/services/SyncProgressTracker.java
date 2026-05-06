package com.app.common.services;

import javafx.application.Platform;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SyncProgressTracker {

    @Setter
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

    public void markQueued(String hardwareId) {
        progressMap.remove(hardwareId);
        progressMap.put(hardwareId, SyncProgress.queued());
        notifyProgressChanged();
    }

    public void markSyncing(String hardwareId, int total, int passed, int failed) {
        progressMap.put(hardwareId, SyncProgress.syncing(total, passed, failed));
        notifyProgressChanged();
    }

    public void markCancelled(String hardwareId) {
        SyncProgress current = progressMap.get(hardwareId);
        if (current != null && current.status() == SyncStatus.SYNCING) {
            progressMap.put(hardwareId, SyncProgress.cancelled(current.total(), current.passed(), current.failed()));
        } else {
            progressMap.remove(hardwareId);
        }
        notifyProgressChanged();
    }

    public void markDone(String hardwareId) {
        SyncProgress current = progressMap.get(hardwareId);
        if (current != null && current.status() == SyncStatus.SYNCING) {
            progressMap.put(hardwareId, SyncProgress.completed(current.total(), current.passed(), current.failed()));
        } else if (current != null && current.status() == SyncStatus.CANCELLED) {
            // keep cancelled status if already set
        } else {
            progressMap.remove(hardwareId);
        }
        notifyProgressChanged();
    }

    public SyncProgress getProgress(String hardwareId) {
        return progressMap.getOrDefault(hardwareId, SyncProgress.idle());
    }

    public boolean isAnySyncActive() {
        return progressMap.values().stream()
                .anyMatch(p -> p.status() == SyncStatus.SYNCING || p.status() == SyncStatus.QUEUED);
    }

    private void notifyProgressChanged() {
        if (onProgressChanged != null) {
            Platform.runLater(onProgressChanged);
        }
    }

    public void clearAll() {
        progressMap.clear();
        notifyProgressChanged();
    }
}
