package com.app.common.modules.crypto.services;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.app.common.modules.crypto.constants.CryptoConstants;
import com.app.common.modules.crypto.dtos.AesGcmCryptoRequest;
import com.app.common.modules.crypto.exceptions.CryptoException;

/**
 * Performs AES-GCM encryption and decryption with caller-provided key, IV, and
 * AAD.
 */
@Service
public class AesGcmCryptoService {

    /**
     * Encrypts plaintext with AES-GCM.
     *
     * @param request encryption request containing plaintext, key, IV, and optional
     *                AAD
     * @return ciphertext with authentication tag appended by the JCA provider
     */
    public byte[] encrypt(AesGcmCryptoRequest request) {
        validateRequest(request);
        try {
            Cipher cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, toSecretKey(request.key()),
                    new GCMParameterSpec(CryptoConstants.GCM_TAG_BITS, request.iv()));
            updateAad(cipher, request.aad());
            return cipher.doFinal(request.input());
        } catch (Exception e) {
            throw new CryptoException("AES-GCM encryption failed.", e);
        }
    }

    /**
     * Decrypts ciphertext with AES-GCM.
     *
     * @param request decryption request containing ciphertext, key, IV, and
     *                optional AAD
     * @return plaintext bytes
     */
    public byte[] decrypt(AesGcmCryptoRequest request) {
        validateRequest(request);
        try {
            Cipher cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, toSecretKey(request.key()),
                    new GCMParameterSpec(CryptoConstants.GCM_TAG_BITS, request.iv()));
            updateAad(cipher, request.aad());
            return cipher.doFinal(request.input());
        } catch (AEADBadTagException e) {
            throw new CryptoException("AES-GCM decryption failed: authentication tag is invalid.", e);
        } catch (Exception e) {
            throw new CryptoException("AES-GCM decryption failed.", e);
        }
    }

    private void validateRequest(AesGcmCryptoRequest request) {
        if (request == null || request.input() == null || request.key() == null || request.iv() == null) {
            throw new CryptoException("AES-GCM request, input, key, and IV must not be null.");
        }
    }

    private SecretKeySpec toSecretKey(byte[] key) {
        return new SecretKeySpec(key, CryptoConstants.AES_ALGORITHM);
    }

    private void updateAad(Cipher cipher, byte[] aad) {
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
    }
}
