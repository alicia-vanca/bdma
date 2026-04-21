package com.app.common.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceValidationResult {
    private boolean valid;
    private String hardwareId;
    private String message;
    private String matchedWhitelistId;
    private String matchedModelName;
    private String cameraId;
    private boolean alreadySaved;

    public static DeviceValidationResult invalid(String hardwareId, String message) {
        return new DeviceValidationResult(false, hardwareId, message, null, null, null, false);
    }

    public static DeviceValidationResult valid(String hardwareId,
            String whitelistId,
            String modelName,
            String cameraId,
            boolean alreadySaved) {
        return new DeviceValidationResult(true, hardwareId, "Device validated",
                whitelistId, modelName, cameraId, alreadySaved);
    }
}
