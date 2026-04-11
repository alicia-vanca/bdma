package com.app.auth.service;

import com.app.common.session.Session;
import com.app.device.repository.ValidatedDeviceRepository;
import com.app.sync.model.SyncContext;
import com.app.sync.queue.DeviceQueue;
import com.app.sync.service.AdbService;
import com.app.user.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final Session session;
    private final DeviceQueue queue;
    private final AdbService adbService;
    private final ValidatedDeviceRepository validatedDeviceRepository;

    public LoginService(Session session,
                        DeviceQueue queue,
                        AdbService adbService,
                        ValidatedDeviceRepository validatedDeviceRepository) {
        this.session = session;
        this.queue = queue;
        this.adbService = adbService;
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
                session.isAdmin()
        );

        List<String> connectedSerials = adbService.getConnectedSerials();

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
