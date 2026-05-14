package com.app.common.dtos;

import com.app.common.services.DeviceMiniStatus;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeviceSummary {

    public enum Status {
        CONNECTED,
        OFFLINE,
        UNVALIDATED
    }

    private String hardwareId;
    private String deviceName;
    private String cameraId;
    private Status status;
    private DeviceMiniStatus.SyncProgress syncProgress;
    private DeviceValidationResult validationResult;

    public boolean isConnected() {
        return status == Status.CONNECTED;
    }

    public boolean isUnvalidated() {
        return status == Status.UNVALIDATED;
    }
}
