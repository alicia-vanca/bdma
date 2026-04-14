package com.app.common.configs;

import com.app.common.exceptions.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class DeviceIdManager {
    private static final Logger log = LoggerFactory.getLogger(DeviceIdManager.class);
    private volatile String cachedDeviceId;

    /**
     * Returns a stable, machine-specific device ID for Windows platforms.
     * Throws AppException on unsupported OS.
     * Caches the result for future calls.
     */
    public String getDeviceId() {
        String local = cachedDeviceId;
        if (local != null && !local.isBlank()) {
            return local;
        }
        synchronized (this) {
            local = cachedDeviceId;
            if (local == null || local.isBlank()) {
                local = resolveHardwareId();
                cachedDeviceId = local;
            }
        }
        return local;
    }

    /**
     * Attempts to resolve a hardware-based identifier on Windows.
     * Throws AppException if not on Windows or no suitable ID is found.
     */
    private String resolveHardwareId() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            String machineGuid = readWindowsMachineGuid();
            if (machineGuid != null) {
                return sha256Hex("machine-guid|" + machineGuid);
            }
            String systemUuid = readWindowsSystemUuid();
            if (systemUuid != null) {
                return sha256Hex("system-uuid|" + systemUuid);
            }
        }
        throw new AppException("Cannot resolve a stable machine ID: only Windows is supported");
    }

    /**
     * Attempts to read the Windows MachineGuid from registry using PowerShell or
     * reg.exe.
     * Returns null if not found.
     */
    private String readWindowsMachineGuid() {
        String machineGuid = normalizeIdentifier(readCommandOutput(
                "powershell",
                "-NoProfile",
                "-Command",
                "Get-ItemPropertyValue -Path 'HKLM:\\SOFTWARE\\Microsoft\\Cryptography' -Name 'MachineGuid'"));
        if (machineGuid != null) {
            return machineGuid;
        }
        return extractWindowsRegValue(readCommandOutput(
                "reg",
                "query",
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                "/v",
                "MachineGuid"));
    }

    /**
     * Attempts to read the Windows System UUID using PowerShell.
     * Returns null if not found.
     */
    private String readWindowsSystemUuid() {
        return normalizeIdentifier(readCommandOutput(
                "powershell",
                "-NoProfile",
                "-Command",
                "(Get-CimInstance Win32_ComputerSystemProduct).UUID"));
    }

    /**
     * Normalizes a raw registry or command output value to a usable identifier.
     * Ignores blank or placeholder values.
     */
    private String normalizeIdentifier(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        for (String line : rawValue.split("\\R")) {
            String candidate = line.trim().toLowerCase(Locale.ROOT);
            if (isUsableIdentifier(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Extracts the MachineGuid value from reg.exe output.
     * Returns null if not found.
     */
    private String extractWindowsRegValue(String regQueryOutput) {
        if (regQueryOutput == null || regQueryOutput.isBlank()) {
            return null;
        }
        for (String line : regQueryOutput.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                String lowered = trimmed.toLowerCase(Locale.ROOT);
                if (lowered.contains("machineguid") && lowered.contains("reg_sz")) {
                    String value = trimmed.replaceFirst("(?i)^.*\\bREG_SZ\\b\\s*", "");
                    String normalized = normalizeIdentifier(value);
                    if (normalized != null) {
                        return normalized;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns true if the value is a usable hardware identifier (not blank or a
     * known placeholder).
     */
    private boolean isUsableIdentifier(String value) {
        return !value.isBlank()
                && !value.equals("none")
                && !value.equals("unknown")
                && !value.equals("default string")
                && !value.equals("system serial number")
                && !value.equals("to be filled by o.e.m.")
                && !value.equals("00000000-0000-0000-0000-000000000000")
                && !value.equals("ffffffff-ffff-ffff-ffff-ffffffffffff");
    }

    /**
     * Runs a command and returns its trimmed output, or null on error/timeout.
     */
    private String readCommandOutput(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try (var inputStream = process.getInputStream()) {
                    return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    return null;
                }
            });
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return null;
            }
            String output = outputFuture.get(1, TimeUnit.SECONDS);
            return process.exitValue() == 0 ? output : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Hardware ID probe command interrupted", e);
            return null;
        } catch (TimeoutException | ExecutionException e) {
            log.debug("Cannot read hardware ID probe command output", e);
            return null;
        } catch (Exception e) {
            log.debug("Cannot execute hardware ID probe command", e);
            return null;
        }
    }

    /**
     * Returns the SHA-256 hex string of the input value.
     */
    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AppException("Cannot hash hardware ID", e);
        }
    }
}