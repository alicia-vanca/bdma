package com.app.common.services;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.app.common.exceptions.AppException;

/**
 * Encrypts and decrypts SQL patch files using AES-256-GCM.
 *
 * <p>File format (encrypted):
 * <pre>
 *   [4 bytes magic] [12 bytes IV] [N bytes GCM ciphertext+tag]
 * </pre>
 * Magic = 0x42 0x44 0x50 0x01 ("BDP\x01") — BDMA Database Patch v1.
 * GCM tag (16 bytes) is appended automatically by the JCE provider.
 *
 * <p>Only INSERT and UPDATE statements are allowed in patch files.
 * Any other SQL statement will be rejected before execution.
 *
 * <p>Key is a hardcoded 32-byte master key — same across all installations.
 * Never expose or log this key.
 */
@Service
public class PatchCryptoService {

    private static final byte[] MAGIC = {0x42, 0x44, 0x50, 0x01}; // "BDP\x01"
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String ENC_EXTENSION = ".enc";
    private static final int STATEMENT_PREVIEW_LENGTH = 100;

    // Only INSERT and UPDATE are allowed in patch files
    private static final Pattern ALLOWED_SQL = Pattern.compile(
            "^\\s*(INSERT|UPDATE)\\b", Pattern.CASE_INSENSITIVE);

    // Master patch key — hardcoded, same on every installation.
    // Generated once offline. Never log or expose this value.
    private static final byte[] MASTER_PATCH_KEY = {
            (byte) 0xCF, (byte) 0xAC, (byte) 0x50, (byte) 0x15, (byte) 0x88, (byte) 0x14, (byte) 0x07, (byte) 0xAF,
            (byte) 0x7C, (byte) 0xC4, (byte) 0x22, (byte) 0x61, (byte) 0x58, (byte) 0x69, (byte) 0xFA, (byte) 0xFD,
            (byte) 0xF8, (byte) 0x74, (byte) 0x03, (byte) 0xDD, (byte) 0xC1, (byte) 0xF8, (byte) 0xAC, (byte) 0x30,
            (byte) 0x36, (byte) 0x6A, (byte) 0x2E, (byte) 0x8E, (byte) 0xD6, (byte) 0x4F, (byte) 0xD9, (byte) 0xBA
    };

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Encrypts a .sql file using the master patch key.
     * Validates that the file only contains INSERT/UPDATE before encrypting.
     *
     * @param sqlFile path to the plaintext .sql file
     */
    public void encrypt(Path sqlFile) {
        validateInputFile(sqlFile);

        byte[] plaintext;
        try {
            plaintext = Files.readAllBytes(sqlFile);
        } catch (IOException e) {
            throw new AppException("Cannot read SQL patch file: " + sqlFile, e);
        }

        // Validate SQL content before encrypting
        String sql = new String(plaintext, StandardCharsets.UTF_8);
        validateSqlStatements(parseSqlStatements(sql));

        byte[] key = getMasterKey();
        byte[] iv = generateIv();
        byte[] ciphertext = doEncrypt(plaintext, key, iv);
        clearKey(key);

        // Layout: [magic 4B][iv 12B][ciphertext+tag NB]
        ByteBuffer buf = ByteBuffer.allocate(MAGIC.length + IV_LENGTH + ciphertext.length);
        buf.put(MAGIC);
        buf.put(iv);
        buf.put(ciphertext);

        Path outFile = resolveOutputPath(sqlFile);
        writeFile(outFile, buf.array());
    }

    /**
     * Decrypts a .sql.enc file and returns parsed, validated SQL statements.
     * Only INSERT and UPDATE statements are allowed.
     *
     * @param encFile path to the encrypted .sql.enc file
     * @return list of validated SQL statements ready for execution
     */
    public List<String> decryptAndValidate(Path encFile) {
        validateInputFile(encFile);

        byte[] raw;
        try {
            raw = Files.readAllBytes(encFile);
        } catch (IOException e) {
            throw new AppException("Cannot read encrypted patch file: " + encFile, e);
        }

        validateMagic(raw, encFile);

        // Parse layout: [magic 4B][iv 12B][ciphertext+tag NB]
        int minLength = MAGIC.length + IV_LENGTH + GCM_TAG_BITS / 8;
        if (raw.length < minLength) {
            throw new AppException("Encrypted patch file is too short or corrupted: " + encFile);
        }

        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(raw, MAGIC.length, iv, 0, IV_LENGTH);

        int ciphertextOffset = MAGIC.length + IV_LENGTH;
        byte[] ciphertext = new byte[raw.length - ciphertextOffset];
        System.arraycopy(raw, ciphertextOffset, ciphertext, 0, ciphertext.length);

        byte[] key = getMasterKey();
        byte[] plaintext = doDecrypt(ciphertext, key, iv, encFile);
        clearKey(key);

        String sql = new String(plaintext, StandardCharsets.UTF_8);
        List<String> statements = parseSqlStatements(sql);
        validateSqlStatements(statements);
        return statements;
    }

