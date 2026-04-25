package com.app.common.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class DriveLetterMapper {

    private static final Logger log = LoggerFactory.getLogger(DriveLetterMapper.class);
    private static final int POWERSHELL_TIMEOUT_SECONDS = 10;
    private static final String SAFE_SERIAL_PATTERN = "^[A-Za-z0-9_\\-:.]+$";

    /**
     * Resolves the Windows drive letter for a given ADB serial number.
     *
     * @param adbSerial the ADB device serial (e.g. {@code BODYCAMERAVS8DQK})
     * @return drive letter with trailing backslash (e.g. {@code E:\}),
     *         or {@code null} if not found or an error occurs
     */
    public String resolve(String adbSerial) {
        if (adbSerial == null || adbSerial.isBlank()) {
            return null;
        }

        String serial = adbSerial.trim();

        if (!serial.matches(SAFE_SERIAL_PATTERN)) {
            log.warn("Rejected unsafe ADB serial: {}", serial);
            return null;
        }

        try {
            String diskNumber = getDiskNumber(serial);
            if (diskNumber == null) {
                log.debug("No disk found for serial: {}", serial);
                return null;
            }

            String driveLetter = getDriveLetter(diskNumber);
            if (driveLetter == null) {
                log.debug("No drive letter found for disk number: {}", diskNumber);
                return null;
            }

            log.info("Resolved drive letter for {}: {}", serial, driveLetter);
            return driveLetter;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("DriveLetterMapper interrupted for serial: {}", serial);
            return null;
        } catch (IOException e) {
            log.warn("DriveLetterMapper I/O error for serial {}: {}", serial, e.getMessage());
            return null;
        }
    }

    /**
     * Finds the Windows disk number whose SerialNumber matches the ADB serial.
     */
    private String getDiskNumber(String serial) throws IOException, InterruptedException {
        String script = String.format(
                "Get-Disk | Where-Object { $_.SerialNumber -ne $null -and $_.SerialNumber.Trim() -eq '%s' } " +
                        "| Select-Object -ExpandProperty Number",
                serial
        );

        String result = runPowerShell(script).trim();

        if (result.isBlank()) {
            return null;
        }

        if (!result.matches("\\d+")) {
            log.warn("Unexpected disk number output: '{}'", result);
            return null;
        }

        return result;
    }

    /**
     * Finds the drive letter assigned to the first partition of the given disk.
     */
    private String getDriveLetter(String diskNumber) throws IOException, InterruptedException {
        String script = String.format(
                "Get-Partition -DiskNumber %s -ErrorAction SilentlyContinue " +
                        "| Get-Volume -ErrorAction SilentlyContinue " +
                        "| Where-Object { $_.DriveLetter -ne $null } " +
                        "| Select-Object -ExpandProperty DriveLetter -First 1",
                diskNumber
        );

        String result = runPowerShell(script).trim();

        if (result.isBlank()) {
            return null;
        }

        if (!result.matches("[A-Za-z]")) {
            log.warn("Unexpected drive letter output: '{}'", result);
            return null;
        }

        return result.toUpperCase() + ":\\";
    }

    private String runPowerShell(String script) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try {
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(POWERSHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                log.warn("PowerShell timed out after {}s", POWERSHELL_TIMEOUT_SECONDS);
                return "";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("PowerShell exited with code {}: {}", exitCode, output);
                return "";
            }

            return output;

        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
