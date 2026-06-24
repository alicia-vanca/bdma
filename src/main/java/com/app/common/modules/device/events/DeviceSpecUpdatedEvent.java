package com.app.common.modules.device.events;

import com.app.common.modules.device.dtos.DeviceSpec;

public record DeviceSpecUpdatedEvent(
                String cameraId,
                DeviceSpec deviceSpec) {
}