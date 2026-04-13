package com.app.sync.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for interacting with ADB to retrieve connected Android devices.
 */
@Service
public class AdbService {

    private static final Logger log = LoggerFactory.getLogger(AdbService.class);
    private String adbPath;

    public AdbService() {
        this.adbPath = findAdbInResources();
        if (adbPath == null) {
            log.error("Embedded ADB not found in resources: adb/adb.exe");
        } else {
            log.info("ADB found at: {}", adbPath);
        }
    }

    private String findAdbInResources() {
        try {
            URL resourceUrl = getClass().getClassLoader().getResource("adb/adb.exe");

            if (resourceUrl == null) {
                resourceUrl = getClass().getClassLoader().getResource("adb");
                if (resourceUrl != null) {
                    File adbFile = new File(URLDecoder.decode(resourceUrl.getPath(), StandardCharsets.UTF_8) + File.separator + "adb.exe");
                    if (adbFile.exists()) {
                        return adbFile.getAbsolutePath();
                    }
                }
                return null;
            }

            File adbFile = new File(URLDecoder.decode(resourceUrl.getPath(), StandardCharsets.UTF_8));
            if (adbFile.exists() && adbFile.canExecute()) {
                return adbFile.getAbsolutePath();
            }
        } catch (Exception e) {
            log.debug("Unable to resolve embedded adb", e);
        }
        return null;
    }

    public String getAdbPath() {
        return adbPath;
    }

    /**
     * Returns a list of serial numbers for devices in "device" state.
     *
     * @return list of available device serials
     */
    public List<String> getConnectedSerials() {

        List<String> serials = new ArrayList<>();

        if (adbPath == null) {
            log.error("ADB path is null - cannot retrieve devices");
            return serials;
        }

        try {
            Process p = new ProcessBuilder(adbPath, "devices")
                    .redirectErrorStream(true)
                    .start();

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));

            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();

                // Skip header and empty lines
                if (line.isEmpty() || line.startsWith("List of devices")) continue;

                // Only include devices in "device" state
                if (line.endsWith("\tdevice") || line.endsWith(" device")) {
                    String serial = line.split("\\s+")[0];
                    serials.add(serial);
                    log.debug("Found device: {}", serial);
                } else {
                    log.debug("Skipping device (not ready): {}", line);
                }
            }

            p.waitFor();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while getting adb devices", e);
        } catch (IOException e) {
            log.error("Failed to get connected devices via adb", e);
        }

        return serials;
    }
}
