package com.app.common.modules.crypto.helpers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.app.common.modules.crypto.exceptions.CryptoException;

/**
 * Provides deterministic key derivation helpers required by compatibility
 * flows.
 */
public final class KeyDerivationHelper {

    private static final String SHA_256 = "SHA-256";

    private KeyDerivationHelper() {
    }

    /**
     * Derives a 32-byte key by hashing a password with SHA-256.
     *
     * @param password source password
     * @return 32-byte SHA-256 digest
     */
    public static byte[] sha256Password(String password) {
        if (password == null) {
            throw new CryptoException("Password must not be null.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return digest.digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new CryptoException("SHA-256 key derivation is not available.", e);
        }
    }
}
