package com.app.common.services;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.app.common.dtos.DeviceEvent;
import com.app.common.dtos.DeviceValidationResult;

@Component
public class DeviceTracker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeviceTracker.class);

    private final AdbClient adbClient;
    private final DeviceValidationService deviceValidationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Set<String> currentDevices = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean running;

    // Holds the active adb track-devices process so shutdown() can destroy it
    // immediately without waiting for the read loop to time out.
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();
    private AtomicReference<ScheduledExecutorService> scheduler = new AtomicReference<>(Executors.newScheduledThreadPool(2));
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingChecks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> stableCounters = new ConcurrentHashMap<>();

    private static final int STABLE_CONFIRM_COUNT = 5;
    private static final int STABLE_POLL_INTERVAL_MS = 1000;

    public DeviceTracker(AdbClient adbClient,
            DeviceValidationService deviceValidationService,
            ApplicationEventPublisher eventPublisher) {
        this.adbClient = adbClient;
        this.deviceValidationService = deviceValidationService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void run() {
        if (!started.compareAndSet(false, true)) {
            log.warn("DeviceTracker is already running; ignore duplicate start");
            return;
        }

        ensureScheduler();
        running = true;
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    track();
                } catch (Exception e) {
                    if (running) {
                        log.warn("Device tracker loop error: {}", e.getMessage());
                        sleep(2000);
                    }
                }
            }
        } finally {
            started.set(false);
        }
    }

    // Stop the current login session and clear all tracked device state so the
    // next login starts from a clean snapshot.
    public void stopTrackingAndResetState() {
        running = false;
        cancelAllStabilityChecks();

        Process p = activeProcess.getAndSet(null);
        if (p != null) {
            p.destroyForcibly();
        }
        // Clear device state so next start will re-validate all connected devices
        currentDevices.clear();
    }

    // Signals the tracker to stop and destroys the active adb process so the
    // OS-level process exits instead of being abandoned in the task manager.
    public void shutdown() {
        stopTrackingAndResetState();
        ScheduledExecutorService currentScheduler = scheduler.getAndSet(Executors.newScheduledThreadPool(2));
        currentScheduler.shutdownNow();
    }

    private void cancelAllStabilityChecks() {
        for (ScheduledFuture<?> future : pendingChecks.values()) {
            future.cancel(false);
        }
        pendingChecks.clear();
        stableCounters.clear();
    }

    private void ensureScheduler() {
        ScheduledExecutorService current = scheduler.get();
        if (current.isShutdown() || current.isTerminated()) {
            scheduler.compareAndSet(current, Executors.newScheduledThreadPool(2));
        }
    }

    private void track() {
        Process p = null;
        try {
            p = adbClient.startAdbProcess("track-devices");
            activeProcess.set(p);

            // Seed each login session from the current adb snapshot so already-
            // Connected devices are re-added immediately after login.
            handle(getDevices());

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
                log.warn("ADB track-devices process failed: {}", e.getMessage());
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
            if (!running || Thread.currentThread().isInterrupted()) {
                log.debug("Skipping adb device refresh during tracker shutdown");
                return List.of();
            }
            log.error("Failed to get devices via adb", e);
            return List.of();
        }
    }

    // Diff the current device set against the live snapshot and publish
    // connect/disconnect events only for devices that actually changed state.
    private void handle(List<String> newDevices) {
        Set<String> newSet = new HashSet<>(newDevices);

        for (String serial : newSet) {
            if (!currentDevices.contains(serial) && !pendingChecks.containsKey(serial)) {
                log.info("New device detected, starting stability check: {}", serial);
                startStabilityCheck(serial);
            }
        }

        for (String serial : new HashSet<>(currentDevices)) {
            if (!newSet.contains(serial)) {
                // Cancel stability check nếu disconnect trong lúc đang chờ
                cancelStabilityCheck(serial);
                eventPublisher.publishEvent(new DeviceEvent(serial, DeviceEvent.EventType.DISCONNECTED, null));
            }
        }

        currentDevices.clear();
        currentDevices.addAll(newSet);
    }

    private void startStabilityCheck(String serial) {
        stableCounters.put(serial, 0);

        try {
            ScheduledFuture<?> future = scheduler.get().scheduleWithFixedDelay(() -> {
                if (!running) {
                    cancelStabilityCheck(serial);
                    return;
                }

                boolean alive = adbClient.isDeviceAlive(serial);
                if (!alive) {
                    log.debug("Stability check failed for {}, resetting counter", serial);
                    stableCounters.put(serial, 0);
                    return;
                }

            int count = stableCounters.merge(serial, 1, (a, b) -> a + b);
            log.debug("Stability check {}/{} for: {}", count, STABLE_CONFIRM_COUNT, serial);

                if (count >= STABLE_CONFIRM_COUNT) {
                    cancelStabilityCheck(serial);
                    log.info("Device stable after {} checks, publishing CONNECTED: {}", STABLE_CONFIRM_COUNT, serial);
                    DeviceValidationResult result = validateSerialSafely(serial);
                    eventPublisher.publishEvent(new DeviceEvent(serial, DeviceEvent.EventType.CONNECTED, result));
                }

            }, STABLE_POLL_INTERVAL_MS, STABLE_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

            pendingChecks.put(serial, future);
        } catch (RejectedExecutionException e) {
            stableCounters.remove(serial);
            log.warn("Unable to schedule stability check for {}: {}", serial, e.getMessage());
        }
    }

    private void cancelStabilityCheck(String serial) {
        ScheduledFuture<?> future = pendingChecks.remove(serial);
        if (future != null) {
            future.cancel(false);
        }
        stableCounters.remove(serial);
    }

    // Validation failures should not stop device tracking; emit an invalid result
    // so listeners can decide how to present the failure.
    private DeviceValidationResult validateSerialSafely(String serial) {
        try {
            return deviceValidationService.validateConnectedDevice(serial);
        } catch (Exception e) {
            log.warn("Validation failed for serial {}: {}", serial, e.getMessage());
            return DeviceValidationResult.invalid(serial, "Validation failed");
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
