package com.app.common.modules.device.services;

import com.app.common.services.WindowsCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class DeviceDriveLetterResolver {

    private static final Logger log = LoggerFactory.getLogger(DeviceDriveLetterResolver.class);
    private static final String SAFE_SERIAL_PATTERN = "^[A-Za-z0-9_\\-:.]+$";

    private final WindowsCommandService windowsCommandService;

    public DeviceDriveLetterResolver(WindowsCommandService windowsCommandService) {
        this.windowsCommandService = windowsCommandService;
    }

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

    private String getDiskNumber(String serial) throws IOException, InterruptedException {
        String result = windowsCommandService.resolveDiskNumber(serial);
        if (result.isBlank()) return null;

        if (!result.matches("\\d+")) {
            log.warn("Unexpected disk number output: '{}'", result);
            return null;
        }

        return result;
    }

    private String getDriveLetter(String diskNumber) throws IOException, InterruptedException {
        String result = windowsCommandService.resolveDriveLetter(diskNumber);
        if (result.isBlank()) return null;

        if (!result.matches("[A-Za-z]")) {
            log.warn("Unexpected drive letter output: '{}'", result);
            return null;
        }

        return result.toUpperCase() + ":\\";
    }
}
