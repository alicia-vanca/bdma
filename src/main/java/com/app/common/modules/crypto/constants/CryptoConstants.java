package com.app.common.modules.crypto.constants;

/**
 * Shared constants for reusable cryptographic services.
 */
public final class CryptoConstants {

    public static final String AES_ALGORITHM = "AES";
    public static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    public static final String AES_CTR_TRANSFORMATION = "AES/CTR/NoPadding";

    public static final int AES_256_KEY_BYTES = 32;
    public static final int AES_BLOCK_BYTES = 16;
    public static final int GCM_RECOMMENDED_IV_BYTES = 12;
    public static final int GCM_TAG_BITS = 128;

    private CryptoConstants() {
    }

    /**
     * Returns a new fixed zero IV required by legacy Bodycam media.
     *
     * @return zero-filled IV bytes for Bodycam AES-CTR compatibility
     */
    public static byte[] fixedZeroIv() {
        return new byte[AES_BLOCK_BYTES];
    }
}
