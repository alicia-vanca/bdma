package com.app.device.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceValidationResult {
    private boolean valid;
    private String serial;
    private String message;
    private String hardwareId;
    private String matchedWhitelistId;
    private String matchedModelName;
    private String accountUserId;
    private boolean alreadySaved;
    private List<String> missingFiles;
    private ValidatedDevice device;

    public static DeviceValidationResult invalid(String serial, String message) {
        return new DeviceValidationResult(false, serial, message, null, null, null, null, false,
                Collections.emptyList(), null);
    }

    public static DeviceValidationResult invalidWithMissingFiles(String serial,
            String message,
            List<String> missingFiles) {
        return new DeviceValidationResult(false, serial, message, null, null, null, null,
                false, missingFiles, null);
    }

    public static DeviceValidationResult valid(String serial,
            String hardwareId,
            String whitelistId,
            String modelName,
            String accountUserId,
            boolean alreadySaved,
            ValidatedDevice device) {
        return new DeviceValidationResult(true, serial, "Device validated", hardwareId,
                whitelistId, modelName, accountUserId, alreadySaved, Collections.emptyList(), device);
    }
}
