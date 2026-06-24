package com.app.admin.devicemanagement.dtos;

import com.app.common.definitions.enums.DeviceStatus;
import com.app.common.modules.device.dtos.DeviceSummary;
import com.app.common.modules.device.dtos.DeviceValidationResult;
import com.app.common.models.ValidatedDevice;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeviceManagementRow {

    private Long id;
    private String deviceName;
    private String cameraId;
    private String hardwareId;
    private DeviceStatus status;
    private String lastSeenAt;
    private String lastSyncAt;
    private boolean active;
    private boolean transientDevice;
    private DeviceValidationResult validationResult;

    public static DeviceManagementRow fromSavedDevice(ValidatedDevice device, DeviceSummary summary) {
        DeviceStatus rowStatus = resolveSavedStatus(summary);
        String hardwareId = summary != null ? summary.getHardwareId() : device.getHardwareId();
        return new DeviceManagementRow(
                device.getId(),
                device.getDeviceName(),
                device.getCameraId(),
                hardwareId,
                rowStatus,
                device.getLastSeenAt(),
                device.getLastSyncAt(),
                device.isActive(),
                false,
                null);
    }

    public static DeviceManagementRow fromTransient(DeviceSummary summary) {
        return new DeviceManagementRow(
                null,
                summary.getDeviceName(),
                summary.getCameraId(),
                summary.getHardwareId(),
                DeviceStatus.UNVALIDATED,
                summary.getLastSeenAt(),
                summary.getLastSyncAt(),
                true,
                true,
                summary.getValidationResult());
    }

    private static DeviceStatus resolveSavedStatus(DeviceSummary summary) {
        if (summary == null) {
            return DeviceStatus.OFFLINE;
        }
        if (summary.getStatus() == DeviceStatus.CONNECTED) {
            return DeviceStatus.CONNECTED;
        }
        // Saved rows should not expose transient validation state; unvalidated rows are
        // represented separately by fromTransient().
        return DeviceStatus.OFFLINE;
    }
}
