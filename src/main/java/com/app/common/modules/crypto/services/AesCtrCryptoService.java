package com.app.common.modules.crypto.services;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.app.common.modules.crypto.constants.CryptoConstants;
import com.app.common.modules.crypto.exceptions.CryptoException;

/**
 * Performs AES-CTR encryption and decryption with caller-provided key and IV.
 */
@Service
public class AesCtrCryptoService {

    /**
     * Streams ciphertext from a file and writes AES-CTR plaintext to another file.
     *
     * @param inputFile  ciphertext source file
     * @param outputFile plaintext output file
     * @param key        AES key bytes
     * @param iv         CTR initial counter block bytes
     */
    public void decrypt(Path inputFile, Path outputFile, byte[] key, byte[] iv) {
        validateFileRequest(inputFile, outputFile, key, iv);
        try {
            Cipher cipher = createDecryptCipher(key, iv);
            try (InputStream input = new CipherInputStream(Files.newInputStream(inputFile), cipher);
                    OutputStream output = Files.newOutputStream(outputFile)) {
                input.transferTo(output);
            }
        } catch (IOException | InvalidAlgorithmParameterException | InvalidKeyException | NoSuchAlgorithmException
                | NoSuchPaddingException e) {
            throw new CryptoException("AES-CTR file decryption failed: " + inputFile, e);
        }
    }

    @SuppressWarnings("java:S3329")
    private Cipher createDecryptCipher(byte[] key, byte[] iv) throws NoSuchAlgorithmException, NoSuchPaddingException,
            InvalidKeyException, InvalidAlgorithmParameterException {
        Cipher cipher = Cipher.getInstance(CryptoConstants.AES_CTR_TRANSFORMATION);
        // IV policy belongs to the caller because this service supports legacy formats
        // whose IV is dictated by protocol or device compatibility.
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, CryptoConstants.AES_ALGORITHM),
                new IvParameterSpec(iv));
        return cipher;
    }

    private void validateFileRequest(Path inputFile, Path outputFile, byte[] key, byte[] iv) {
        if (inputFile == null || outputFile == null || key == null || iv == null) {
            throw new CryptoException("AES-CTR input file, output file, key, and IV must not be null.");
        }
    }
}
