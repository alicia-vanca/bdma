package com.app.common.modules.device.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.app.common.definitions.enums.ServiceRulePhase;
import com.app.common.models.ServiceRule;
import com.app.common.services.DeviceServiceRuleService;
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
    private static final String NOT_FOUND_OUTPUT = "not found";
    private static final String DEVICE_NOT_FOUND_OUTPUT = "device not found";
    public static final String ERR_DEVICE_NOT_FOUND = "ADB_DEVICE_NOT_FOUND";
    private static final Pattern GETPROP_PATTERN = Pattern.compile("^\\[([^]]+)]\\s*:\\s*\\[([^]]*)]$");
    private final AdbRuntimeService adbRuntimeService;
    private final DeviceServiceRuleService deviceServiceRuleService;
    private final DeviceListState deviceListState;
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

    public AdbClient(AdbRuntimeService adbRuntimeService, DeviceServiceRuleService deviceServiceRuleService,
            DeviceListState deviceListState) {
        this.adbRuntimeService = adbRuntimeService;
        this.deviceServiceRuleService = deviceServiceRuleService;
        this.deviceListState = deviceListState;
    }

    @PreDestroy
    public void killServer() {
        try {
            String adbExecutable = adbRuntimeService.resolveAdbExecutable();
            boolean killServerCommandCompleted = requestAdbServerShutdown(adbExecutable);
            if (!killServerCommandCompleted) {
                log.warn("ADB kill-server command did not stop cleanly; forcing bundled ADB processes to exit");
            }

            int forcedProcessCount = terminateBundledAdbProcesses(adbExecutable);
            List<ProcessHandle> remainingProcesses = waitForBundledAdbProcessesToExit(adbExecutable);
            if (remainingProcesses.isEmpty()) {
                log.info("Bundled ADB shutdown verified. killServerCommandCompleted={}, forcedProcessCount={}",
                        killServerCommandCompleted, forcedProcessCount);
            } else {
                log.error("Bundled ADB shutdown incomplete. Remaining process IDs: {}",
                        remainingProcesses.stream().map(ProcessHandle::pid).toList());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while shutting down bundled ADB", e);
        } catch (Exception e) {
            log.warn("Failed to kill ADB server on shutdown", e);
        }
    }

    private boolean requestAdbServerShutdown(String adbExecutable) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(adbExecutable, "kill-server")
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            return false;
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            log.warn("ADB kill-server exited with code {}: {}", exitCode, output);
            return false;
        }
        return true;
    }

    private int terminateBundledAdbProcesses(String adbExecutable) {
        Path bundledAdbPath = Path.of(adbExecutable).toAbsolutePath().normalize();
        List<ProcessHandle> bundledAdbProcesses = findBundledAdbProcesses(bundledAdbPath);

        for (ProcessHandle process : bundledAdbProcesses) {
            forceStopBundledAdbProcess(process);
        }
        return bundledAdbProcesses.size();
    }

    private List<ProcessHandle> waitForBundledAdbProcessesToExit(String adbExecutable) throws InterruptedException {
        Path bundledAdbPath = Path.of(adbExecutable).toAbsolutePath().normalize();
        for (int attempt = 0; attempt < 10; attempt++) {
            List<ProcessHandle> remainingProcesses = findBundledAdbProcesses(bundledAdbPath);
            if (remainingProcesses.isEmpty()) {
                return remainingProcesses;
            }
            for (ProcessHandle process : remainingProcesses) {
                forceStopBundledAdbProcess(process);
            }
            Thread.sleep(100);
        }
        return findBundledAdbProcesses(bundledAdbPath);
    }

    private void forceStopBundledAdbProcess(ProcessHandle process) {
        process.descendants().forEach(this::forceStopProcess);
        forceStopProcess(process);
    }

    private void forceStopProcess(ProcessHandle process) {
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (SecurityException e) {
            log.warn("Unable to force-stop bundled ADB process {}", process.pid(), e);
        }
    }

    private List<ProcessHandle> findBundledAdbProcesses(Path bundledAdbPath) {
        return ProcessHandle.allProcesses()
                .filter(process -> isBundledAdbProcess(process, bundledAdbPath))
                .toList();
    }

    private boolean isBundledAdbProcess(ProcessHandle process, Path bundledAdbPath) {
        return process.info().command()
                .map(command -> Path.of(command).toAbsolutePath().normalize().toString()
                        .equalsIgnoreCase(bundledAdbPath.toString()))
                .orElse(false);
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
        boolean shouldPreflight;
        synchronized (gate) {
            shouldPreflight = gate.state == SerialState.CONNECTED;
        }

        // Run tracker preflight outside GraceGate so ADB and tracker never acquire
        // GraceGate -> serialLock in one thread while another thread can do the
        // reverse.
        boolean preflightPassed = !shouldPreflight || serialPreflightChecker.get().test(serial);

        synchronized (gate) {
            if (gate.state == SerialState.CONNECTED && !preflightPassed) {
                // Manual check before starting a command. Because tracker event has a delay
                // after device actually disconnects, this reduces the chance of starting a
                // command that will fail due to disconnection.
                gate.state = SerialState.IN_GRACE;
                gate.notifyAll();
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
        return executeAdbCommand(serial, null, args).output();
    }

    private RunResult executeAdbCommand(String serial, Duration timeout, String... args) {
        return executeAdbCommand(serial, timeout, false, false, args);
    }

    private RunResult executeAdbCommandIgnoringGrace(String serial, Duration timeout, String... args) {
        return executeAdbCommand(serial, timeout, true, false, args);
    }

    /**
     * Execute ADB command with optional retry on device loss or MTP interference.
     * Health and stability probes can bypass the grace gate with ignoreGrace
     * because they are used to decide whether the device is stable enough for
     * normal work.
     */
    private RunResult executeAdbCommand(String serial, Duration timeout, boolean ignoreGrace, boolean isRetry,
            String... args) {
        if (!ignoreGrace && !waitUntilGraceResolved(serial)) {
            throw new DeviceDisconnectedException("ADB command skipped: disconnected " + serial);
        }

        List<String> command = buildAdbCommand(serial, args);
        String label = String.join(" ", args);
        Duration effectiveTimeout = timeout != null ? timeout : ADB_TIMEOUT;

        try {
            RunResult result = runAdbProcess(command, label, effectiveTimeout);
            if (ignoreGrace || isStopServiceConfirmedOutput(label, result)
                    || isUsbFunctionsNoneReconnectExit(label, result)) {
                // Raw probes must not trigger grace, MTP recovery, or normal command
                // failure logging because tracker uses their direct ADB result to decide state.
                return result;
            }
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
        boolean retryForDeviceUnavailable = shouldRetryForDeviceUnavailable(serial, isRetry, result, args);
        boolean retryForMtp = shouldRetryForMtpInterference(serial, isRetry, result, args);
        if (!retryForDeviceUnavailable && (!isFindTargetDirectoryMissing(args, result) || retryForMtp)) {
            logAdbResult(label, result);
        }

        if (retryForDeviceUnavailable) {
            log.info("[{}] ADB device unavailable during command, entering grace before retry: {}", serial, label);
            serialPreflightChecker.get().test(serial);
            if (!waitUntilGraceResolved(serial)) {
                throw new DeviceDisconnectedException(
                        "ADB command skipped after transient disconnect: disconnected " + serial);
            }
            log.info("[{}] Device reconnected after grace, retrying command: {}", serial, label);
            return executeAdbCommand(serial, timeout, false, true, args);
        }

        if (retryForMtp) {
            log.warn("[{}] MTP interference detected, stopping camera service", serial);
            if (runPreSyncServiceRules(serial)) {
                setUsbFunctionsNone(serial);
                if (!waitUntilGraceResolved(serial)) {
                    throw new DeviceDisconnectedException(
                            "ADB command skipped after MTP recovery: disconnected " + serial);
                }
                log.info("[{}] Camera service stopped, retrying command: {}", serial, label);
                return executeAdbCommand(serial, timeout, false, true, args);
            }
        }
        return result;
    }

    private boolean shouldRetryForDeviceUnavailable(String serial, boolean isRetry, RunResult result, String[] args) {
        return !isRetry
                && serial != null
                && !serial.isBlank()
                && !isGetStateCommand(args)
                && result.exitCode() != 0
                && (isAdbDeviceUnavailableOutput(result.output().toLowerCase())
                        || isUnexpectedAdbShellExit255(result, args));
    }

    /**
     * ADB shell can exit with 255 when a device disappears after a command already
     * streamed partial output. The caller will then run tracker preflight, which
     * performs the real get-state check before waiting for grace and retrying.
     */
    private boolean isUnexpectedAdbShellExit255(RunResult result, String[] args) {
        return result.exitCode() == 255
                && args.length > 0
                && ADB_SHELL.equals(args[0]);
    }

    /**
     * Excludes the raw ADB health probe from grace retry handling. The tracker uses
     * this command to decide whether grace should start, so retrying it through the
     * same grace path can recursively call the preflight checker.
     */
    private boolean isGetStateCommand(String[] args) {
        return args.length == 1 && "get-state".equals(args[0]);
    }

    private boolean runPreSyncServiceRules(String serial) {
        Optional<List<ServiceRule>> rules = resolveServiceRules(serial, ServiceRulePhase.BEFORE_SYNC);
        if (rules.isEmpty()) {
            return false;
        }

        executeServiceRules(serial, rules.get(), ServiceRulePhase.BEFORE_SYNC);
        return true;
    }

    private Optional<List<ServiceRule>> resolveServiceRules(String serial, ServiceRulePhase phase) {
        Optional<Long> whitelistId = deviceListState.findWhitelistIdByHardwareId(serial);
        if (whitelistId.isEmpty()) {
            log.warn("[{}] No whitelist context found for service rules: phase={}", serial, phase);
            return Optional.empty();
        }
        return Optional.of(deviceServiceRuleService.resolveRules(whitelistId.get(), phase));
    }

    private boolean shouldRetryForMtpInterference(String serial, boolean isRetry, RunResult result, String[] args) {
        if (isRetry
                || result.exitCode() == 0
                || serial == null
                || serial.isBlank()
                || !result.output().toLowerCase().contains("no such file or directory")) {
            return false;
        }

        if (isFindTargetDirectoryMissing(args, result)) {
            return !canCreateMissingFindTarget(serial, args);
        }

        return true;
    }

    /**
     * Detects expected failures from commands like `adb shell find /path -type f`
     * when the target directory does not exist. Missing media folders are not ADB
     * command failures by themselves, so normal warning logs are suppressed unless
     * the missing path also proves storage/MTP recovery is needed.
     */
    private boolean isFindTargetDirectoryMissing(String[] args, RunResult result) {
        return args.length >= 4
                && ADB_SHELL.equals(args[0])
                && "find".equals(args[1])
                && result.output().startsWith("find:")
                && result.output().contains("No such file or directory");
    }

    private boolean canCreateMissingFindTarget(String serial, String[] args) {
        String target = args[2];
        String label = ADB_SHELL + " mkdir -p " + target;
        try {
            RunResult mkdirResult = runAdbProcess(
                    buildAdbCommand(serial, ADB_SHELL, "mkdir", "-p", target),
                    label,
                    QUICK_TIMEOUT);
            boolean created = mkdirResult.exitCode() == 0;
            if (created) {
                log.debug("[{}] Missing media folder created, partition ok, skip MTP recovery: {}", serial, target);
            } else {
                log.debug(
                        "[{}] Missing media folder create failed, partition failed, allow MTP recovery: {} (exitCode={}, output={})",
                        serial, target, mkdirResult.exitCode(), mkdirResult.output());
            }
            return created;
        } catch (IOException e) {
            log.debug("[{}] Missing media folder create IO failed, allow MTP recovery: {}", serial, target, e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void logAdbResult(String label, RunResult result) {
        if (result.exitCode() != 0 && log.isWarnEnabled()) {
            log.warn("ADB command failed: {}\n(exitCode={}){}",
                    label, result.exitCode(),
                    result.output().isEmpty() ? "" : "\n(output=" + result.output() + ")");
        }
    }

    private boolean isStopServiceConfirmedOutput(String label, RunResult result) {
        return label.startsWith(ADB_SHELL + " am stopservice ")
                && result.output().toLowerCase().contains("service stopped");
    }

    private boolean isUsbFunctionsNoneReconnectExit(String label, RunResult result) {
        // Setting USB functions to none can cause a temporary disconnect that results
        // in an exit code of 255.
        return result.exitCode() == 255
                && label.equals(ADB_SHELL + " svc usb setFunctions none");
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
        } catch (DeviceDisconnectedException e) {
            log.debug("Skip reading device properties because device is disconnected: {}", serial);
            throw e;
        } catch (Exception e) {
            log.error("Failed to read device properties for {}", serial, e);
            return Map.of();
        }
    }

    public Map<String, String> getBatteryInfo(String serial) {
        try {
            String output = run(serial, ADB_SHELL, "dumpsys", "battery");
            if (output == null || output.isBlank()) {
                return Map.of();
            }
            Map<String, String> result = new HashMap<>();

            for (String line : output.split("\\R")) {
                int separatorIndex = line.indexOf(':');
                if (separatorIndex > 0) {
                    result.put(line.substring(0, separatorIndex).trim(), line.substring(separatorIndex + 1).trim());
                }
            }
            return result;
        } catch (DeviceDisconnectedException e) {
            log.debug("Skip reading battery info because device is disconnected: {}", serial);
            return Map.of();
        } catch (Exception e) {
            log.warn("Failed to read battery info for {}", serial, e);
            return Map.of();
        }
    }

    public Map<String, String> getIntStorageInfo(String serial) {
        return getStorageInfo(serial, "/storage/emulated");
    }

    public Map<String, String> getExtStorageInfo(String serial) {
        String extStorage = getExternalStorage(serial);
        if (extStorage == null) {
            return Map.of();
        }
        return getStorageInfo(serial, extStorage);
    }

    public Map<String, String> getStorageInfo(String serial, String folderPath) {
        try {
            String output = executeAdbCommandIgnoringGrace(serial, QUICK_TIMEOUT, ADB_SHELL, "df", folderPath).output();
            if (output == null || output.isBlank()) {
                return Map.of();
            }
            String[] lines = output.split("\\R");
            if (lines.length < 2) {
                return Map.of();
            }

            String[] headers = lines[0].trim().split("\\s+");

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.startsWith("/")) {
                    continue;
                }

                String[] values = line.split("\\s+");
                Map<String, String> result = new LinkedHashMap<>();
                for (int j = 0; j < Math.min(headers.length, values.length); j++) {
                    result.put(headers[j], values[j]);
                }
                return result;
            }
            return Map.of();
        } catch (DeviceDisconnectedException e) {
            log.debug("Skip reading storage info because device is disconnected: {} {}", serial, folderPath);
            return Map.of();
        } catch (Exception e) {
            log.warn("Failed to read storage info for {} {}", serial, folderPath, e);
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

            String lower = trimmed.toLowerCase();
            return !(lower.contains("no such file")
                    || lower.contains("cannot access")
                    || lower.contains(NOT_FOUND_OUTPUT));
        } catch (DeviceDisconnectedException e) {
            log.debug("Skip checking remote file because device is disconnected: {} on {}", path, serial);
            throw e;
        } catch (Exception e) {
            log.error("Failed to check remote file {} on {}", path, serial, e);
            return false;
        }
    }

    private boolean isAdbDeviceUnavailableOutput(String output) {
        return output.contains(DEVICE_NOT_FOUND_OUTPUT)
                || output.matches("(?s).*device '[^']+' not found.*")
                || output.contains("no devices/emulators found")
                || output.contains("device offline");
    }

    public String readTextFile(String serial, String path) {
        try {
            RunResult result = executeAdbCommand(serial, QUICK_TIMEOUT, ADB_SHELL, "cat", path);
            if (result.exitCode() != 0) {
                return "";
            }
            return result.output();
        } catch (DeviceDisconnectedException e) {
            log.debug("Skip reading remote file because device is disconnected: {} on {}", path, serial);
            throw e;
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
                RunResult result = executeAdbCommand(serial, FIND_TIMEOUT, ADB_SHELL, "find", target, "-type", "f");
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
            RunResult result = executeAdbCommand(serial, QUICK_TIMEOUT, ADB_SHELL, "ls", "/storage");
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
            RunResult result = executeAdbCommand(serial, QUICK_TIMEOUT, ADB_SHELL, "stat", "-c", "%s", remote);
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
        RunResult result = executeAdbCommand(serial, PULL_TIMEOUT, "pull", remote, local);
        if (result.exitCode() != 0) {
            String output = result.output().toLowerCase();
            if (result.exitCode() == 1 || output.contains(NOT_FOUND_OUTPUT)
                    || output.contains("no devices/emulators found")) {
                throw new AppException(ERR_DEVICE_NOT_FOUND + ": " + result.output().trim());
            }
            return false;
        }
        return true;
    }

    public boolean deleteRemoteFile(String serial, String remote) {
        log.info("[{}] Remote file delete requested: {}", serial, remote);
        try {
            RunResult result = executeAdbCommand(serial, QUICK_TIMEOUT, ADB_SHELL, "rm", "-f", remote);
            boolean success = result.exitCode() == 0;
            log.info("[{}] Remote file delete completed: path={}, exitCode={}, success={}",
                    serial, remote, result.exitCode(), success);
            return success;
        } catch (AppException e) {
            log.warn("[{}] Remote file delete failed: path={}, error={}", serial, remote, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Checks whether a remote directory is empty, then deletes it using rmdir.
     *
     * @param serial ADB device serial
     * @param path   remote directory path
     * @return {@code true} if the directory was deleted
     */
    public boolean deleteRemoteDirectoryIfEmpty(String serial, String path) {
        RunResult contents;
        try {
            contents = executeAdbCommand(serial, QUICK_TIMEOUT, ADB_SHELL, "ls", "-A", path);
        } catch (AppException e) {
            log.warn("[{}] Failed to inspect remote directory {}: {}", serial, path, e.getMessage());
            return false;
        }

        if (contents.exitCode() != 0) {
            log.warn("[{}] Failed to inspect remote directory {}: exit={}, output={}",
                    serial, path, contents.exitCode(), contents.output());
            return false;
        }
        if (!contents.output().isBlank()) {
            return false;
        }

        try {
            RunResult result = executeAdbCommand(serial, QUICK_TIMEOUT, ADB_SHELL, "rmdir", path);
            if (result.exitCode() == 0) {
                log.debug("[{}] Deleted empty remote directory: {}", serial, path);
                return true;
            }
            log.warn("[{}] Failed to delete remote directory {}: exit={}, output={}",
                    serial, path, result.exitCode(), result.output());
            return false;
        } catch (AppException e) {
            log.warn("[{}] Failed to delete remote directory {}: {}", serial, path, e.getMessage());
            return false;
        }
    }

    /**
     * Finds all date-pattern directories one level under each media type root.
     * 
     * @param serial     ADB device serial
     * @param root       storage root path (e.g., /storage/emulated/0/DCIM)
     * @param mediaTypes list of media type folder names (e.g., ["video", "image"])
     * @return list of directory paths matching find criteria
     */
    public List<String> findDateDirectories(String serial, String root, List<String> mediaTypes) {
        List<String> allDirectories = new ArrayList<>();
        for (String type : mediaTypes) {
            String typeRoot = root + "/" + type;
            try {
                RunResult result = executeAdbCommand(serial, FIND_TIMEOUT, ADB_SHELL,
                        "find", typeRoot, "-mindepth", "1", "-maxdepth", "1", "-type", "d");
                if (result.exitCode() == 0) {
                    String[] lines = result.output().split("\\R");
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (!trimmed.isBlank()) {
                            allDirectories.add(trimmed);
                        }
                    }
                } else {
                    log.debug("[{}] find date directories under {} failed: exit={}", serial, typeRoot,
                            result.exitCode());
                }
            } catch (AppException e) {
                log.debug("[{}] find date directories under {} exception: {}", serial, typeRoot, e.getMessage());
            }
        }
        return allDirectories;
    }

    /**
     * Lists all files (not directories) directly inside a remote directory
     * (non-recursive). Returns only the file names, not full paths.
     *
     * @param serial        ADB device serial
     * @param directoryPath remote directory path to inspect
     * @return list of file names inside the directory, or empty list if the
     *         directory is missing, empty, or the command fails
     */
    public List<String> listFilesInDirectory(String serial, String directoryPath) {
        try {
            RunResult result = executeAdbCommand(serial, FIND_TIMEOUT, ADB_SHELL,
                    "find", directoryPath, "-mindepth", "1", "-maxdepth", "1", "-type", "f");
            if (result.exitCode() != 0) {
                log.debug("[{}] listFilesInDirectory failed for {}: exit={}", serial, directoryPath,
                        result.exitCode());
                return List.of();
            }

            List<String> fileNames = new ArrayList<>();
            for (String line : result.output().split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank() && !trimmed.startsWith("find:")) {
                    int lastSlash = trimmed.lastIndexOf('/');
                    fileNames.add(lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed);
                }
            }
            return fileNames;
        } catch (DeviceDisconnectedException e) {
            throw e;
        } catch (AppException e) {
            log.debug("[{}] listFilesInDirectory exception for {}: {}", serial, directoryPath, e.getMessage());
            return List.of();
        }
    }


    public boolean isDeviceAlive(String serial) {
        try {
            RunResult result = executeAdbCommandIgnoringGrace(serial, QUICK_TIMEOUT, "get-state");
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

    /**
     * Executes active Android service rules for a sync phase. Failures are logged
     * and do not abort sync, preserving current best-effort service behavior.
     *
     * @param serial ADB serial for the connected device
     * @param phase  sync phase to execute
     */
    public void executeServiceRules(String serial, ServiceRulePhase phase) {
        resolveServiceRules(serial, phase)
                .ifPresent(rules -> executeServiceRules(serial, rules, phase));
    }

    private void executeServiceRules(String serial, List<ServiceRule> rules, ServiceRulePhase phase) {
        int appliedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        for (ServiceRule rule : rules) {
            if (!rule.isActive()) {
                skippedCount++;
                log.info("[{}] Skipped inactive service rule: phase={}, ruleId={}, action={}, service={}",
                        serial, phase, rule.getId(), rule.getAction(), rule.getServiceName());
            } else if (!isValidServiceRule(rule)) {
                skippedCount++;
                log.warn("[{}] Invalid service config: phase={}, ruleId={}, action={}, service={}",
                        serial, phase, rule.getId(), rule.getAction(), rule.getServiceName());
            } else {
                boolean success = executeServiceRule(serial, rule);
                if (success) {
                    appliedCount++;
                    log.info("[{}] Applied service rule: phase={}, ruleId={}, action={}, service={}",
                            serial, phase, rule.getId(), rule.getAction(), rule.getServiceName());
                } else {
                    failedCount++;
                    log.warn("[{}] Service rule failed: phase={}, ruleId={}, action={}, service={}",
                            serial, phase, rule.getId(), rule.getAction(), rule.getServiceName());
                }
            }
        }

        log.info("[{}] Service control flow completed: phase={}, applied={}, skipped={}, failed={}, total={}",
                serial, phase, appliedCount, skippedCount, failedCount, rules.size());
    }

    private boolean isValidServiceRule(ServiceRule rule) {
        return rule != null
                && rule.getAction() != null
                && rule.getServiceName() != null
                && !rule.getServiceName().isBlank();
    }

    private boolean executeServiceRule(String serial, ServiceRule rule) {
        return switch (rule.getAction()) {
            case STOP -> stopService(serial, rule.getServiceName());
            case START -> startService(serial, rule.getServiceName());
        };
    }

    /**
     * Stops an Android service component and polls until it is no longer reported
     * by activity service dumpsys.
     *
     * @param serial           ADB serial for the connected device
     * @param serviceComponent fully-qualified Android service component
     * @return true when service is confirmed stopped or already stopped
     */
    public boolean stopService(String serial, String serviceComponent) {
        // Sending stop service command

        long deadline = System.currentTimeMillis() + QUICK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!isServiceRunning(serial, serviceComponent)) {
                // Service confirmed stopped
                return true;
            }
            try {
                executeAdbCommand(serial, QUICK_TIMEOUT, ADB_SHELL,
                        "am", "stopservice",
                        "-n", serviceComponent);
            } catch (AppException e) {
                log.warn("[{}] Stop service failed for {}:\nMessage:{}", serial, serviceComponent, e.getMessage());
                return false;
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.warn("[{}] Stop service failed (timeout). Service still running: {}", serial, serviceComponent);
        return false;
    }

    /**
     * Starts an Android service component.
     *
     * @param serial           ADB serial for the connected device
     * @param serviceComponent fully-qualified Android service component
     * @return true when ADB startservice exits successfully
     */
    public boolean startService(String serial, String serviceComponent) {
        try {
            return executeAdbCommand(serial, QUICK_TIMEOUT, ADB_SHELL,
                    "am", "startservice",
                    "-n", serviceComponent).exitCode() == 0;
        } catch (AppException e) {
            log.warn("[{}] Start service failed for {}: {}", serial, serviceComponent, e.getMessage());
            return false;
        }
    }

    public boolean setUsbFunctionsNone(String serial) {
        try {
            RunResult result = executeAdbCommand(serial, QUICK_TIMEOUT, ADB_SHELL,
                    "svc", "usb", "setFunctions", "none");
            return result.exitCode() == 0
                    || isUsbFunctionsNoneReconnectExit(ADB_SHELL + " svc usb setFunctions none", result);
        } catch (AppException e) {
            log.warn("[{}] setUsbFunctionsNone failed: {}", serial, e.getMessage());
            return false;
        }
    }

    private boolean isServiceRunning(String serial, String serviceComponent) {
        try {
            RunResult result = executeAdbCommand(serial, QUICK_TIMEOUT, ADB_SHELL,
                    "dumpsys", "activity", "services",
                    serviceComponent);
            return result.output().contains("ServiceRecord");
        } catch (AppException e) {
            log.debug("[{}] isServiceRunning check failed for {}: {}", serial, serviceComponent, e.getMessage());
            return false;
        }
    }

}