    // -------------------------------------------------------------------------
    // SQL parsing and validation
    // -------------------------------------------------------------------------

    private List<String> parseSqlStatements(String sql) {
        List<String> result = new java.util.ArrayList<>();
        for (String raw : sql.split(";")) {
            String stmt = raw.lines()
                    .filter(line -> !line.isBlank() && !line.stripLeading().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b)
                    .trim();
            if (!stmt.isEmpty()) {
                result.add(stmt);
            }
        }
        if (result.isEmpty()) {
            throw new AppException("Patch file contains no valid SQL statements");
        }
        return result;
    }

    private void validateSqlStatements(List<String> statements) {
        for (String stmt : statements) {
            if (!ALLOWED_SQL.matcher(stmt).find()) {
                throw new AppException(
                        "Only INSERT and UPDATE statements are allowed in patch file: " + summarize(stmt));
            }
        }
    }

    private String summarize(String stmt) {
        String oneLine = stmt.replaceAll("\\s+", " ").trim();
        return oneLine.length() > STATEMENT_PREVIEW_LENGTH
                ? oneLine.substring(0, STATEMENT_PREVIEW_LENGTH) + "..."
                : oneLine;
    }

    // -------------------------------------------------------------------------
    // Crypto helpers
    // -------------------------------------------------------------------------

    private byte[] doEncrypt(byte[] plaintext, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, toSecretKey(key), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new AppException("Encryption failed", e);
        }
    }

    private byte[] doDecrypt(byte[] ciphertext, byte[] key, byte[] iv, Path encFile) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, toSecretKey(key), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new AppException(
                    "Decryption failed: file is corrupted or was encrypted with a different key — " + encFile, e);
        } catch (Exception e) {
            throw new AppException("Decryption failed: " + encFile, e);
        }
    }

    private SecretKey toSecretKey(byte[] key) {
        return new SecretKeySpec(key, "AES");
    }

    private byte[] generateIv() {
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        return iv;
    }

    private byte[] getMasterKey() {
        if (MASTER_PATCH_KEY.length != 32) {
            throw new AppException("Master patch key is not configured (must be 32 bytes)");
        }
        return Arrays.copyOf(MASTER_PATCH_KEY, MASTER_PATCH_KEY.length);
    }

    private void clearKey(byte[] key) {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
        }
    }

    // -------------------------------------------------------------------------
    // File helpers
    // -------------------------------------------------------------------------

    private void validateInputFile(Path file) {
        if (!Files.exists(file)) {
            throw new AppException("File not found: " + file);
        }
        if (!Files.isRegularFile(file)) {
            throw new AppException("Not a regular file: " + file);
        }
    }

    private void validateMagic(byte[] raw, Path file) {
        if (raw.length < MAGIC.length) {
            throw new AppException("File is not a valid BDMA patch (too small): " + file);
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (raw[i] != MAGIC[i]) {
                throw new AppException(
                        "File is not a valid BDMA patch (invalid magic bytes): " + file);
            }
        }
    }

    private Path resolveOutputPath(Path inputFile) {
        String filename = inputFile.getFileName().toString();
        String outName = filename.endsWith(ENC_EXTENSION)
                ? filename.substring(0, filename.length() - ENC_EXTENSION.length())
                : filename + ENC_EXTENSION;
        return inputFile.resolveSibling(outName);
    }

    private void writeFile(Path path, byte[] data) {
        try {
            Files.write(path, data);
        } catch (IOException e) {
            throw new AppException("Cannot write output file: " + path, e);
        }
    }
}
