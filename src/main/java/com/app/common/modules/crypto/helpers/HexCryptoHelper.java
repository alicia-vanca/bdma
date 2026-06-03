package com.app.common.modules.crypto.helpers;

import com.app.common.modules.crypto.exceptions.CryptoException;

/**
 * Converts cryptographic byte material to and from hexadecimal text.
 */
public final class HexCryptoHelper {

    private HexCryptoHelper() {
    }

    /**
     * Decodes hexadecimal text into bytes.
     *
     * @param hex hexadecimal text with even length
     * @return decoded bytes
     * @throws CryptoException when text is blank, odd length, or contains non-hex
     *                         characters
     */
    public static byte[] decode(String hex) {
        if (hex == null || hex.isBlank()) {
            throw new CryptoException("Hex value is not configured.");
        }

        String normalized = hex.trim();
        if (normalized.length() % 2 != 0) {
            throw new CryptoException("Hex value must have an even number of characters.");
        }

        byte[] decoded = new byte[normalized.length() / 2];
        for (int i = 0; i < decoded.length; i++) {
            int hi = Character.digit(normalized.charAt(i * 2), 16);
            int lo = Character.digit(normalized.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new CryptoException("Hex value contains non-hex characters.");
            }
            decoded[i] = (byte) ((hi << 4) | lo);
        }
        return decoded;
    }
}
