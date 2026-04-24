package com.app.common.events;

import com.app.common.dtos.DeviceValidationResult;

/**
 * Represents a device connection state change detected by DeviceTracker.
 * Listeners receive the affected hardwareId, the event type, and any validation
 * details resolved at the tracker boundary.
 */
public record DeviceEvent(
        String hardwareId,
        EventType type,
        DeviceValidationResult validationResult) {

    public enum EventType {
        CONNECTED,
        DISCONNECTED
    }
}
