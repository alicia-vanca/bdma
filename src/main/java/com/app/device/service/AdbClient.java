package com.app.device.service;

import com.app.common.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdbClient {

    private static final Logger log = LoggerFactory.getLogger(AdbClient.class);
    private static final Duration ADB_TIMEOUT = Duration.ofSeconds(20);
    private static final String ADB_SHELL = "shell";
    private static final Pattern GETPROP_PATTERN = Pattern.compile("^\\[(.+)]\\s*:\\s*\\[(.*)]$");

    private final AdbRuntimeService adbRuntimeService;

    public AdbClient(AdbRuntimeService adbRuntimeService) {
        this.adbRuntimeService = adbRuntimeService;
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

    public Map<String, String> getProps(String serial) {
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
    }

    public boolean fileExists(String serial, String path) {
        String output = runAdb("-s", serial, ADB_SHELL, "ls", "-1", path);
        String trimmed = output.trim();
        if (trimmed.isBlank()) {
            return false;
        }

        return !(trimmed.contains("no such file")
                || trimmed.contains("cannot access")
                || trimmed.contains("not found"));
    }

    public String readTextFile(String serial, String path) {
        return runAdb("-s", serial, ADB_SHELL, "cat", path);
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
}
