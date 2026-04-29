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
        // Only start sync worker on app startup; device tracker starts after login
        startSyncWorker();
    }

    public synchronized void startSyncWorker() {
        if (syncThread == null || !syncThread.isAlive()) {
            worker.resetShutdownFlag();
            syncThread = new Thread(worker, "sync-worker");
            syncThread.setDaemon(true);
            syncThread.start();
        }
    }

    public synchronized void startDeviceTracker() {
        startSyncWorker();
        if (trackerThread == null || !trackerThread.isAlive()) {
            trackerThread = new Thread(tracker, "device-tracker");
            trackerThread.setDaemon(true);
            trackerThread.start();
        }
    }

    public synchronized void resetForLogout() {
        tracker.stopTrackingAndResetState();

        if (trackerThread != null) {
            trackerThread.interrupt();
            trackerThread = null;
        }

        // Request graceful shutdown without blocking UI
        if (syncThread != null) {
            worker.requestShutdown();
            Thread shutdownThread = syncThread;
            syncThread = null;
            
            // Clean up in background to avoid blocking logout UI
            new Thread(() -> {
                try {
                    shutdownThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // Force interrupt if still running after timeout
                if (shutdownThread.isAlive()) {
                    shutdownThread.interrupt();
                }
            }, "sync-shutdown").start();
        }
    }

    // Stop the tracker and worker threads when Spring context closes.
    @PreDestroy
    public synchronized void shutdown() {
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
