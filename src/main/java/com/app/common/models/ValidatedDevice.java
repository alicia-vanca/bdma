package com.app.common.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidatedDevice {
    private Long id;
    private String deviceName;
    private String hardwareId;
    private String whitelistId;
    private String validatedAt;
    private String lastSeenAt;
    private String cameraId;
}
