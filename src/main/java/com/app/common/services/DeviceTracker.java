package com.app.common.services;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.common.dtos.DeviceEvent;
import com.app.common.dtos.DeviceValidationResult;

@Component
public class DeviceTracker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeviceTracker.class);

    private final AdbClient adbClient;
    private final DeviceValidationService deviceValidationService;
    private final Set<String> currentDevices = ConcurrentHashMap.newKeySet();
    // Caches latest validation results for currently connected devices so listeners
    // can receive replayed CONNECTED events without repeating validate.
    private final Map<String, DeviceValidationResult> knownResults = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean running;
    private final List<Consumer<DeviceEvent>> listeners = new CopyOnWriteArrayList<>();

    // Holds the active adb track-devices process so shutdown() can destroy it
    // immediately without waiting for the read loop to time out.
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    public DeviceTracker(AdbClient adbClient,
            DeviceValidationService deviceValidationService) {
        this.adbClient = adbClient;
        this.deviceValidationService = deviceValidationService;
    }

    @Override
    public void run() {
        if (!started.compareAndSet(false, true)) {
            log.warn("DeviceTracker is already running; ignore duplicate start");
            return;
        }

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

    /**
     * Registers a listener for device connection events.
     * On registration, replays cached connect events for all already-known devices
     * so validating won't repeat itself.
     */
    public void addListener(Consumer<DeviceEvent> listener) {
        listeners.add(listener);
        Map<String, DeviceValidationResult> snapshot = new HashMap<>(knownResults);
        if (snapshot.isEmpty()) {
            return;
        }
        Thread replay = new Thread(() -> {
            for (Map.Entry<String, DeviceValidationResult> entry : snapshot.entrySet()) {
                safeAccept(listener,
                        new DeviceEvent(entry.getKey(), DeviceEvent.EventType.CONNECTED, entry.getValue()));
            }
        }, "device-tracker-login-replay");
        replay.setDaemon(true);
        replay.start();
    }

    public void removeListener(Consumer<DeviceEvent> listener) {
        listeners.remove(listener);
    }

    // Looks up a cached validation result by hardware ID so callers can determine
    // the current connection state of a device without re-running ADB validation.
    public Optional<DeviceValidationResult> getKnownResultByHardwareId(String hardwareId) {
        if (hardwareId == null) {
            return Optional.empty();
        }
        return knownResults.values().stream()
                .filter(r -> hardwareId.equals(r.getHardwareId()))
                .findFirst();
    }

    // Marks a connected device as saved in the known cache right after
    // persistence succeeds, so later replays carry the correct saved state.
    public void markKnownAsSaved(DeviceValidationResult result) {
        if (result == null || !result.isValid() || result.getHardwareId() == null) {
            return;
        }

        DeviceValidationResult saved = DeviceValidationResult.valid(
                result.getHardwareId(),
                result.getMatchedWhitelistId(),
                result.getMatchedModelName(),
                result.getAccountUserId(),
                true);
        knownResults.put(saved.getHardwareId(), saved);
    }

    // Signals the tracker to stop and destroys the active adb process so the
    // OS-level process exits instead of being abandoned in the task manager.
    public void shutdown() {
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

    // Diff the current device set against the live snapshot and fire
    // connect/disconnect events only for devices that actually changed state.
    private void handle(List<String> newDevices) {
        Set<String> newSet = new HashSet<>(newDevices);

        for (String serial : newSet) {
            if (!currentDevices.contains(serial)) {
                DeviceValidationResult result = validateSerialSafely(serial);
                knownResults.put(serial, result);
                fire(new DeviceEvent(serial, DeviceEvent.EventType.CONNECTED, result));
            }
        }

        for (String serial : new HashSet<>(currentDevices)) {
            if (!newSet.contains(serial)) {
                knownResults.remove(serial);
                fire(new DeviceEvent(serial, DeviceEvent.EventType.DISCONNECTED, null));
            }
        }

        currentDevices.clear();
        currentDevices.addAll(newSet);
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

    private void fire(DeviceEvent event) {
        for (Consumer<DeviceEvent> listener : listeners) {
            safeAccept(listener, event);
        }
    }

    private void safeAccept(Consumer<DeviceEvent> listener, DeviceEvent event) {
        try {
            listener.accept(event);
        } catch (Exception e) {
            log.warn("Device event listener failed for hardwareId {}: {}", event.hardwareId(), e.getMessage());
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
