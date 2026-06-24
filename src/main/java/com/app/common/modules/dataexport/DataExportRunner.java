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

    @PreDestroy
    public void shutdown() {
        try {
            worker.cancelAndCleanup();
        } catch (Exception e) {
            log.error("Error during export worker shutdown", e);
        } finally {
            log.info("Export runner shut down");
        }
    }
}
