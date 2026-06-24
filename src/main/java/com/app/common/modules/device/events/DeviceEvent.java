package com.app.common.modules.device.events;

import com.app.common.definitions.enums.DeviceEventType;
import com.app.common.modules.device.dtos.DeviceValidationResult;

/**
 * Represents a device connection state change detected by DeviceTracker.
 * Listeners receive the affected hardwareId, the event type, and any validation
 * details resolved at the tracker boundary.
 */
public record DeviceEvent(
                String hardwareId,
                DeviceEventType type,
                DeviceValidationResult validationResult) {
}
