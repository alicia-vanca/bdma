package com.app.common.modules.crypto.helpers;

import java.security.SecureRandom;
import java.util.Arrays;

import com.app.common.modules.crypto.exceptions.CryptoException;

/**
 * Handles validation, generation, and cleanup of keying material.
 */
public final class KeyMaterialHelper {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private KeyMaterialHelper() {
    }

    /**
     * Decodes a fixed-length key from hexadecimal text.
     *
     * @param hex           hexadecimal key text
     * @param expectedBytes expected decoded byte length
     * @param keyName       human-readable key name used in error messages
     * @return decoded key bytes
     */
    public static byte[] decodeHexKey(String hex, int expectedBytes, String keyName) {
        byte[] key = HexCryptoHelper.decode(hex);
        if (key.length != expectedBytes) {
            clear(key);
            throw new CryptoException(
                    keyName + " must be a " + expectedBytes * 2 + "-character hex string (" + expectedBytes
                            + " bytes). Got length: " + hex.trim().length());
        }
        return key;
    }

    /**
     * Creates cryptographically random bytes for IVs, nonces, or salts.
     *
     * @param length number of bytes to generate
     * @return random bytes
     */
    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * Overwrites a byte buffer with zeros to reduce secret lifetime in heap memory.
     *
     * @param data byte buffer to clear; may be {@code null}
     */
    public static void clear(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }
}
