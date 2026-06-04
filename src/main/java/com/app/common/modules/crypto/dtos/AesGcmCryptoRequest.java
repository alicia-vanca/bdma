package com.app.common.modules.crypto.dtos;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Request data for AES-GCM encryption or decryption.
 *
 * @param input plaintext for encryption or ciphertext with tag for decryption
 * @param key   AES key bytes
 * @param iv    GCM nonce/IV bytes
 * @param aad   optional additional authenticated data; may be {@code null}
 */
public record AesGcmCryptoRequest(byte[] input, byte[] key, byte[] iv, byte[] aad) {

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AesGcmCryptoRequest(byte[] otherInput, byte[] otherKey, byte[] otherIv, byte[] otherAad))) {
            return false;
        }
        return Arrays.equals(input, otherInput)
                && Arrays.equals(key, otherKey)
                && Arrays.equals(iv, otherIv)
                && Arrays.equals(aad, otherAad);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(input);
        result = 31 * result + Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(iv);
        result = 31 * result + Arrays.hashCode(aad);
        return result;
    }

    @NotNull
    @Override
    public String toString() {
        return "AesGcmCryptoRequest[inputLength=" + length(input)
                + ", keyLength=" + length(key)
                + ", ivLength=" + length(iv)
                + ", aadLength=" + length(aad) + "]";
    }

    private int length(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }
}
