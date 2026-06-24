package com.app.common.modules.databackup;

import com.app.common.modules.databackup.workers.DataBackupWorker;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * Manages backup worker lifecycle.
 * Worker starts after login and stops gracefully on logout.
 */
@Component
public class DataBackupRunner {

    private final DataBackupWorker worker;
    private Thread backupThread;
    private volatile boolean shuttingDown;

    public DataBackupRunner(DataBackupWorker worker) {
        this.worker = worker;
    }

    public synchronized void startBackupWorker() {
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
        if (backupThread == null || !backupThread.isAlive()) {
            worker.resetShutdownFlag();
            backupThread = new Thread(worker, "backup-worker");
            backupThread.setDaemon(true);
            backupThread.start();
        }
    }

    /**
     * Asks the backup worker to finish current work before the Spring context
     * closes.
     */
    @PreDestroy
    public synchronized void gracefulShutdown() {
        shuttingDown = true;

        try {
            if (backupThread != null) {
                worker.requestShutdown();
                // Idle workers block on the queue; interrupt only when no backup is active so
                // shutdown does not wait for the join timeout.
                if (worker.isIdleForShutdown()) {
                    backupThread.interrupt();
                }
                waitForThreadToStop(backupThread);
                backupThread = null;
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
