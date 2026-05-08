package com.app.common.services;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

import com.app.common.dtos.DeviceValidationResult;
import com.app.common.events.DeviceEvent;

@Component
public class DeviceTracker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeviceTracker.class);

    private final AdbClient adbClient;
    private final DeviceValidationService deviceValidationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentHashMap<String, DeviceState> deviceStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> gracePeriodTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceValidationResult> unvalidatedResults = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean running;

    // Holds the active adb track-devices process so shutdown() can destroy it
    // immediately without waiting for the read loop to time out.
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();
    private AtomicReference<ScheduledExecutorService> scheduler = new AtomicReference<>(
            Executors.newScheduledThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors())));
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingChecks = new ConcurrentHashMap<>();
    private static final long STABILITY_DURATION_MS = 5_000; // device must stay alive continuously for this long
    private static final long STABILITY_TIMEOUT_MS = 15_000; // max time allowed for stability check before dropping
    private static final long GRACE_PERIOD_MS = 1_500; // buffer before publishing DISCONNECTED
    private static final long STABILITY_POLL_MS = 500; // interval between isDeviceAlive() checks

    private enum DeviceState {
        STABILIZING, CONNECTED, DISCONNECTING, UNVALIDATED
    }

    public DeviceTracker(AdbClient adbClient,
            DeviceValidationService deviceValidationService,
            ApplicationEventPublisher eventPublisher) {
        this.adbClient = adbClient;
        this.deviceValidationService = deviceValidationService;
        this.eventPublisher = eventPublisher;
        this.adbClient.setSerialPreflightChecker(this::verifySerialBeforeCommand);
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

    // Stops the current session, cancels all pending stability checks and grace
    // periods.
    // Clears device state so the next login starts from a clean snapshot.
    public void stopTrackingAndResetState() {
        running = false;
        cancelAllStabilityChecks();

        Process p = activeProcess.getAndSet(null);
        if (p != null) {
            p.destroyForcibly();
        }
        log.info("Device tracking stopped and state reset");
    }

    // Signals the tracker to stop, destroys the active adb process.
    // Shuts down the scheduler to release all threads.
    public void shutdown() {
        stopTrackingAndResetState();
        scheduler.get().shutdownNow();
    }

    // Cancels all pending stability checks and grace period timers, then clears
    // device state.
    // Called on session stop or shutdown.
    private void cancelAllStabilityChecks() {
        for (ScheduledFuture<?> future : pendingChecks.values()) {
            future.cancel(false);
        }
        pendingChecks.clear();

        for (ScheduledFuture<?> future : gracePeriodTasks.values()) {
            future.cancel(false);
        }
        for (String serial : gracePeriodTasks.keySet()) {
            adbClient.onDisconnected(serial);
        }
        gracePeriodTasks.clear();

        for (Map.Entry<String, DeviceState> entry : deviceStates.entrySet()) {
            if (entry.getValue() != DeviceState.STABILIZING) {
                adbClient.onDisconnected(entry.getKey());
            }
        }

        deviceStates.clear();
        unvalidatedResults.clear();
    }

    // Recreates the scheduler if it was shut down (e.g. after logout/login cycle).
    private void ensureScheduler() {
        ScheduledExecutorService current = scheduler.get();
        if (current.isShutdown() || current.isTerminated()) {
            scheduler.compareAndSet(current,
                    Executors.newScheduledThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors())));
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

    private boolean verifySerialBeforeCommand(String serial) {
        if (serial == null || serial.isBlank() || !running) {
            return true;
        }

        boolean present;
        try {
            present = adbClient.isDeviceAlive(serial);
        } catch (Exception e) {
            log.debug("[{}] Manual preflight snapshot failed: {}", serial, e.getMessage());
            return false;
        }

        if (present) {
            if (deviceStates.replace(serial, DeviceState.DISCONNECTING, DeviceState.CONNECTED)) {
                cancelGracePeriod(serial);
                log.debug("[{}] Manual preflight confirmed reconnect", serial);
            }
            return true;
        }

        if (deviceStates.replace(serial, DeviceState.CONNECTED, DeviceState.DISCONNECTING)) {
            log.info("[{}] Manual preflight: missing from adb snapshot, entering grace period", serial);
            startGracePeriod(serial);
        }
        return false;
    }

    // Diffs the live device snapshot against current tracked states.
    // New devices enter STABILIZING, missing CONNECTED devices enter DISCONNECTING
    // (grace period), missing STABILIZING devices are dropped immediately.
    private void handle(List<String> newDevices) {
        Set<String> newSet = new HashSet<>(newDevices);

        for (String serial : newSet) {
            deviceStates.computeIfAbsent(serial, s -> {
                if (!pendingChecks.containsKey(s)) {
                    log.info("[{}] New device detected, starting stability check", s);
                    startStabilityCheck(s);
                }
                return DeviceState.STABILIZING;
            });

            if (deviceStates.replace(serial, DeviceState.DISCONNECTING, DeviceState.CONNECTED)) {
                cancelGracePeriod(serial);
                log.info("[{}] Device reconnected within grace period", serial);
            }
        }

        for (String serial : new HashSet<>(deviceStates.keySet())) {
            if (!newSet.contains(serial)) {
                DeviceState state = deviceStates.get(serial);
                if (state == DeviceState.CONNECTED) {
                    log.info("[{}] Lost connection to device, entering grace period", serial);
                    deviceStates.put(serial, DeviceState.DISCONNECTING);
                    startGracePeriod(serial);
                } else if (state == DeviceState.STABILIZING) {
                    deviceStates.remove(serial);
                    cancelStabilityCheck(serial);
                } else if (state == DeviceState.UNVALIDATED) {
                    deviceStates.remove(serial);
                    cancelStabilityCheck(serial);
                    eventPublisher.publishEvent(new DeviceEvent(serial, DeviceEvent.EventType.DISCONNECTED, unvalidatedResults.remove(serial)));
                }
            }
        }
    }

    // Polls isDeviceAlive() every STABILITY_POLL_MS.
    // Device must remain alive continuously for STABILITY_DURATION_MS before being
    // considered stable.
    // Drops device if not stable within STABILITY_TIMEOUT_MS total.
    private void startStabilityCheck(String serial) {
        long startedAt = System.currentTimeMillis();
        long[] stableSince = { 0 };

        try {
            ScheduledFuture<?> future = scheduler.get().scheduleWithFixedDelay(
                    () -> runStabilityPoll(serial, startedAt, stableSince),
                    STABILITY_POLL_MS, STABILITY_POLL_MS, TimeUnit.MILLISECONDS);

            pendingChecks.put(serial, future);
        } catch (RejectedExecutionException e) {
            deviceStates.remove(serial);
            log.warn("[{}] Unable to schedule stability check: {}", serial, e.getMessage());
        }
    }

    private void runStabilityPoll(String serial, long startedAt, long[] stableSince) {
        if (!running) {
            cancelStabilityCheck(serial);
            return;
        }

        if (System.currentTimeMillis() - startedAt > STABILITY_TIMEOUT_MS) {
            log.warn("[{}] Stability check timed out, dropping device", serial);
            deviceStates.remove(serial);
            cancelStabilityCheck(serial);
            return;
        }

        if (!adbClient.isDeviceAlive(serial)) {
            stableSince[0] = 0;
            log.info("[{}] Disconnected, resetting stability timer", serial);
            return;
        }

        if (stableSince[0] == 0) {
            stableSince[0] = System.currentTimeMillis();
        }

        if (System.currentTimeMillis() - stableSince[0] >= STABILITY_DURATION_MS) {
            onDeviceStable(serial);
        }
    }

    private void onDeviceStable(String serial) {
        cancelStabilityCheck(serial);
        if (!deviceStates.replace(serial, DeviceState.STABILIZING, DeviceState.CONNECTED)) {
            return;
        }

        adbClient.onReconnected(serial);
        log.info("[{}] Device stable, start validation", serial);
        DeviceValidationResult result = validateSerialSafely(serial);

        if (result.isValid()) {
            eventPublisher.publishEvent(new DeviceEvent(serial, DeviceEvent.EventType.CONNECTED, result));
        } else {
            deviceStates.put(serial, DeviceState.UNVALIDATED);
            unvalidatedResults.put(serial, result);
            eventPublisher.publishEvent(new DeviceEvent(serial, DeviceEvent.EventType.UNVALIDATED, result));
        }
    }

    private void cancelStabilityCheck(String serial) {
        ScheduledFuture<?> future = pendingChecks.remove(serial);
        if (future != null) {
            future.cancel(false);
        }
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

    // Waits GRACE_PERIOD_MS before publishing DISCONNECTED to allow transient.
    // Disconnects (e.g. USB mode change) to recover without triggering a full
    // reconnect cycle.
    private void startGracePeriod(String serial) {
        adbClient.onGraceStarted(serial);
        try {
            ScheduledFuture<?> future = scheduler.get().schedule(() -> {
                if (deviceStates.remove(serial, DeviceState.DISCONNECTING)) {
                    log.info("[{}] Grace period expired, publishing DISCONNECTED", serial);
                    adbClient.onDisconnected(serial);
                    eventPublisher.publishEvent(
                            new DeviceEvent(serial, DeviceEvent.EventType.DISCONNECTED, null));
                } else {
                    adbClient.onReconnected(serial);
                }
                gracePeriodTasks.remove(serial);
            }, GRACE_PERIOD_MS, TimeUnit.MILLISECONDS);
            gracePeriodTasks.put(serial, future);
        } catch (RejectedExecutionException e) {
            deviceStates.remove(serial);
            adbClient.onDisconnected(serial);
            log.warn("[{}] Unable to schedule grace period: {}", serial, e.getMessage());
        }
    }

    private void cancelGracePeriod(String serial) {
        ScheduledFuture<?> future = gracePeriodTasks.remove(serial);
        if (future != null) {
            future.cancel(false);
        }
        adbClient.onReconnected(serial);
    }
}
