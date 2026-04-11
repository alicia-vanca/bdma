package com.app.sync.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for interacting with ADB to retrieve connected Android devices.
 */
@Service
public class AdbService {

    private static final Logger log = LoggerFactory.getLogger(AdbService.class);

    /**
     * Returns a list of serial numbers for devices in "device" state.
     *
     * @return list of available device serials
     */
    public List<String> getConnectedSerials() {

        List<String> serials = new ArrayList<>();

        try {
            Process p = new ProcessBuilder("adb", "devices")
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
