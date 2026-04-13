package com.app.sync.service;

import com.app.device.service.AdbClient;
import com.app.device.service.AdbRuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for interacting with ADB to retrieve connected Android devices.
 */
@Service
public class AdbService {

    private static final Logger log = LoggerFactory.getLogger(AdbService.class);
    private final AdbClient adbClient;
    private final AdbRuntimeService adbRuntimeService;

    public AdbService(AdbClient adbClient, AdbRuntimeService adbRuntimeService) {
        this.adbClient = adbClient;
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

    /**
     * Returns a list of serial numbers for devices in "device" state.
     *
     * @return list of available device serials
     */
    public List<String> getConnectedSerials() {
        try {
            return adbClient.listConnectedSerials();
        } catch (Exception e) {
            log.error("Failed to get connected devices via adb", e);
            return List.of();
        }
    }
}


