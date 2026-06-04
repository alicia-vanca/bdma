package com.app.common.modules.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.common.modules.crypto.services.SyncedEncryptedFileTransitionService;

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

    public SyncedEncryptedFileTransitionStartup(SyncedEncryptedFileTransitionService transitionService,
            @Value("${bodycam.crypto.synced-encrypted-transition.enabled:true}") boolean enabled) {
        this.transitionService = transitionService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            return;
        }

        Thread transitionThread = new Thread(this::runTransitionSafely, "synced-encrypted-file-transition");
        transitionThread.setDaemon(true);
        transitionThread.start();
    }

    private void runTransitionSafely() {
        try {
            transitionService.migrate();
        } catch (Exception e) {
            log.warn("Synced encrypted transition startup failed.", e);
        }
    }
}
