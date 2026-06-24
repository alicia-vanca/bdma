package com.app.common.exceptions;

import java.io.IOException;

/**
 * Signals that a storage operation failed because the target drive is
 * unavailable.
 */
public class DriveUnavailableException extends IOException {
    public DriveUnavailableException(String message) {
        super(message);
    }
}
