package com.app.common.services;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.exceptions.AppException;
import jakarta.annotation.PreDestroy;

@Service
public class AdbClient {

    private static final Logger log = LoggerFactory.getLogger(AdbClient.class);
    private static final Duration ADB_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration FIND_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration QUICK_TIMEOUT = Duration.ofSeconds(10);
    private static final String ADB_SHELL = "shell";
    private static final Pattern GETPROP_PATTERN = Pattern.compile("^\\[(.+)]\\s*:\\s*\\[(.*)]$");

    private final AdbRuntimeService adbRuntimeService;

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

    public List<String> listConnectedSerials() {
        String output = runAdb("devices");
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
            String output = runAdb("-s", serial, ADB_SHELL, "getprop");
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
            String output = runAdb("-s", serial, ADB_SHELL, "ls", "-1", path);
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
            return runAdb("-s", serial, ADB_SHELL, "cat", path);
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
        String adbPath = getAdbPath();
        if (adbPath == null) {
            return List.of();
        }

        List<String> command = new ArrayList<>(List.of(adbPath, "-s", serial, ADB_SHELL, "find"));
        for (String type : types) {
            command.add(root + "/" + type);
        }
        command.addAll(List.of("-type", "f"));
        List<String> result = new ArrayList<>();
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank() && !line.startsWith("find:")) {
                        result.add(line.trim());
                    }
                }
            }

            boolean finished = process.waitFor(FIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("find timed out on {} root={}", serial, root);
                return List.of();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("find error: {}", root, e);
        }

        return result;
    }

    public String getExternalStorage(String serial) {
        String adbPath = getAdbPath();
        if (adbPath == null) {
            return null;
        }

        try {
            Process process = new ProcessBuilder(adbPath, "-s", serial, ADB_SHELL, "ls /storage")
                    .redirectErrorStream(true)
                    .start();

            try {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.matches("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")) {
                            return "/storage/" + line;
                        }
                    }
                }
            } finally {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            log.debug("Failed to resolve external storage for {}", serial, e);
        }

        return null;
    }

    public long getRemoteSize(String serial, String remote) {
        String adbPath = getAdbPath();
        if (adbPath == null) {
            return -1;
        }

        try {
            Process process = new ProcessBuilder(adbPath, "-s", serial,
                    ADB_SHELL, "stat -c %s '" + remote + "'")
                    .redirectErrorStream(true)
                    .start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                line = reader.readLine();
            }
            boolean finished = process.waitFor(QUICK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("getRemoteSize timed out: {} on {}", remote, serial);
                return -1;
            }
            return (line != null && line.matches("\\d+")) ? Long.parseLong(line) : -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            log.debug("getRemoteSize failed {} on {}", remote, serial, e);
            return -1;
        }
    }

    public boolean pullFile(String serial, String remote, String local) {
        String adbPath = getAdbPath();
        if (adbPath == null) {
            return false;
        }
        ProcessBuilder pb = new ProcessBuilder(adbPath, "-s", serial, "pull", remote, local);
        try {
            return runWithTimeout(pb, PULL_TIMEOUT, "pull " + remote) == 0;
        } catch (AppException e) {
            log.error("pull failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean deleteRemoteFile(String serial, String remote) {
        String adbPath = getAdbPath();
        if (adbPath == null) {
            return false;
        }

        ProcessBuilder pb = new ProcessBuilder(
                adbPath, "-s", serial, ADB_SHELL, "rm", "-f", remote);
        try {
            return runWithTimeout(pb, QUICK_TIMEOUT, "rm " + remote) == 0;
        } catch (AppException e) {
            log.debug("deleteRemoteFile failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isDeviceAlive(String serial) {
        String adbPath = getAdbPath();
        if (adbPath == null) {
            return false;
        }

        try {
            Process process = new ProcessBuilder(adbPath, "-s", serial, "get-state")
                    .redirectErrorStream(true)
                    .start();

            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                line = reader.readLine();
            }

            boolean finished = process.waitFor(QUICK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("isDeviceAlive timed out for {}", serial);
                return false;
            }

            return "device".equals(line);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("isDeviceAlive failed for {}", serial, e);
            return false;
        }
    }

    private String runAdb(String... args) {
        String adbExecutable = adbRuntimeService.resolveAdbExecutable();

        List<String> command = new ArrayList<>();
        command.add(adbExecutable);
        Collections.addAll(command, args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            AtomicReference<IOException> streamError = new AtomicReference<>();

            Thread outputDrain = Thread.ofPlatform().name("adb-output-drain").start(() -> {
                try {
                    process.getInputStream().transferTo(outputBuffer);
                } catch (IOException e) {
                    streamError.set(e);
                }
            });

            boolean finished = process.waitFor(ADB_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                outputDrain.join(500);
                throw new AppException("ADB command timed out: " + String.join(" ", command));
            }

            outputDrain.join(1000);
            if (streamError.get() != null) {
                throw streamError.get();
            }

            String output = outputBuffer.toString(StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            if (exitCode != 0 && log.isDebugEnabled()) {
                log.debug("ADB command returned non-zero exit code {}: {}", exitCode, String.join(" ", command));
            }
            return output;
        } catch (IOException e) {
            throw new AppException("Failed to run adb command: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("Failed to run adb command: " + String.join(" ", command), e);
        }
    }

    /**
     * Runs a pre-configured ProcessBuilder with a strict timeout.
     * Drains stdout to prevent buffer-full blocking.
     * Always calls destroyForcibly() on exit.
     *
     * @param pb      ProcessBuilder already configured
     * @param timeout maximum wait duration
     * @param label   short description for logging (e.g. "pull /sdcard/foo.mp4")
     * @return exit code of the process
     * @throws AppException on timeout or IO error
     */
    private int runWithTimeout(ProcessBuilder pb, Duration timeout, String label) {
        pb.redirectErrorStream(true);
        try {
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
            if (streamError.get() != null) throw streamError.get();

            return process.exitValue();

        } catch (IOException e) {
            throw new AppException("ADB IO error: " + label, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("ADB interrupted: " + label, e);
        }
    }

    public boolean stopCameraService(String serial) {
        String adbPath = getAdbPath();
        if (adbPath == null) return false;

        ProcessBuilder pb = new ProcessBuilder(adbPath, "-s", serial, ADB_SHELL,
                "am", "stopservice",
                "-n", "com.bodycamera.nettysocket/com.recoda.bodycamera.service.CameraService");
        try {
            return runWithTimeout(pb, QUICK_TIMEOUT, "stopCameraService") == 0;
        } catch (AppException e) {
            log.warn("[{}] stopCameraService failed: {}", serial, e.getMessage());
            return false;
        }
    }

    public boolean startCameraService(String serial) {
        String adbPath = getAdbPath();
        if (adbPath == null) return false;

        ProcessBuilder pb = new ProcessBuilder(adbPath, "-s", serial, ADB_SHELL,
                "am", "startservice",
                "-n", "com.bodycamera.nettysocket/com.recoda.bodycamera.service.CameraService");
        try {
            return runWithTimeout(pb, QUICK_TIMEOUT, "startCameraService") == 0;
        } catch (AppException e) {
            log.warn("[{}] startCameraService failed: {}", serial, e.getMessage());
            return false;
        }
    }

    public boolean setUsbFunctionsNone(String serial) {
        String adbPath = getAdbPath();
        if (adbPath == null) return false;

        ProcessBuilder pb = new ProcessBuilder(adbPath, "-s", serial, ADB_SHELL,
                "svc", "usb", "setFunctions", "none");
        try {
            return runWithTimeout(pb, QUICK_TIMEOUT, "setUsbFunctionsNone") == 0;
        } catch (AppException e) {
            log.warn("[{}] setUsbFunctionsNone failed: {}", serial, e.getMessage());
            return false;
        }
    }
}
