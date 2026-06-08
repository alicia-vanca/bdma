package com.app.common.modules.crypto.services;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.app.common.modules.crypto.constants.CryptoConstants;
import com.app.common.modules.crypto.exceptions.CryptoException;
import com.app.common.modules.crypto.helpers.KeyDerivationHelper;
import com.app.common.modules.crypto.helpers.KeyMaterialHelper;

/**
 * Decrypts media produced by the current Bodycam synchronization flow.
 *
 * <p>
 * This service exists for compatibility with existing Bodycam devices and
 * previously synchronized encrypted media files. The flow uses AES-256-CTR,
 * a SHA-256 derived password key, and a fixed zero IV. Do not change this flow
 * without reviewing impact on devices, playback, export, and backward
 * compatibility.
 */
@Service
public class BodycamCryptoService {

    private final AesCtrCryptoService aesCtrCryptoService;
    private final String password;

    public BodycamCryptoService(AesCtrCryptoService aesCtrCryptoService,
            @Value("${bodycam.crypto.password:${BODYCAM_CRYPTO_PASSWORD:}}") String password) {
        this.aesCtrCryptoService = aesCtrCryptoService;
        this.password = password;
    }

    /**
     * Streams Bodycam encrypted media into a decrypted output file.
     *
     * @param encryptedFile encrypted source file
     * @param outputFile    decrypted output file
     * @param password      Bodycam password used to derive the AES-256 key
     */
    public void decryptMediaFile(Path encryptedFile, Path outputFile, String password) {
        byte[] key = KeyDerivationHelper.sha256Password(password);
        byte[] iv = CryptoConstants.fixedZeroIv();
        try {
            aesCtrCryptoService.decrypt(encryptedFile, outputFile, key, iv);
        } finally {
            KeyMaterialHelper.clear(key);
            KeyMaterialHelper.clear(iv);
        }
    }

    /**
     * Streams Bodycam encrypted media into a decrypted output file with the
     * configured sync password.
     *
     * @param encryptedFile encrypted source file
     * @param outputFile    decrypted output file
     */
    public void decryptMediaFile(Path encryptedFile, Path outputFile) {
        decryptMediaFile(encryptedFile, outputFile, configuredPassword());
    }

    private String configuredPassword() {
        if (password == null || password.isBlank()) {
            throw new CryptoException("Bodycam crypto password is not configured.");
        }
        return password;
    }

}
