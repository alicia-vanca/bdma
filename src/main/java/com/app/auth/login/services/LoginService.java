package com.app.auth.login.services;

import com.app.common.dtos.SyncContext;
import com.app.common.models.User;
import com.app.common.repositories.ValidatedDeviceRepository;
import com.app.common.services.AdbClient;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.session.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final Session session;
    private final DeviceSyncQueue queue;
    private final AdbClient adbClient;
    private final ValidatedDeviceRepository validatedDeviceRepository;

    public LoginService(Session session,
            DeviceSyncQueue queue,
            AdbClient adbClient,
            ValidatedDeviceRepository validatedDeviceRepository) {
        this.session = session;
        this.queue = queue;
        this.adbClient = adbClient;
        this.validatedDeviceRepository = validatedDeviceRepository;
    }

    /**
     * Handles post-login initialization:
     * - Retrieves authenticated user from session
     * - Builds SyncContext based on user role
     * - Enqueues all connected devices for syncing
     * - Sync is processed asynchronously by workers
     */
    public void onLoginSuccess() {

        User user = session.getUser();
        if (user == null) {
            log.warn("onLoginSuccess called but session is empty.");
            return;
        }

        SyncContext ctx = new SyncContext(
                user.getUsername(),
                session.isAdmin());

        List<String> connectedSerials = adbClient.listConnectedSerials();

        if (connectedSerials.isEmpty()) {
            log.info("Login [{}] — no devices connected.", user.getUsername());
            return;
        }

        int queuedCount = 0;

        for (String serial : connectedSerials) {
            if (!validatedDeviceRepository.isAllowed(serial)) {
                continue;
            }
            queue.add(serial, ctx);
            queuedCount++;
        }

        log.info("Login [{}] [{}] — {} validated device(s) queued for sync.",
                user.getUsername(),
                user.getRole(),
                queuedCount);
    }
}
