package com.app.common.config;

import com.app.common.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

public class DeviceIdManager {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdManager.class);

    private final String filePath;

    public DeviceIdManager(String appDir) {
        this.filePath = appDir + "/config/device.id";
    }

    public String getDeviceId() {
        File file = new File(filePath);

        try {
            if (file.exists()) {
                return Files.readString(file.toPath()).trim();
            }

            File parentDir = file.getParentFile();
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                throw new AppException("Failed to create config directory: " + parentDir.getAbsolutePath());
            }

            String id = UUID.randomUUID().toString();
            Files.writeString(file.toPath(), id);
            log.info("Device ID generated: {}", id);

            return id;

        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            throw new AppException("Cannot get deviceId: " + e.getMessage());
        }
    }
}