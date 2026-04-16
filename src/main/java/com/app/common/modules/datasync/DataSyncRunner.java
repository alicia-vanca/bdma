package com.app.common.modules.datasync;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.app.common.modules.datasync.workers.DataSyncWorker;
import com.app.common.services.DeviceTracker;

import jakarta.annotation.PreDestroy;

@Component
public class DataSyncRunner implements CommandLineRunner {

    private final DataSyncWorker worker;
    private final DeviceTracker tracker;
    private Thread syncThread;
    private Thread trackerThread;

    public DataSyncRunner(DataSyncWorker worker, DeviceTracker tracker) {
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

    // Stop the tracker and interrupt worker threads when Spring context closes.
    @PreDestroy
    public void shutdown() {
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
}
