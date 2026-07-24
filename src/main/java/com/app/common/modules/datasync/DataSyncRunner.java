package com.app.common.modules.datasync;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.app.common.modules.datasync.workers.DataSyncWorker;
import com.app.common.modules.device.services.DeviceTracker;

import jakarta.annotation.PreDestroy;

@Component
public class DataSyncRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSyncRunner.class);
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5000;

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
     * Stops sync work before disconnecting the tracker.
     */
    @PreDestroy
    public synchronized void gracefulShutdown() {
        shuttingDown = true;

        try {
            if (syncThread != null) {
                worker.requestShutdown();
                // Idle workers block on the queue; interrupt only when no sync is active so
                // shutdown does not wait for the join timeout.
                if (worker.isIdleForShutdown()) {
                    syncThread.interrupt();
                }
                boolean stopped = waitForThreadToStop(syncThread);
                if (!stopped && !Thread.currentThread().isInterrupted()) {
                    worker.requestForcedShutdown();
                    syncThread.interrupt();
                    stopped = waitForThreadToStop(syncThread);
                    if (stopped) {
                        log.warn("Sync worker forced shutdown");
                    }
                }
                if (stopped) {
                    syncThread = null;
                } else if (!Thread.currentThread().isInterrupted()) {
                    log.error("Sync worker did not stop after interruption");
                }
            }

            if (trackerThread != null) {
                tracker.shutdown();
                trackerThread.interrupt();
                if (waitForThreadToStop(trackerThread)) {
                    trackerThread = null;
                }
            }
        } finally {
            shuttingDown = false;
            notifyAll();
        }
    }

    private boolean waitForThreadToStop(Thread thread) {
        try {
            thread.join(SHUTDOWN_TIMEOUT_MILLIS);
            return !thread.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !thread.isAlive();
        }
    }
}
