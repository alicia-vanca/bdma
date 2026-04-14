package com.app.common.services;

import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.session.Session;
import com.app.common.dtos.SyncContext;
import com.app.common.repositories.ValidatedDeviceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DeviceTracker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeviceTracker.class);

    private final DeviceSyncQueue queue;
    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final Session session;
    private final AdbClient adbClient;

    private final Set<String> currentDevices = new HashSet<>();
    private volatile boolean running = true;
    // Holds the active adb track-devices process so stop() can destroy it
    // immediately.
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    public DeviceTracker(DeviceSyncQueue queue,
            ValidatedDeviceRepository validatedDeviceRepository,
            Session session,
            AdbClient adbClient) {
        this.queue = queue;
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.session = session;
        this.adbClient = adbClient;
    }

    @Override
    public void run() {
        while (running) {
            try {
                track();
            } catch (Exception e) {
                sleep(2000);
            }
        }
    }

    // Signals the tracker to stop and destroys the active adb process so the
    // OS-level process exits instead of being abandoned in the task manager.
    public void stop() {
        running = false;
        Process p = activeProcess.getAndSet(null);
        if (p != null) {
            p.destroyForcibly();
        }
    }

    private void track() {
        Process p = null;
        try {
            p = adbClient.startAdbProcess("track-devices");
            activeProcess.set(p);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {

                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        sleep(200);
                        handle(getDevices());
                    }
                }
            }

        } catch (Exception e) {
            if (running) {
                sleep(2000);
            }
        } finally {
            activeProcess.compareAndSet(p, null);
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }

    private List<String> getDevices() {
        try {
            return adbClient.listConnectedSerials();
        } catch (Exception e) {
            log.error("Failed to get devices via adb", e);
            return List.of();
        }
    }

    private void handle(List<String> newDevices) {
        Set<String> newSet = new HashSet<>(newDevices);

        for (String serial : newSet) {
            if (!currentDevices.contains(serial)
                    && validatedDeviceRepository.isAllowed(serial)
                    && session.getUser() != null) {

                SyncContext ctx = new SyncContext(
                        session.getUser().getUsername(),
                        session.isAdmin());

                queue.add(serial, ctx);
            }
        }

        for (String serial : new HashSet<>(currentDevices)) {
            if (!newSet.contains(serial)) {
                queue.remove(serial);
            }
        }

        currentDevices.clear();
        currentDevices.addAll(newSet);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
