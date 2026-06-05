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
    private Long matchedWhitelistId;
    private String matchedModelName;
    private String cameraId;
    private boolean alreadySaved;
    private String deviceName;

    public static DeviceValidationResult invalid(String hardwareId, String message) {
        return new DeviceValidationResult(false, hardwareId, message, null, null, null, false, null);
    }

    public static DeviceValidationResult valid(String hardwareId,
            Long whitelistId,
            String modelName,
            String cameraId,
            boolean alreadySaved,
            String deviceName) {
        return new DeviceValidationResult(true, hardwareId, "Device validated",
                whitelistId, modelName, cameraId, alreadySaved, deviceName);
    }
}
