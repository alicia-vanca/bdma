package com.app.common.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.exceptions.AppException;
import com.app.common.exceptions.DeviceDisconnectedException;

import jakarta.annotation.PreDestroy;

@Service
public class AdbClient {

    // -------------------------------------------------------------------------
    // Constants & fields
    // -------------------------------------------------------------------------

    private static final Logger log = LoggerFactory.getLogger(AdbClient.class);
    private static final Duration ADB_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration FIND_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration QUICK_TIMEOUT = Duration.ofSeconds(10);
    private static final String ADB_SHELL = "shell";
    public static final String ERR_DEVICE_NOT_FOUND = "ADB_DEVICE_NOT_FOUND";
    private static final Pattern GETPROP_PATTERN = Pattern.compile("^\\[(.+)]\\s*:\\s*\\[(.*)]$");
    private static final String CAMERA_SERVICE_COMPONENT = "com.bodycamera.nettysocket/com.recoda.bodycamera.service.CameraService";
    private final AdbRuntimeService adbRuntimeService;
    private final Map<String, GraceGate> graceGates = new ConcurrentHashMap<>();
    private final AtomicReference<Predicate<String>> serialPreflightChecker = new AtomicReference<>(serial -> true);

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private enum SerialState {
        CONNECTED,
        IN_GRACE,
        DISCONNECTED
    }

    private static final class GraceGate {
        private SerialState state = SerialState.CONNECTED;
    }

    private record RunResult(int exitCode, String output) {
    }

    // -------------------------------------------------------------------------
    // Construction & lifecycle
    // -------------------------------------------------------------------------

    public AdbClient(AdbRuntimeService adbRuntimeService) {
        this.adbRuntimeService = adbRuntimeService;
    }

    @PreDestroy
    public void killServer() {
        try {
            String adbExecutable = adbRuntimeService.resolveAdbExecutable();
            Process process = new ProcessBuilder(adbExecutable, "kill-server")
                    .redirectErrorStream(true)
                    .start();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
                log.info("ADB server killed on shutdown");
            } finally {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Failed to kill ADB server on shutdown", e);
        }
    }

    // -------------------------------------------------------------------------
    // Grace-gate events (called by DeviceTracker)
    // -------------------------------------------------------------------------

    public void onGraceStarted(String serial) {
        if (serial == null || serial.isBlank())
            return;
        GraceGate gate = graceGates.computeIfAbsent(serial, key -> new GraceGate());
        synchronized (gate) {
            gate.state = SerialState.IN_GRACE;
            gate.notifyAll();
        }
    }

    public void onReconnected(String serial) {
        if (serial == null || serial.isBlank())
            return;
        GraceGate gate = graceGates.computeIfAbsent(serial, key -> new GraceGate());
        synchronized (gate) {
            gate.state = SerialState.CONNECTED;
            gate.notifyAll();
        }
    }

    public void onDisconnected(String serial) {
        if (serial == null || serial.isBlank())
            return;
        GraceGate gate = graceGates.computeIfAbsent(serial, key -> new GraceGate());
        synchronized (gate) {
            gate.state = SerialState.DISCONNECTED;
            gate.notifyAll();
        }
    }

    public void setSerialPreflightChecker(Predicate<String> serialPreflightChecker) {
        this.serialPreflightChecker.set(Objects.requireNonNullElse(serialPreflightChecker, serial -> true));
    }

    // -------------------------------------------------------------------------
    // Execution helpers
    // -------------------------------------------------------------------------

