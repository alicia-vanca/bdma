package com.app.common.adb;

import com.app.common.exception.AppException;
import com.app.device.service.AdbRuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
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

@Service
public class AdbService {

    private static final Logger log = LoggerFactory.getLogger(AdbService.class);
    private static final Duration ADB_TIMEOUT = Duration.ofSeconds(20);
    private static final String SHELL = "shell";
    private static final Pattern GETPROP_PATTERN = Pattern.compile("^\\[(.+)]\\s*:\\s*\\[(.*)]$");

    private final AdbRuntimeService adbRuntimeService;

    public AdbService(AdbRuntimeService adbRuntimeService) {
        this.adbRuntimeService = adbRuntimeService;
    }

    public String getAdbPath() {
        try {
            return adbRuntimeService.resolveAdbExecutable();
        } catch (Exception e) {
            log.error("ADB path is null - cannot retrieve devices");
            return null;
        }
    }

    public List<String> getConnectedSerials() {
        try {
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
        } catch (Exception e) {
            log.error("Failed to get connected devices via adb", e);
            return List.of();
        }
    }

    public Map<String, String> getProps(String serial) {
        try {
            String output = runAdb("-s", serial, SHELL, "getprop");
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
            String output = runAdb("-s", serial, SHELL, "ls", "-1", path);
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
            return runAdb("-s", serial, SHELL, "cat", path);
        } catch (Exception e) {
            log.error("Failed to read remote file {} on {}", path, serial, e);
            return "";
        }
    }

    public Process startTrackDevicesProcess() throws IOException {
        String adbPath = getAdbPath();
        if (adbPath == null) {
            throw new IOException("ADB path is null");
        }
        return new ProcessBuilder(adbPath, "track-devices")
                .redirectErrorStream(true)
                .start();
    }

    public List<String> findFiles(String serial, String root, List<String> types) {
        String adbPath = getAdbPath();
        if (adbPath == null) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        try {
            List<String> command = new ArrayList<>(List.of(adbPath, "-s", serial, SHELL, "find"));
            for (String type : types) {
                command.add(root + "/" + type);
            }
            command.add("-type");
            command.add("f");

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

            process.waitFor();
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
            Process process = new ProcessBuilder(adbPath, "-s", serial, SHELL, "ls /storage")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.matches("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")) {
                        return "/storage/" + line;
                    }
                }
            }

            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
                    SHELL, "stat -c %s '" + remote + "'")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && line.matches("\\d+")) {
                    return Long.parseLong(line);
                }
            }

            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Failed to read remote size for {} on {}", remote, serial, e);
        }

        return -1;
    }

    public boolean pullFile(String serial, String remote, String local) {
        String adbPath = getAdbPath();
        if (adbPath == null) {
            return false;
        }

        try {
            Process process = new ProcessBuilder(adbPath, "-s", serial, "pull", remote, local)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("pull error: {}", remote, e);
        }

        return false;
    }

    public boolean deleteRemoteFile(String serial, String remote) {
        String adbPath = getAdbPath();
        if (adbPath == null) {
            return false;
        }

        try {
            Process process = new ProcessBuilder(adbPath, "-s", serial, SHELL, "rm", "-f", remote)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Failed to delete remote file {} on {}", remote, serial, e);
        }

        return false;
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

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return "device".equals(line);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Failed to check device state for {}", serial, e);
        }

        return false;
    }

    private String runAdb(String... args) {
        String adbExecutable = adbRuntimeService.resolveAdbExecutable();

        List<String> command = new ArrayList<>();
        command.add(adbExecutable);
        Collections.addAll(command, args);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
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
}
