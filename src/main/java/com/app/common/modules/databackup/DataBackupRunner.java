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
                // Incase user clicks logout and login quickly, we don't want to start the
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

    public synchronized void resetForLogout() {
        shuttingDown = true;

        if (backupThread == null) {
            shuttingDown = false;
            notifyAll();
            return;
        }

        worker.requestShutdown();
        Thread shutdownThread = backupThread;
        backupThread = null;

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
                shuttingDown = false;
                notifyAll();
            }
        }, "backup-shutdown").start();
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (backupThread != null) {
            backupThread.interrupt();
            backupThread = null;
        }
    }
}