    private boolean waitUntilGraceResolved(String serial) {
        if (serial == null || serial.isBlank()) {
            return true;
        }

        GraceGate gate = graceGates.computeIfAbsent(serial, key -> new GraceGate());
        synchronized (gate) {
            if (gate.state == SerialState.CONNECTED && !serialPreflightChecker.get().test(serial)) {
                // Manual check before starting a command. Because tracker event has a delay
                // after device actually disconnects, this reduces the chance of starting a
                // command that will fail due to disconnection
                onGraceStarted(serial);
            }
            while (gate.state == SerialState.IN_GRACE) {
                try {
                    gate.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            if (gate.state == SerialState.DISCONNECTED) {
                if (log.isDebugEnabled()) {
                    log.debug("Skip adb command because device is disconnected: {}", serial);
                }
                return false;
            }
            return true;
        }
    }

    private String run(String serial, String... args) {
        return runWithTimeout(serial, null, args).output();
    }

    private RunResult runWithTimeout(String serial, Duration timeout, String... args) {
        if (!waitUntilGraceResolved(serial)) {
            throw new DeviceDisconnectedException("ADB command skipped: disconnected " + serial);
        }

        return executeAdbCommand(serial, timeout, args);
    }

    private RunResult executeAdbCommand(String serial, Duration timeout, String... args) {
        return executeAdbCommand(serial, timeout, false, args);
    }

    /**
     * Execute ADB command with optional retry on MTP interference.
     * When camera service triggers MTP mode, external storage becomes inaccessible
     * via ADB.
     * This method detects "No such file" errors and stops camera service before
     * retrying.
     */
    private RunResult executeAdbCommand(String serial, Duration timeout, boolean isRetry, String... args) {
        List<String> command = buildAdbCommand(serial, args);
        String label = String.join(" ", args);
        Duration effectiveTimeout = timeout != null ? timeout : ADB_TIMEOUT;

        try {
            RunResult result = runAdbProcess(command, label, effectiveTimeout);
            return handleAdbResult(serial, timeout, isRetry, args, label, result);
        } catch (IOException e) {
            throw new AppException("ADB IO error: " + label, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("ADB interrupted: " + label, e);
        }
    }

    private List<String> buildAdbCommand(String serial, String... args) {
        String adbExecutable = adbRuntimeService.resolveAdbExecutable();
        List<String> command = new ArrayList<>();
        command.add(adbExecutable);
        Collections.addAll(command, args);

        if (serial != null && !serial.isBlank()) {
            command.add(1, "-s");
            command.add(2, serial);
        }
        return command;
    }

    private RunResult runAdbProcess(List<String> command, String label, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        AtomicReference<IOException> streamError = new AtomicReference<>();

        Thread drainer = Thread.ofPlatform().name("adb-drain-" + label).start(() -> {
            try {
                process.getInputStream().transferTo(sink);
            } catch (IOException e) {
                streamError.set(e);
            }
        });

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            drainer.join(500);
            throw new AppException("ADB timeout (" + timeout.toSeconds() + "s): " + label);
        }

        drainer.join(1000);
        if (streamError.get() != null)
            throw streamError.get();

        int exitCode = process.exitValue();
        String output = sink.toString(StandardCharsets.UTF_8).trim();
        return new RunResult(exitCode, output);
    }

    private RunResult handleAdbResult(String serial, Duration timeout, boolean isRetry,
            String[] args, String label, RunResult result) {
        logAdbResult(label, result);

        if (shouldRetryForMtpInterference(serial, isRetry, result)) {
            log.warn("[{}] MTP interference detected, stopping camera service and retrying: {}", serial, label);
            if (stopCameraService(serial)) {
                setUsbFunctionsNone(serial);
                if (!waitUntilGraceResolved(serial)) {
                    throw new DeviceDisconnectedException(
                            "ADB command skipped after MTP recovery: disconnected " + serial);
                }
                return executeAdbCommand(serial, timeout, true, args);
            }
        }
        return result;
    }

    private boolean shouldRetryForMtpInterference(String serial, boolean isRetry, RunResult result) {
        return !isRetry
                && result.exitCode() != 0
                && serial != null
                && !serial.isBlank()
                && result.output().toLowerCase().contains("no such file or directory");
    }

    private void logAdbResult(String label, RunResult result) {
        if (result.exitCode() != 0 && log.isWarnEnabled()) {
            log.warn("ADB command failed: {} (exitCode={}){}",
                    label, result.exitCode(),
                    result.output().isEmpty() ? "" : ", output=" + result.output());
        } else if (log.isDebugEnabled() && !result.output().isEmpty()) {
            // log.debug("ADB command output for {}: {}", label, result.output());
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public List<String> listConnectedSerials() {
        String output = run(null, "devices");
        List<String> serials = new ArrayList<>();

        String[] lines = output.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("List of devices attached")) {
                continue;
            }

            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2 && "device".equals(parts[1])) {
                serials.add(parts[0]);
            }
        }
        return serials;
    }

    public String getAdbPath() {
        try {
            return adbRuntimeService.resolveAdbExecutable();
        } catch (Exception e) {
            log.error("ADB path is null - cannot retrieve devices");
            return null;
        }
    }

    public Map<String, String> getProps(String serial) {
        try {
            String output = run(serial, ADB_SHELL, "getprop");
            Map<String, String> props = new HashMap<>();

            String[] lines = output.split("\\R");
            for (String line : lines) {
                Matcher matcher = GETPROP_PATTERN.matcher(line.trim());
                if (!matcher.matches()) {
                    continue;
                }
                props.put(matcher.group(1).trim(), matcher.group(2).trim());
            }

            return props;
        } catch (Exception e) {
            log.error("Failed to read device properties for {}", serial, e);
            return Map.of();
        }
    }

    public boolean fileExists(String serial, String path) {
        try {
            String output = run(serial, ADB_SHELL, "ls", "-1", path);
            String trimmed = output.trim();
            if (trimmed.isBlank()) {
                return false;
            }

            return !(trimmed.contains("no such file")
                    || trimmed.contains("cannot access")
                    || trimmed.contains("not found"));
        } catch (Exception e) {
            log.error("Failed to check remote file {} on {}", path, serial, e);
            return false;
        }
    }

    public String readTextFile(String serial, String path) {
        try {
            return run(serial, ADB_SHELL, "cat", path);
        } catch (Exception e) {
            log.error("Failed to read remote file {} on {}", path, serial, e);
            return "";
        }
    }

    public Process startAdbProcess(String... args) {
        String adbExecutable = adbRuntimeService.resolveAdbExecutable();
        List<String> command = new ArrayList<>();
        command.add(adbExecutable);
        Collections.addAll(command, args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            return pb.start();
        } catch (IOException e) {
            throw new AppException("Failed to start adb process: " + String.join(" ", command), e);
        }
    }

    public List<String> findFiles(String serial, String root, List<String> types) {
        List<String> files = new ArrayList<>();
        for (String type : types) {
            String target = root + "/" + type;
            try {
                RunResult result = runWithTimeout(serial, FIND_TIMEOUT, ADB_SHELL, "find", target, "-type", "f");
                String[] lines = result.output().split("\\R");
                for (String line : lines) {
                    if (!line.isBlank() && !line.startsWith("find:")) {
                        files.add(line.trim());
                    }
                }
            } catch (DeviceDisconnectedException e) {
                throw e;
            } catch (AppException e) {
                log.warn("find failed for path: {}", target, e);
            }
        }
        return files;
    }

    public String getExternalStorage(String serial) {
        try {
            RunResult result = runWithTimeout(serial, QUICK_TIMEOUT, ADB_SHELL, "ls", "/storage");
            if (result.exitCode() != 0) {
                return null;
            }

            String[] lines = result.output().split("\\R");
            for (String line : lines) {
                if (line.matches("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")) {
                    return "/storage/" + line;
                }
            }
        } catch (DeviceDisconnectedException e) {
            throw e;
        } catch (AppException e) {
            log.debug("Failed to resolve external storage for {}", serial, e);
        }

        return null;
    }

    public long getRemoteSize(String serial, String remote) {
        try {
            RunResult result = runWithTimeout(serial, QUICK_TIMEOUT, ADB_SHELL, "stat", "-c", "%s", remote);
            if (result.exitCode() != 0) {
                return -1;
            }

            String[] lines = result.output().split("\\R");
            String line = lines.length > 0 ? lines[0].trim() : "";
            return (line != null && line.matches("\\d+")) ? Long.parseLong(line) : -1;
        } catch (DeviceDisconnectedException e) {
            throw e;
        } catch (AppException e) {
            log.debug("getRemoteSize failed {} on {}", remote, serial, e);
            return -1;
        }
    }

    public boolean pullFile(String serial, String remote, String local) {
        RunResult result = runWithTimeout(serial, PULL_TIMEOUT, "pull", remote, local);
        if (result.exitCode() != 0) {
            String output = result.output().toLowerCase();
            if (result.exitCode() == 1 || output.contains("not found")
                    || output.contains("no devices/emulators found")) {
                throw new AppException(ERR_DEVICE_NOT_FOUND + ": " + result.output().trim());
            }
            return false;
        }
        return true;
    }

    public boolean deleteRemoteFile(String serial, String remote) {
        try {
            return runWithTimeout(serial, QUICK_TIMEOUT, ADB_SHELL, "rm", "-f", remote).exitCode() == 0;
        } catch (AppException e) {
            log.debug("deleteRemoteFile failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isDeviceAlive(String serial) {
        try {
            RunResult result = executeAdbCommand(serial, QUICK_TIMEOUT, "get-state");
            if (result.exitCode() != 0) {
                return false;
            }

            String[] lines = result.output().split("\\R");
            String line = lines.length > 0 ? lines[0].trim() : "";
            return "device".equals(line);

        } catch (AppException e) {
            log.debug("isDeviceAlive failed for {}", serial, e);
            return false;
        }
    }

    public boolean stopCameraService(String serial) {
        log.debug("[{}] Sending stop Camera service command", serial);

        // Poll until service is confirmed stopped
        long deadline = System.currentTimeMillis() + QUICK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            // Check if service is already stopped
            if (!isCameraServiceRunning(serial)) {
                log.debug("[{}] Camera service confirmed stopped", serial);
                return true;
            }
            // Stop request
            try {
                runWithTimeout(serial, QUICK_TIMEOUT, ADB_SHELL,
                        "am", "stopservice",
                        "-n", CAMERA_SERVICE_COMPONENT);
            } catch (AppException e) {
                log.warn("[{}] stopCameraService failed: {}", serial, e.getMessage());
                return false;
            }

            // Wait a bit before next check
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.warn("[{}] Camera service still running after stop timeout", serial);
        return false;
    }

    public boolean startCameraService(String serial) {
        try {
            boolean result = runWithTimeout(serial, QUICK_TIMEOUT, ADB_SHELL,
                    "am", "startservice",
                    "-n", CAMERA_SERVICE_COMPONENT).exitCode() == 0;
            log.info("[{}] Camera service restored successfully", serial);
            return result;
        } catch (AppException e) {
            log.warn("[{}] startCameraService failed: {}", serial, e.getMessage());
            return false;
        }
    }

    public boolean setUsbFunctionsNone(String serial) {
        try {
            return runWithTimeout(serial, QUICK_TIMEOUT, ADB_SHELL,
                    "svc", "usb", "setFunctions", "none").exitCode() == 0;
        } catch (AppException e) {
            log.warn("[{}] setUsbFunctionsNone failed: {}", serial, e.getMessage());
            return false;
        }
    }

    private boolean isCameraServiceRunning(String serial) {
        try {
            RunResult result = runWithTimeout(serial, QUICK_TIMEOUT, ADB_SHELL,
                    "dumpsys", "activity", "services",
                    CAMERA_SERVICE_COMPONENT);
            return result.output().contains("ServiceRecord");
        } catch (AppException e) {
            log.debug("[{}] isCameraServiceRunning check failed: {}", serial, e.getMessage());
            return false;
        }
    }

}
