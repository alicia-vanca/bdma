package com.app.common.modules.device.dtos;

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
    private String matchedWhitelistModel;
    private String cameraId;
    private boolean alreadySaved;
    private String deviceName;
    private boolean active;
    private DeviceSpec deviceSpec;

    public static DeviceValidationResult invalid(String hardwareId, String message) {
        return new DeviceValidationResult(false, hardwareId, message, null, null, null, false, null, false, null);
    }

    public static DeviceValidationResult valid(String hardwareId,
            Long whitelistId,
            String whitelistModel,
            String cameraId,
            boolean alreadySaved,
            String deviceName,
            boolean active,
            DeviceSpec specInfo) {
        return new DeviceValidationResult(true, hardwareId, "Device validated",
                whitelistId, whitelistModel, cameraId, alreadySaved, deviceName, active, specInfo);
    }
}
