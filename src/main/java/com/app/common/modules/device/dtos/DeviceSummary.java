package com.app.common.modules.device.dtos;

import com.app.common.definitions.enums.DeviceStatus;
import com.app.common.modules.device.services.DeviceMiniStatus;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeviceSummary {

    private String hardwareId;
    private String deviceName;
    private String cameraId;
    private DeviceStatus status;
    private String lastSeenAt;
    private String lastSyncAt;
    private DeviceMiniStatus.SyncProgress syncProgress;
    private DeviceValidationResult validationResult;
    private Long whitelistId;
    private boolean active;
    private DeviceSpec deviceSpec;

    /**
     * Creates a detached copy so UI snapshots do not share mutable row instances
     * with the backend device state.
     *
     * @param source summary to copy
     */
    public DeviceSummary(DeviceSummary source) {
        this(
                source.getHardwareId(),
                source.getDeviceName(),
                source.getCameraId(),
                source.getStatus(),
                source.getLastSeenAt(),
                source.getLastSyncAt(),
                source.getSyncProgress(),
                source.getValidationResult(),
                source.getWhitelistId(),
                source.isActive(),
                source.getDeviceSpec());
    }

    public boolean isConnected() {
        return DeviceStatus.CONNECTED.equals(status);
    }

    public boolean isUnvalidated() {
        return DeviceStatus.UNVALIDATED.equals(status);
    }
}
