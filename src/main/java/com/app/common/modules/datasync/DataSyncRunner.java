package com.app.common.modules.datasync;

import org.springframework.stereotype.Component;

import com.app.common.modules.datasync.workers.DataSyncWorker;
import com.app.common.modules.device.services.DeviceTracker;

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
                // In case user clicks logout and login quickly, we don't want to start the
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

    /**
     * Asks the sync worker to finish current work before the Spring context closes.
     */
    @PreDestroy
    public synchronized void gracefulShutdown() {
        shuttingDown = true;

        try {
            if (trackerThread != null) {
                trackerThread.interrupt();
                trackerThread = null;
            }

            if (syncThread != null) {
                worker.requestShutdown();
                // Idle workers block on the queue; interrupt only when no sync is active so
                // shutdown does not wait for the join timeout.
                if (worker.isIdleForShutdown()) {
                    syncThread.interrupt();
                }
                waitForThreadToStop(syncThread);
                syncThread = null;
            }
        } finally {
            shuttingDown = false;
            notifyAll();
        }
    }

    private void waitForThreadToStop(Thread thread) {
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
