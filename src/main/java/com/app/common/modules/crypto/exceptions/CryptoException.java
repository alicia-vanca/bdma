package com.app.common.modules.crypto.exceptions;

/**
 * Represents failures in reusable cryptographic operations.
 */
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
