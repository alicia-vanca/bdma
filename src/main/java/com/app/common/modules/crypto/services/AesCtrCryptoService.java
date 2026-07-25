package com.app.common.modules.crypto.services;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.app.common.modules.externalmediadecrypt.callbacks.ProgressCallback;
import com.app.common.modules.i18n.I18n;
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
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("AES-CTR file decryption interrupted: " + inputFile);
                    }
                    output.write(buffer, 0, bytesRead);
                }
            }
        } catch (IOException | InvalidAlgorithmParameterException | InvalidKeyException | NoSuchAlgorithmException
                | NoSuchPaddingException e) {
            throw new CryptoException("AES-CTR file decryption failed: " + inputFile, e);
        }
    }
    public void decrypt(
            Path inputFile,
            Path outputFile,
            byte[] key,
            byte[] iv,
            ProgressCallback progressCallback,
            BooleanSupplier isCancelled) {

        validateFileRequest(inputFile, outputFile, key, iv);

        try {
            Cipher cipher = createDecryptCipher(key, iv);

            long totalBytes = Files.size(inputFile);
            long processedBytes = 0;

            try (InputStream input =
                         new CipherInputStream(Files.newInputStream(inputFile), cipher);
                 OutputStream output =
                         Files.newOutputStream(outputFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    if (isCancelled != null && isCancelled.getAsBoolean()) {
                        return;
                    }

                    output.write(buffer, 0, bytesRead);

                    processedBytes += bytesRead;

                    if (progressCallback != null) {
                        progressCallback.update(processedBytes, totalBytes);
                    }
                }

                output.flush();
            }

        } catch (InvalidKeyException e) {
            throw new CryptoException(I18n.get("externalMediaDecrypt.invalid.key"), e);

        } catch (InvalidAlgorithmParameterException e) {
            throw new CryptoException(I18n.get("externalMediaDecrypt.invalid.iv"), e);

        } catch (NoSuchAlgorithmException e) {
            throw new CryptoException(I18n.get("externalMediaDecrypt.unsupported.algorithm"), e);

        } catch (NoSuchPaddingException e) {
            throw new CryptoException(I18n.get("externalMediaDecrypt.unsupported.padding"), e);

        } catch (IOException e) {
            throw new CryptoException(I18n.get("externalMediaDecrypt.io.exception"), e);

        } catch (SecurityException e) {
            throw new CryptoException(I18n.get("externalMediaDecrypt.access.denied"), e);
        } catch (Exception e) {
            throw new CryptoException(I18n.get("externalMediaDecrypt.exception"), e);
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
