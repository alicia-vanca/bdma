package com.app.common.modules.foldermanager.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.app.common.definitions.AppConstants;

/**
 * Static utility service for protecting paths inside bdma folders.
 * 
 * Strategy:
 * - bdma root folder: deny delete (D)
 * - child folders created by ensureBdmaDir: lock with the same delete-deny rule
 */
public class FolderSecurityService {

    private static final Logger log = LoggerFactory.getLogger(FolderSecurityService.class);

    private static final String CMD_ATTRIB = "attrib";
    private static final String CMD_ICACLS = "icacls";
    private static final String PROPERTY_USER_NAME = "user.name";
    private static final String ICACLS_DENY = "/deny";
    private static final String ICACLS_REMOVE_DENY = "/remove:d";
    private static final String CHECKSUM_EXT = ".sha256";
    private static final String CHECKSUM_BACKUP_EXT = ".sha256.backup";
    private static final Pattern SHA256_PATTERN = Pattern.compile("(?i)\\b[a-f0-9]{64}\\b");
    private static final int HASH_BUFFER_SIZE = 8192;

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private FolderSecurityService() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Ensure directory path exists and is accessible.
     *
     * If the path is inside a bdma folder, lock the bdma folder with delete-deny
     * (D).
     * Otherwise, simply create the directory structure.
     *
     * @param dirPath absolute path to the directory
     * @throws IOException if path exists but is not a directory, or directory
     *                     creation fails
     */
    public static void ensureDirAccessible(String dirPath) throws IOException {
        ensureDirAccessible(dirPath, true);
    }

    /**
     * Ensure directory path exists and is accessible, with optional bdma folder
     * protection.
     *
     * @param dirPath           absolute path to the directory
     * @param protectionEnabled true to lock bdma folder if found, false to unlock
     *                          it
     * @throws IOException if path exists but is not a directory, or directory
     *                     creation fails
     */
    public static void ensureDirAccessible(String dirPath, boolean protectionEnabled) throws IOException {
        Path target = Path.of(dirPath);
        if (Files.exists(target) && !Files.isDirectory(target)) {
            throw new IOException("Path is not a directory: " + dirPath);
        }

        // Walk from drive root to target, creating each missing directory.
        Path root = target.getRoot();
        if (root != null) {
            Path current = root;
            for (Path segment : root.relativize(target)) {
                current = current.resolve(segment);
                if (!Files.exists(current)) {
                    Files.createDirectory(current);
                }
            }
        }

        // Apply protection only if path is inside a bdma folder
        Path bdmaFolder = findDeepestBdmaFolder(dirPath);
        if (bdmaFolder != null) {
            if (protectionEnabled) {
                lockSinglePath(bdmaFolder);
            } else {
                unlockSinglePath(bdmaFolder);
            }
        }
        // Unlock any non-bdma parent folders to avoid unintended access issues, but
        // keep the bdma folder protected
        if (bdmaFolder == null || !target.equals(bdmaFolder)) {
            unlockSinglePath(target);
        }
    }

    // ── Internal Implementation ──────────────────────────────────────────────

    /**
     * Lock a single directory path by denying delete.
     *
     * @param path the path to lock
     * @throws IOException if lock operations fail
     */
    public static void lockSinglePath(Path path) throws IOException {
        try {
            String user = buildIcaclsTrustee();
            hideSinglePath(path);
            runCommand(CMD_ICACLS,
                    path.toString(),
                    ICACLS_DENY,
                    user + ":(D)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Lock interrupted for: " + path, e);
        }
    }

