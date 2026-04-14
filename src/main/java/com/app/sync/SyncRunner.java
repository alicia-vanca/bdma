package com.app.sync;

import com.app.sync.tracker.DeviceTracker;
import com.app.sync.worker.SyncWorker;
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
        start();
    }

    public synchronized void start() {
        if (syncThread == null || !syncThread.isAlive()) {
            syncThread = new Thread(worker, "sync-worker");
            syncThread.setDaemon(true);
            syncThread.start();
        }

        if (trackerThread == null || !trackerThread.isAlive()) {
            trackerThread = new Thread(tracker, "device-tracker");
            trackerThread.setDaemon(true);
            trackerThread.start();
        }
    }

    public synchronized void stop() {
        tracker.shutdown();

        if (trackerThread != null) {
            trackerThread.interrupt();
            trackerThread = null;
        }

        if (syncThread != null) {
            syncThread.interrupt();
            syncThread = null;
        }
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }
}
