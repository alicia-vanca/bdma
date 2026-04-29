package com.app.common.modules.foldermanager.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for converting logical file paths to obfuscated physical paths.
 * Hashes filenames and appends Windows CLSID to disguise files as system
 * folders.
 */
public final class FilePathHasher {

    // Control Panel CLSID - makes files appear as system folders in Explorer
    private static final String CLSID_SUFFIX = ".{21EC2020-3AEA-1069-A2DD-08002B30309D}";

    private FilePathHasher() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Convert logical filename to physical hashed filename with CLSID suffix.
     * Only replaces the filename, preserves the directory path.
     * 
     * Example:
     * Input: "C:\\data_bdma\\users\\john\\documents\\contract.pdf"
     * Output:
     * "C:\\data_bdma\\users\\john\\documents\\a3f5e8d9c2b1.{21EC2020-3AEA-1069-A2DD-08002B30309D}"
     * 
     * @param filePath Full path to the file
     * @return Physical path with hashed filename and CLSID suffix
     */
    public static String toPhysicalPath(String filePath) {
        Path path = Path.of(filePath);
        Path parent = path.getParent();
        String filename = path.getFileName().toString();

        String hashedFilename = hashFilename(filename) + CLSID_SUFFIX;

        if (parent != null) {
            return parent.resolve(hashedFilename).toString();
        } else {
            return hashedFilename;
        }
    }

    /**
     * Hash filename using SHA-256 and return first 64 hex characters.
     * Provides consistent, collision-resistant obfuscation for file names.
     * 
     * @param filename the original filename to hash
     * @return first 64 hex characters of SHA-256 hash (full hash)
     * @throws IllegalStateException if SHA-256 algorithm is not available
     */
    private static String hashFilename(String filename) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(filename.getBytes(StandardCharsets.UTF_8));
            // Use full 32 bytes (64 hex chars) for maximum collision resistance
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
