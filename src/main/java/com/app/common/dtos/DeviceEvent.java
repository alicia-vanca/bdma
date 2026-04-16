package com.app.common.dtos;

/**
 * Represents a device connection state change detected by DeviceTracker.
 * Listeners receive the affected serial, the event type, and any validation
 * details resolved at the tracker boundary.
 */
public record DeviceEvent(
        String serial,
        EventType type,
        DeviceValidationResult validationResult) {

    public enum EventType {
        CONNECTED,
        DISCONNECTED
    }
}
