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
        try {
            String diskNumber = getDiskNumber(adbSerial.trim());
            if (diskNumber == null) {
                log.debug("No disk found for serial: {}", adbSerial);
                return null;
            }

            String driveLetter = getDriveLetter(diskNumber);
            if (driveLetter == null) {
                log.debug("No drive letter found for disk number: {}", diskNumber);
                return null;
            }

            log.info("Resolved drive letter for {}: {}", adbSerial, driveLetter);
            return driveLetter;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("DriveLetterMapper interrupted for {}", adbSerial);
            return null;

        } catch (IOException e) {
            log.debug("DriveLetterMapper failed for {}: {}", adbSerial, e.getMessage());
            return null;
        }
    }

    /**
     * Finds the Windows disk number whose SerialNumber matches the ADB serial.
     */
    private String getDiskNumber(String serial) throws IOException, InterruptedException {
        String script = String.format(
                "Get-Disk | Where-Object { $_.SerialNumber.Trim() -eq '%s' } " +
                        "| Select-Object -ExpandProperty Number",
                serial
        );

        String result = runPowerShell(script).trim();
        return result.isBlank() ? null : result;
    }

    /**
     * Finds the drive letter assigned to the first partition of the given disk.
     */
    private String getDriveLetter(String diskNumber) throws IOException, InterruptedException {
        String script = String.format(
                "Get-Partition -DiskNumber %s " +
                        "| Get-Volume " +
                        "| Where-Object { $_.DriveLetter } " +
                        "| Select-Object -ExpandProperty DriveLetter -First 1",
                diskNumber
        );

        String result = runPowerShell(script).trim();
        return result.isBlank() ? null : result + ":\\";
    }

    private String runPowerShell(String script) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = process.waitFor(POWERSHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("PowerShell timed out resolving drive letter");
            return "";
        }
        return output;
    }
}