    /**
     * Hide a single path from normal Explorer views without changing ACLs.
     *
     * @param path the path to hide
     * @throws IOException if the hide operation fails
     */
    public static void hideSinglePath(Path path) throws IOException {
        try {
            runCommand(CMD_ATTRIB, "+h", "+s", path.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Hide interrupted for: " + path, e);
        }
    }

    /**
     * Unlock a single bdma folder path by removing delete deny ACL from the user.
     * Existing files/subdirectories retain inherited ACL updates from the root.
     */
    public static void unlockSinglePath(Path path) throws IOException {
        try {
            String user = buildIcaclsTrustee();
            runCommand(CMD_ICACLS,
                    path.toString(),
                    ICACLS_REMOVE_DENY,
                    user);
            runCommand(CMD_ATTRIB, "-h", "-s", path.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Unlock interrupted for: " + path, e);
        }
    }

    /**
     * Generate checksum sidecar files for a backup file.
     *
     * Creates two files in the same directory as the input file:
     * - {@code <name>.sha256}
     * - {@code <name>.sha256.backup}
     *
     * @param backupPath the backup file path
     * @throws IOException if input file is missing/not a regular file or checksum
     *                     files cannot be written
     */
    public static void generateBackupHashFiles(Path backupPath) throws IOException {
        if (backupPath == null) {
            throw new IOException("Backup file path is null");
        }
        if (!Files.exists(backupPath) || !Files.isRegularFile(backupPath)) {
            throw new IOException("Backup file not found or not a regular file: " + backupPath);
        }

        String checksum = calculateSha256Hex(backupPath);
        String payload = checksum + System.lineSeparator();
        Files.writeString(primaryChecksumPath(backupPath), payload, StandardCharsets.UTF_8);
        Files.writeString(backupChecksumPath(backupPath), payload, StandardCharsets.UTF_8);
    }

    /**
     * Check whether a path is a checksum sidecar file created for a backup file.
     *
     * @param path candidate file path
     * @return true when the filename ends with .sha256 or .sha256.backup
     */
    public static boolean isChecksumSidecarFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(CHECKSUM_EXT) || name.endsWith(CHECKSUM_BACKUP_EXT);
    }

    private static String buildIcaclsTrustee() {
        String user = System.getProperty(PROPERTY_USER_NAME);
        String domain = System.getenv("USERDOMAIN");

        return (domain != null && !domain.isBlank())
                ? domain + "\\" + user
                : user;
    }

    /**
     * Find the deepest bdma folder (data or backup) in the given path hierarchy.
     *
     * @param filePath absolute path to search within
     * @return the deepest bdma folder path, or null if not found
     * @throws IllegalArgumentException if path is not absolute
     */
    private static Path findDeepestBdmaFolder(String filePath) {
        Path path = Path.of(filePath);

        // Ensure we're working with an absolute path
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Path must be absolute: " + filePath);
        }

        Path current = path;
        while (current != null) {
            if (current.getFileName() != null) {
                String name = current.getFileName().toString();
                if (name.equals(AppConstants.SYNC_FOLDER_NAME) || name.equals(AppConstants.BACKUP_FOLDER_NAME)) {
                    return current;
                }
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Execute a system command and capture output.
     *
     * @param command command and arguments to execute
     * @return exit code of the command
     * @throws IOException          if command execution fails
     * @throws InterruptedException if command is interrupted
     */
    private static int runCommand(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        StringBuilder sb = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty())
                    sb.append('\n');
                sb.append(line);
            }
        }
        String output = sb.toString().trim();
        int code = process.waitFor();

        if (code != 0) {
            if (log.isWarnEnabled()) {
                log.warn("[cmd] Command failed with exit code {}: {} - Output: {}",
                        code, String.join(" ", command), output);
            }
        } else if (log.isDebugEnabled()) {
            // log.debug("[cmd] {} => {}", String.join(" ", command), output.isEmpty() ?
            // "(no output)" : output);
        }

        return code;
    }

    /**
     * Validate backup file integrity using SHA-256 sidecar files.
     *
     * Validation order:
     * 1) Compare file hash with {@code <name>.sha256}
     * 2) If mismatch/missing, compare with {@code <name>.sha256.backup}
     * 3) Return false if neither sidecar matches
     *
     * @param backupPath backup file path
     * @return true when checksum matches primary or backup sidecar, otherwise false
     */
    public static boolean isValidBackupFile(Path backupPath) {
        try {
            if (backupPath == null || !Files.exists(backupPath) || !Files.isRegularFile(backupPath)) {
                return false;
            }

            String actualChecksum = calculateSha256Hex(backupPath);
            String expectedPrimary = readChecksumValue(primaryChecksumPath(backupPath));
            String expectedBackup = readChecksumValue(backupChecksumPath(backupPath));

            boolean primaryValid = expectedPrimary != null && actualChecksum.equalsIgnoreCase(expectedPrimary);
            boolean backupValid = expectedBackup != null && actualChecksum.equalsIgnoreCase(expectedBackup);

            // If at least one hash file is valid, regenerate both to ensure consistency
            if (primaryValid || backupValid) {
                if (!primaryValid || !backupValid) {
                    log.info("[checksum] Regenerating hash files for consistency: {}", backupPath);
                    generateBackupHashFiles(backupPath);
                }
                return true;
            }

            return false;
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("[checksum] Failed to validate backup file {}: {}", backupPath, e.getMessage());
            }
            return false;
        }
    }

    public static Path primaryChecksumPath(Path backupPath) {
        return backupPath.resolveSibling(backupPath.getFileName().toString() + CHECKSUM_EXT);
    }

    public static Path backupChecksumPath(Path backupPath) {
        return backupPath.resolveSibling(backupPath.getFileName().toString() + CHECKSUM_BACKUP_EXT);
    }

    private static String calculateSha256Hex(Path filePath) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }

        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(filePath)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static String readChecksumValue(Path checksumPath) throws IOException {
        if (!Files.exists(checksumPath) || !Files.isRegularFile(checksumPath)) {
            return null;
        }

        String content = Files.readString(checksumPath, StandardCharsets.UTF_8);
        Matcher matcher = SHA256_PATTERN.matcher(content);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : null;
    }
}
