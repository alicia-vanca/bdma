package com.app.common.modules.dataexport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.common.modules.dataexport.workers.DataExportWorker;

import jakarta.annotation.PreDestroy;

/**
 * Manages export worker lifecycle, matching the sync and backup runner pattern.
 * The worker owns export queue execution; this runner owns session reset and
 * shutdown boundaries.
 */
@Component
public class DataExportRunner {

    private static final Logger log = LoggerFactory.getLogger(DataExportRunner.class);

    private final DataExportWorker worker;

    public DataExportRunner(DataExportWorker worker) {
        this.worker = worker;
    }

    /**
     * Stops all pending exports and prepares the worker for the next login
     * session.
     */
    public void resetForLogout() {
        worker.cancelAndCleanup();
        worker.resetExecutor();
        log.info("Export worker reset for logout");
    }

    @PreDestroy
    public void shutdown() {
        worker.cancelAndCleanup();
        log.info("Export runner shut down");
    }
}
