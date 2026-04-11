package com.app.sync.tracker;

import com.app.common.session.Session;
import com.app.device.repository.ValidatedDeviceRepository;
import com.app.sync.model.SyncContext;
import com.app.sync.queue.DeviceQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class DeviceTracker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeviceTracker.class);

    private final DeviceQueue queue;
    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final Session session;

    private final Set<String> currentDevices = new HashSet<>();
    private volatile boolean running = true;

    public DeviceTracker(DeviceQueue queue,
                         ValidatedDeviceRepository validatedDeviceRepository,
                         Session session) {
        this.queue = queue;
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.session = session;
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

    private void track() {
        try {
            Process p = new ProcessBuilder("adb", "track-devices").start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        sleep(200);
                        handle(getDevices());
                    }
                }
            }

        } catch (Exception e) {
            sleep(2000);
        }
    }

    private List<String> getDevices() {
        List<String> list = new ArrayList<>();

        try {
            Process p = new ProcessBuilder("adb", "devices").start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.endsWith("device") && !line.startsWith("List")) {
                        list.add(line.split("\\s+")[0]);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to get devices via adb", e);
        }

        return list;
    }

    private void handle(List<String> newDevices) {
        Set<String> newSet = new HashSet<>(newDevices);

        for (String serial : newSet) {
            if (!currentDevices.contains(serial)
                    && validatedDeviceRepository.isAllowed(serial)
                    && session.getUser() != null) {

                SyncContext ctx = new SyncContext(
                        session.getUser().getUsername(),
                        session.isAdmin()
                );

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
