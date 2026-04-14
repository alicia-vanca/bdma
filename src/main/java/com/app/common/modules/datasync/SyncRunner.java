package com.app.common.modules.datasync;

import com.app.common.services.DeviceTracker;
import com.app.common.modules.datasync.workers.SyncWorker;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SyncRunner implements CommandLineRunner {

    private final SyncWorker worker;
    private final DeviceTracker tracker;
    private Thread syncThread;
    private Thread trackerThread;

    public SyncRunner(SyncWorker worker, DeviceTracker tracker) {
        this.worker = worker;
        this.tracker = tracker;
    }

    @Override
    public void run(String... args) {
        // Daemon threads so the JVM can exit cleanly without waiting for them.
        syncThread = new Thread(worker, "sync-worker");
        syncThread.setDaemon(true);
        syncThread.start();

        trackerThread = new Thread(tracker, "device-tracker");
        trackerThread.setDaemon(true);
        trackerThread.start();
    }

    // Stop the tracker and interrupt worker threads when Spring context closes.
    @PreDestroy
    public void shutdown() {
        tracker.stop();
        if (syncThread != null) {
            syncThread.interrupt();
        }
        if (trackerThread != null) {
            trackerThread.interrupt();
        }
    }
}
