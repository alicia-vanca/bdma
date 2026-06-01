package com.app.dev.patchmanager.services;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.app.common.exceptions.AppException;
import com.app.common.modules.i18n.I18n;
import com.app.common.repositories.PatchApplyLogRepository;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;

/**
 * AES-256-GCM patch encryption for SQL files.
 *
 * <p>
 * Encrypted files include magic, a UUID, IV, and ciphertext/tag.
 * The patch UUID is authenticated to prevent header tampering.
 * Only INSERT and UPDATE statements are accepted.
 */
@Service
public class PatchCryptoService {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /**
     * Magic bytes identifying a v2 BDP patch file.
     */
    private static final byte[] MAGIC = { 0x42, 0x44, 0x50, 0x02 }; // "BDP\x02"

    private static final int MAGIC_LENGTH = 4;
    private static final int PATCH_ID_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;
    private static final int HEADER_LENGTH = MAGIC_LENGTH + PATCH_ID_LENGTH + IV_LENGTH;
    private static final int MIN_FILE_LENGTH = HEADER_LENGTH + GCM_TAG_BYTES;
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024; // 10 MB guard

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int STMT_PREVIEW_LEN = 120;

    /**
     * Expected key length in bytes (AES-256).
     */
    private static final int KEY_BYTES = 32;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    /**
     * 64-char hex AES-256 key loaded from the environment.
     * Set via: PATCH_MASTER_KEY=<64 hex chars>
     * Generate once with: openssl rand -hex 32
     */
    @Value("${patch.master.key}")
    private String masterKeyHex;

    private final PatchApplyLogRepository patchApplyLogRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public PatchCryptoService(PatchApplyLogRepository patchApplyLogRepository) {
        this.patchApplyLogRepository = patchApplyLogRepository;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Encrypts SQL text directly to an encrypted patch file without creating a
     * plaintext file.
     *
     * @param sql     plaintext SQL text
     * @param outFile encrypted output file
     * @param patchId stable patch identifier written into the encrypted header
     */
    public void encryptSql(String sql, Path outFile, UUID patchId) {
        if (sql == null) {
            throw new AppException(I18n.get("dev.patch.validation.empty"));
        }
        encryptPlaintext(sql.getBytes(StandardCharsets.UTF_8), outFile, patchId);
    }

    /**
     * Decrypts an encrypted patch and returns validated SQL statements.
     *
     * @param encFile encrypted {@code .sql.enc} file
     * @return validated SQL statements
     */
    public DecryptResult decryptAndValidate(Path encFile) {
        validateInputFile(encFile);

        byte[] raw = readFile(encFile);
        validateMagic(raw, encFile);

        if (raw.length < MIN_FILE_LENGTH) {
            throw new AppException("Encrypted patch file is too short or corrupted: " + encFile);
        }

        // Read patch ID from bytes [4..19]
        UUID patchId = bytesToUuid(raw);

        // Guard against duplicate apply BEFORE decryption
        if (patchApplyLogRepository.existsByPatchId(patchId)) {
            throw new AppException(
                    "Patch [" + patchId + "] has already been applied. Aborting to prevent duplicate execution.");
        }

        // Read IV from bytes [20..31]
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(raw, MAGIC_LENGTH + PATCH_ID_LENGTH, iv, 0, IV_LENGTH);

        // Ciphertext starts at byte 32
        byte[] ciphertext = new byte[raw.length - HEADER_LENGTH];
        System.arraycopy(raw, HEADER_LENGTH, ciphertext, 0, ciphertext.length);

        byte[] key = loadMasterKey();
        byte[] plaintext;
        byte[] aad = Arrays.copyOf(raw, HEADER_LENGTH);
        try {
            plaintext = doDecrypt(ciphertext, key, iv, aad, encFile);
        } finally {
            clearKey(key);
        }

        String sql = new String(plaintext, StandardCharsets.UTF_8);
        List<String> statements = parseSqlStatements(sql);
        validateSqlStatements(statements);

        return new DecryptResult(patchId, statements);
    }

    // =========================================================================
    // Result type
    // =========================================================================

    /**
     * Carries the patch UUID and the validated SQL statements returned by
     * {@link #decryptAndValidate(Path)}. The caller is responsible for
     * persisting a {@code PatchApplyRecord} after successful execution.
     */
    public record DecryptResult(UUID patchId, List<String> statements) {
    }

    // =========================================================================
    // SQL parsing and validation
    // =========================================================================

    /**
     * Validates editable patch SQL before packing it into an encrypted patch.
     *
     * @param sql plaintext SQL containing only INSERT or UPDATE statements
     * @return parsed SQL statements ready for display or execution
     */
    public List<String> validateSql(String sql) {
        List<String> statements = parseSqlStatements(sql);
        validateSqlStatements(statements);
        return statements;
    }

    /**
     * Parses SQL text reliably using JSQLParser.
     */
    private List<String> parseSqlStatements(String sql) {
        try {
            var stmts = CCJSqlParserUtil.parseStatements(sql);
            List<String> result = new ArrayList<>();
            for (Statement s : stmts) {
                String trimmed = s.toString().trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            if (result.isEmpty()) {
                throw new AppException(I18n.get("dev.patch.validation.empty"));
            }
            return result;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(I18n.get("dev.patch.validation.statementParse", findFirstProblemStatement(sql)),
                    e);
        }
    }

    /**
     * Validates that each SQL statement is INSERT or UPDATE.
     */
    private void validateSqlStatements(List<String> statements) {
        for (String stmtSql : statements) {
            Statement parsed;
            try {
                parsed = CCJSqlParserUtil.parse(stmtSql);
            } catch (Exception e) {
                throw new AppException(I18n.get("dev.patch.validation.statementParse", summarize(stmtSql)), e);
            }

            if (!(parsed instanceof Insert) && !(parsed instanceof Update)) {
                throw new AppException(I18n.get("dev.patch.validation.onlyInsertUpdate", summarize(stmtSql)));
            }
        }
    }

    /**
     * JSQLParser aborts full-script parsing before exposing the bad fragment.
     * Split on semicolons as a fallback so users can see the first unparsable part.
     */
    private String findFirstProblemStatement(String sql) {
        for (String candidate : sql.split(";")) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                CCJSqlParserUtil.parse(trimmed);
            } catch (Exception e) {
                return summarize(trimmed);
            }
        }
        return summarize(sql);
    }

