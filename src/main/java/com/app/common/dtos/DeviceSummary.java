package com.app.common.dtos;

import com.app.common.services.SyncProgressTracker;

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
    private Status status;
    private SyncProgressTracker.SyncProgress syncProgress;
    private DeviceValidationResult validationResult;

    public boolean isConnected() {
        return status == Status.CONNECTED;
    }

    public boolean isUnvalidated() {
        return status == Status.UNVALIDATED;
    }
}
