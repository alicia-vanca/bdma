package com.app.common.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    public static DeviceValidationResult invalid(String serial, String message) {
        return new DeviceValidationResult(false, serial, message, null, null, null, null, false);
    }

    public static DeviceValidationResult valid(String serial,
            String hardwareId,
            String whitelistId,
            String modelName,
            String accountUserId,
            boolean alreadySaved) {
        return new DeviceValidationResult(true, serial, "Device validated", hardwareId,
                whitelistId, modelName, accountUserId, alreadySaved);
    }
}
