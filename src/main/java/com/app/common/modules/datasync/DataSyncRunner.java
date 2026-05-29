package com.app.common.modules.datasync;

import org.springframework.stereotype.Component;

import com.app.common.modules.datasync.workers.DataSyncWorker;
import com.app.common.services.DeviceTracker;

import jakarta.annotation.PreDestroy;

@Component
public class DataSyncRunner {

    private final DataSyncWorker worker;
    private final DeviceTracker tracker;
    private Thread syncThread;
    private Thread trackerThread;
    private volatile boolean shuttingDown;

    public DataSyncRunner(DataSyncWorker worker, DeviceTracker tracker) {
        this.worker = worker;
        this.tracker = tracker;
    }

    public synchronized void startSyncWorker() {
        while (shuttingDown) {
            try {
                // Wait for shutdown to complete before starting new worker
                // Incase user clicks logout and login quickly, we don't want to start the
                // worker until the previous one has fully shut down
                wait(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
        shuttingDown = true;

        if (trackerThread != null) {
            trackerThread.interrupt();
            trackerThread = null;
        }

        if (syncThread == null) {
            tracker.stopTrackingAndResetState();
            shuttingDown = false;
            notifyAll();
            return;
        }

        // Request graceful shutdown without blocking UI
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
            synchronized (this) {
                tracker.stopTrackingAndResetState();
                shuttingDown = false;
                notifyAll();
            }
        }, "sync-shutdown").start();
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
