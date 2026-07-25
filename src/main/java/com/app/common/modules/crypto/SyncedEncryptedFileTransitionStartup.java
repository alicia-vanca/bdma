package com.app.common.modules.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.app.common.modules.crypto.services.SyncedEncryptedFileTransitionService;

import jakarta.annotation.PreDestroy;

/**
 * Temporary startup hook for legacy _enc synced-file transition. Remove
 * together
 * with SyncedEncryptedFileTransitionService after the transition phase.
 */
@Component
public class SyncedEncryptedFileTransitionStartup {

    private static final Logger log = LoggerFactory.getLogger(SyncedEncryptedFileTransitionStartup.class);

    private final SyncedEncryptedFileTransitionService transitionService;
    private final boolean enabled;
    private volatile Thread transitionThread;

    public SyncedEncryptedFileTransitionStartup(@Lazy SyncedEncryptedFileTransitionService transitionService,
            @Value("${bodycam.crypto.synced-encrypted-transition.enabled:true}") boolean enabled) {
        this.transitionService = transitionService;
        this.enabled = enabled;
    }

    public void startAfterUi() {
        if (!enabled) {
            return;
        }

        Thread thread = Thread.ofPlatform()
                .daemon()
                .name("synced-encrypted-file-transition")
                .unstarted(this::runTransitionSafely);
        transitionThread = thread;
        thread.start();
    }

    private void runTransitionSafely() {
        try {
            transitionService.migrate();
        } catch (Exception e) {
            log.warn("Synced encrypted transition startup failed.", e);
        } finally {
            transitionThread = null;
        }
    }

    @PreDestroy
    public void shutdown() {
        stopAndWait();
    }

    public boolean stopAndWait() {
        Thread thread = transitionThread;
        if (thread == null) {
            return true;
        }

        thread.interrupt();
        try {
            // ponytail: 2 s shutdown ceiling; use managed executor if transition must finish before exit.
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean stopped = !thread.isAlive();
        if (!stopped) {
            log.error("Synced encrypted transition did not stop within 2000 ms");
        }
        return stopped;
    }
}
