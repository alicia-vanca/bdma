package com.app.common.modules.datasync;

import com.app.common.services.DeviceTracker;
import com.app.common.modules.datasync.workers.DataSyncWorker;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

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
