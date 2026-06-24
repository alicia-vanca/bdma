package com.app.common.exceptions;

import java.io.IOException;

/**
 * Signals that a storage operation failed because target drive has no usable
 * space.
 */
public class DiskFullException extends IOException {
    public DiskFullException(String message) {
        super(message);
    }
}