    private String summarize(String stmt) {
        String oneLine = stmt.replaceAll("\\s+", " ").trim();
        return oneLine.length() > STMT_PREVIEW_LEN
                ? oneLine.substring(0, STMT_PREVIEW_LEN) + "..."
                : oneLine;
    }

    // =========================================================================
    // Crypto helpers
    // =========================================================================

    private void encryptPlaintext(byte[] plaintext, Path outFile, UUID patchId) {
        validateSql(new String(plaintext, StandardCharsets.UTF_8));

        byte[] iv = generateIv();
        byte[] key = loadMasterKey();
        byte[] ciphertext;
        try {
            byte[] aad = buildHeaderAad(patchId, iv);
            ciphertext = doEncrypt(plaintext, key, iv, aad);
        } finally {
            clearKey(key);
        }

        // Layout: [magic 4B][patchId 16B][iv 12B][ciphertext+tag NB]
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LENGTH + ciphertext.length);
        buf.put(MAGIC);
        buf.put(uuidToBytes(patchId));
        buf.put(iv);
        buf.put(ciphertext);

        writeFile(outFile, buf.array());
    }

    private byte[] doEncrypt(byte[] plaintext, byte[] key, byte[] iv, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, toSecretKey(key), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new AppException("Encryption failed.", e);
        }
    }

    private byte[] doDecrypt(byte[] ciphertext, byte[] key, byte[] iv, byte[] aad, Path encFile) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, toSecretKey(key), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new AppException(
                    "Decryption failed: file is corrupted or was encrypted with a different key ΓÇö " + encFile, e);
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

    private byte[] buildHeaderAad(UUID patchId, byte[] iv) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LENGTH);
        buf.put(MAGIC);
        buf.put(uuidToBytes(patchId));
        buf.put(iv);
        return buf.array();
    }

    /**
     * Decodes the master key from its hex representation.
     * The caller MUST call {@link #clearKey(byte[])} on the returned array in a
     * finally block.
     *
     * <p>
     * The key is sourced from the {@code patch.master.key} Spring property, which
     * should be
     * bound to the {@code PATCH_MASTER_KEY} environment variable (or Vault/KMS
     * secret).
     * Generate once with: {@code openssl rand -hex 32}
     */
    private byte[] loadMasterKey() {
        if (masterKeyHex == null || masterKeyHex.isBlank()) {
            throw new AppException(
                    "Master patch key is not configured. Set the PATCH_MASTER_KEY environment variable.");
        }
        String hex = masterKeyHex.trim();
        if (hex.length() != KEY_BYTES * 2) {
            throw new AppException(
                    "Master patch key must be a 64-character hex string (32 bytes). Got length: " + hex.length());
        }
        byte[] key = new byte[KEY_BYTES];
        for (int i = 0; i < KEY_BYTES; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new AppException("Master patch key contains non-hex characters.");
            }
            key[i] = (byte) ((hi << 4) | lo);
        }
        return key;
    }

    /**
     * Overwrites a key buffer with zeros to minimize time the secret lives in heap
     * memory.
     */
    private void clearKey(byte[] key) {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
        }
    }

    // =========================================================================
    // UUID Γåö bytes helpers
    // =========================================================================

    private byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    private UUID bytesToUuid(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data, MAGIC_LENGTH, 16);
        long msb = bb.getLong();
        long lsb = bb.getLong();
        return new UUID(msb, lsb);
    }

    // =========================================================================
    // File helpers
    // =========================================================================

    private void validateInputFile(Path file) {
        if (!Files.exists(file)) {
            throw new AppException("File not found: " + file);
        }
        if (!Files.isRegularFile(file)) {
            throw new AppException("Not a regular file: " + file);
        }
    }

    private byte[] readFile(Path file) {
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                throw new AppException(
                        "Patch file exceeds maximum allowed size (" + MAX_FILE_BYTES / 1024 / 1024
                                + " MB): " + file);
            }
            return Files.readAllBytes(file);
        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            throw new AppException("Cannot read file: " + file, e);
        }
    }

    private void validateMagic(byte[] raw, Path file) {
        if (raw.length < MAGIC.length) {
            throw new AppException("File is not a valid BDP patch (too small): " + file);
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (raw[i] != MAGIC[i]) {
                throw new AppException(
                        "File is not a valid BDP patch (invalid magic bytes): " + file);
            }
        }
    }

    private void writeFile(Path path, byte[] data) {
        try {
            Files.write(path, data);
        } catch (IOException e) {
            throw new AppException("Cannot write output file: " + path, e);
        }
    }
}
