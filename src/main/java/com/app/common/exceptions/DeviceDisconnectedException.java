package com.app.common.exceptions;

public class DeviceDisconnectedException extends AppException {
    public DeviceDisconnectedException(String message) {
        super(message);
    }

    public DeviceDisconnectedException(String message, Throwable cause) {
        super(message, cause);
    }
}