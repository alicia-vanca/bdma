package com.app.common.modules.device.services;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
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

import com.app.common.definitions.enums.DeviceEventType;
import com.app.common.modules.device.dtos.DeviceValidationResult;
import com.app.common.modules.device.events.DeviceEvent;

@Component
public class DeviceTracker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeviceTracker.class);

    private final AdbClient adbClient;
    private final DeviceValidationService deviceValidationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentHashMap<String, DeviceState> deviceStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> gracePeriodTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceValidationResult> validationResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> serialLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean running;

    // Holds the active adb track-devices process so shutdown() can destroy it
    // immediately without waiting for the read loop to time out.
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();
    private final AtomicReference<ScheduledExecutorService> scheduler = new AtomicReference<>(
            Executors.newScheduledThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors())));
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingChecks = new ConcurrentHashMap<>();
    private static final long STABILITY_DURATION_MS = 1_200; // device must stay alive continuously for this long
    private static final long STABILITY_TIMEOUT_MS = 15_000; // max time allowed for stability check before dropping
    private static final long GRACE_PERIOD_MS = 3_000; // buffer before publishing DISCONNECTED
    private static final long STABILITY_POLL_MS = 300; // interval between isDeviceAlive() checks

    private enum DeviceState {
        STABILIZING, CONNECTED, DISCONNECTING
    }

    /**
     * Returns whether a hardware serial is currently accepted as connected by the
     * tracker. DISCONNECTING devices are treated as unavailable so retry actions do
     * not enqueue work during the grace period.
     *
     * @param serial hardware serial reported by ADB
     * @return true only when the tracker has confirmed the device is connected
     */
    public boolean isConnected(String serial) {
        if (serial == null || serial.isBlank()) {
            return false;
        }
        return deviceStates.get(serial) == DeviceState.CONNECTED;
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
            try {
                p.destroyForcibly();
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    log.warn("Timed out waiting for adb track-devices process to exit");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for adb track-devices process to exit");
            } catch (SecurityException e) {
                log.warn("Unable to force-stop adb track-devices process", e);
            } finally {
                closeProcessStreams(p);
            }
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
        validationResults.clear();
        serialLocks.clear();
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
                try {
                    p.destroyForcibly();
                } catch (SecurityException e) {
                    log.warn("Unable to force-stop adb track-devices process", e);
                } finally {
                    closeProcessStreams(p);
                }
            }
        }
    }

    private void closeProcessStreams(Process process) {
        closeProcessStream(process.getInputStream(), "input");
        closeProcessStream(process.getOutputStream(), "output");
        closeProcessStream(process.getErrorStream(), "error");
    }

    private void closeProcessStream(Closeable stream, String streamName) {
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("Failed to close adb track-devices {} stream", streamName, e);
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
            log.debug("[{}] ADB preflight check: get snapshot failed: {}", serial, e.getMessage());
            return false;
        }

        boolean startGrace = false;
        boolean reconnected = false;
        if (present) {
            Object lock = serialLock(serial);
            synchronized (lock) {
                if (deviceStates.replace(serial, DeviceState.DISCONNECTING, DeviceState.CONNECTED)) {
                    cancelGraceTask(serial);
                    reconnected = true;
                    log.debug("[{}] ADB preflight check: device reconnected confirmed", serial);
                }
            }
            if (reconnected) {
                adbClient.onReconnected(serial);
            }
            return true;
        }

        Object lock = serialLock(serial);
        synchronized (lock) {
            if (deviceStates.replace(serial, DeviceState.CONNECTED, DeviceState.DISCONNECTING)) {
                log.info("[{}] ADB preflight check: device is missing from snapshot, entering grace period", serial);
                startGrace = true;
            }
        }

        if (startGrace) {
            startDisconnectGracePeriod(serial);
        }
        return false;
    }

    // Diffs the live device snapshot against current tracked states.
    // New devices enter STABILIZING, missing CONNECTED devices enter DISCONNECTING
    // (grace period), missing STABILIZING devices are dropped immediately.
    private void handle(List<String> newDevices) {
        Set<String> newSet = new HashSet<>(newDevices);

        for (String serial : newSet) {
            handlePresentDevice(serial);
        }

        for (String serial : new HashSet<>(deviceStates.keySet())) {
            handleMissingDevice(serial, newSet);
        }
    }

    // Registers new devices or converts devices in grace back to CONNECTED.
    private void handlePresentDevice(String serial) {
        deviceStates.computeIfAbsent(serial, s -> {
            if (!pendingChecks.containsKey(s)) {
                log.info("[{}] New device detected, starting stability check", s);
                startStabilityCheck(s);
            }
            return DeviceState.STABILIZING;
        });

        boolean reconnected = false;
        Object lock = serialLock(serial);
        synchronized (lock) {
            if (deviceStates.replace(serial, DeviceState.DISCONNECTING, DeviceState.CONNECTED)) {
                cancelGraceTask(serial);
                reconnected = true;
                log.info("[{}] Device reconnected within grace period", serial);
            }
        }
        if (reconnected) {
            adbClient.onReconnected(serial);
        }
    }

    // Moves missing CONNECTED devices into grace and removes unstable devices.
    private void handleMissingDevice(String serial, Set<String> connectedSerials) {
        if (connectedSerials.contains(serial)) {
            return;
        }

        boolean startGrace = false;
        Object lock = serialLock(serial);
        synchronized (lock) {
            DeviceState state = deviceStates.get(serial);
            if (state == DeviceState.CONNECTED) {
                log.info("[{}] Lost connection to device, entering grace period", serial);
                deviceStates.put(serial, DeviceState.DISCONNECTING);
                startGrace = true;
            } else if (state == DeviceState.STABILIZING) {
                log.info("[{}] Device disconnected during stability check, dropping pending device", serial);
                deviceStates.remove(serial);
                cancelStabilityCheck(serial);
            }
        }
        if (startGrace) {
            startDisconnectGracePeriod(serial);
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
            log.info("[{}] Device is not alive, resetting stability timer", serial);
            return;
        }

        if (!isInternalStorageAccessible(serial)) {
            // Internal storage is not accessible yet, resetting stability timer
            stableSince[0] = 0;
            return;
        }

        if (stableSince[0] == 0) {
            stableSince[0] = System.currentTimeMillis();
        }

        if (System.currentTimeMillis() - stableSince[0] >= STABILITY_DURATION_MS) {
            onDeviceStable(serial);
        }
    }

    private boolean isInternalStorageAccessible(String serial) {
        return !adbClient.getIntStorageInfo(serial).isEmpty();
    }

    private void onDeviceStable(String serial) {
        cancelStabilityCheck(serial);
        if (!deviceStates.replace(serial, DeviceState.STABILIZING, DeviceState.CONNECTED)) {
            return;
        }

        adbClient.onReconnected(serial);
        log.info("[{}] Device stable, start validation", serial);
        DeviceValidationResult result = validateSerialSafely(serial);
        validationResults.put(serial, result);
        if (result.isValid()) {
            eventPublisher.publishEvent(new DeviceEvent(serial, DeviceEventType.CONNECTED, result));
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
    private void startDisconnectGracePeriod(String serial) {
        adbClient.onGraceStarted(serial);
        try {
            ScheduledFuture<?> future = scheduler.get().schedule(() -> completeGracePeriod(serial),
                    GRACE_PERIOD_MS, TimeUnit.MILLISECONDS);
            gracePeriodTasks.put(serial, future);
        } catch (RejectedExecutionException e) {
            deviceStates.remove(serial);
            adbClient.onDisconnected(serial);
            log.warn("[{}] Unable to schedule grace period: {}", serial, e.getMessage());
        }
    }

    // Keep AdbClient grace-gate updates outside serial locks so tracker and sync
    // workers never acquire locks in opposite orders.
    private void completeGracePeriod(String serial) {
        DeviceValidationResult disconnectedValidation = null;
        boolean disconnected;

        Object lock = serialLock(serial);
        synchronized (lock) {
            disconnected = deviceStates.remove(serial, DeviceState.DISCONNECTING);
            if (disconnected) {
                log.info("[{}] Grace period expired, publishing DISCONNECTED", serial);
                disconnectedValidation = validationResults.remove(serial);
            }
            gracePeriodTasks.remove(serial);
        }

        if (disconnected) {
            adbClient.onDisconnected(serial);
            eventPublisher.publishEvent(
                    new DeviceEvent(serial, DeviceEventType.DISCONNECTED, disconnectedValidation));
        } else {
            adbClient.onReconnected(serial);
        }
    }

    private void cancelGraceTask(String serial) {
        ScheduledFuture<?> future = gracePeriodTasks.remove(serial);
        if (future != null) {
            future.cancel(false);
        }
    }

    private Object serialLock(String serial) {
        return serialLocks.computeIfAbsent(serial, key -> new Object());
    }
}
