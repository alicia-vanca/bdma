package com.app.common.modules.foldermanager.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
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
import com.app.common.services.WindowsCommandService;
import com.app.common.services.WindowsCommandService.CommandResult;

/**
 * Static utility service for protecting paths inside bdma folders.
 * <p>
 * Strategy:
 * - bdma root folder: deny delete (D)
 * - child folders created by ensureBdmaDir: lock with the same delete-deny rule
 */
public class FolderSecurityService {

    private static final Logger log = LoggerFactory.getLogger(FolderSecurityService.class);
    private static final WindowsCommandService WINDOWS_COMMAND_SERVICE = new WindowsCommandService();

    private static final String CMD_ATTRIB = "attrib";
    private static final String CMD_ICACLS = "icacls";
    private static final String CMD_TAKEOWN = "takeown";
    private static final String PROPERTY_USER_NAME = "user.name";
    private static final String WINDOWS_EVERYONE_SID = "*S-1-1-0";
    private static final String ICACLS_DENY = "/deny";
    private static final String ICACLS_GRANT_REPLACE = "/grant:r";
    private static final String ICACLS_REMOVE_DENY = "/remove:d";
    private static final String ICACLS_RESET = "/reset";
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
     * Ensure directory path exists and is accessible, with optional bdma folder
     * protection.
     * <p>
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

        Path bdmaFolder = findDeepestBdmaFolder(target);
        boolean repairEnabled = bdmaFolder != null;

        if (bdmaFolder != null) {
            repairStorageRootIfInaccessible(bdmaFolder);
        }

        // Walk from drive root to target, creating each missing directory.
        Path root = target.getRoot();
        if (root != null) {
            Path current = root;
            for (Path segment : root.relativize(target)) {
                current = current.resolve(segment);
                if (Files.notExists(current)) {
                    createDirectoryWithRepair(current, repairEnabled);
                }
            }
        }

        // Apply protection only if path is inside a bdma folder
        if (bdmaFolder != null && !protectionEnabled) {
            unlockSinglePathWithRepair(bdmaFolder);
        }
        // Unlock any non-bdma parent folders to avoid unintended access issues, but
        // keep the bdma folder protected
        if (!target.equals(bdmaFolder)) {
            unlockSinglePathWithRepair(target);
        }
        if (bdmaFolder != null && protectionEnabled) {
            lockSinglePathWithRepair(bdmaFolder);
        }
    }

    // ── Internal Implementation ──────────────────────────────────────────────

    /**
     * Lock a single directory path by denying delete.
     * <p>
     * @param path the path to lock
     * @throws IOException if lock operations fail
     */
    public static void lockSinglePath(Path path) throws IOException {
        lockSinglePathWithRepair(path);
    }

    private static void lockSinglePathWithRepair(Path path) throws IOException {
        runWithAclRepair(path, () -> lockSinglePathRaw(path));
    }

    private static void lockSinglePathRaw(Path path) throws IOException {
        try {
            String user = buildIcaclsTrustee();
            hideSinglePath(path);
            runRequiredCommand(path, "Lock folder",
                    CMD_ICACLS,
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
     * <p>
     * @param path the path to hide
     * @throws IOException if the hide operation fails
     */
    public static void hideSinglePath(Path path) throws IOException {
        try {
            runOptionalCommand(path, "Apply hidden attributes",
                    CMD_ATTRIB, "+h", "+s", path.toString());
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
        unlockSinglePathWithRepair(path);
    }

    private static void unlockSinglePathWithRepair(Path path) throws IOException {
        runWithAclRepair(path, () -> unlockSinglePathRaw(path));
    }

    private static void unlockSinglePathRaw(Path path) throws IOException {
        try {
            String user = buildIcaclsTrustee();
            runRequiredCommand(path, "Unlock folder",
                    CMD_ICACLS,
                    path.toString(),
                    ICACLS_REMOVE_DENY,
                    user);
            runOptionalCommand(path, "Remove hidden attributes",
                    CMD_ATTRIB, "-h", "-s", path.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Unlock interrupted for: " + path, e);
        }
    }

    /**
     * Generate checksum sidecar files for a backup file.
     * <p>
     * Creates two files in the same directory as the input file:
     * - {@code <name>.sha256}
     * - {@code <name>.sha256.backup}
     * <p>
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
     * <p>
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
     * <p>
     * @param path absolute path to search within
     * @return the deepest bdma folder path, or null if not found
     * @throws IllegalArgumentException if path is not absolute
     */
    private static Path findDeepestBdmaFolder(Path path) {
        // Ensure we're working with an absolute path
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Path must be absolute: " + path);
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

    private static void createDirectoryWithRepair(Path directory, boolean repairEnabled) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (IOException e) {
            if (!repairEnabled || directory.getParent() == null) {
                throw e;
            }
            repairAclFromNearestAncestor(directory.getParent(), directory.getParent(), e);
            Files.createDirectory(directory);
        }
    }

    private static void repairStorageRootIfInaccessible(Path bdmaFolder) throws IOException {
        Path storageRoot = bdmaFolder.getParent();
        if (storageRoot == null || Files.notExists(storageRoot) || Files.isReadable(storageRoot)) {
            return;
        }

        repairAclFromNearestAncestor(
                storageRoot,
                storageRoot,
                new AccessDeniedException(storageRoot.toString()));
        if (!Files.isReadable(storageRoot)) {
            throw new AccessDeniedException(storageRoot.toString());
        }
    }

    private static void runWithAclRepair(Path path, AclOperation operation) throws IOException {
        try {
            operation.run();
        } catch (IOException firstFailure) {
            if (findDeepestBdmaFolder(path) == null) {
                throw firstFailure;
            }
            Path parent = path.getParent();
            if (parent == null) {
                throw firstFailure;
            }
            repairAclFromNearestAncestor(parent, path, firstFailure);
            operation.run();
        }
    }

    private static void repairAclFromNearestAncestor(
            Path repairStart,
            Path repairTarget,
            IOException firstFailure) throws IOException {
        Path root = repairTarget.getRoot();
        if (root == null || repairStart == null) {
            throw firstFailure;
        }

        Path current = repairStart;
        IOException lastFailure = firstFailure;
        Path repairedAncestor = null;
        while (!current.equals(root)) {
            try {
                repairSinglePathAcl(current);
                repairedAncestor = current;
                break;
            } catch (IOException repairFailure) {
                lastFailure = repairFailure;
                current = current.getParent();
            }
        }

        if (repairedAncestor == null) {
            throw new IOException(
                    "Unable to repair folder permissions before filesystem root: " + repairTarget,
                    lastFailure);
        }

        Path descendant = repairedAncestor;
        for (Path segment : repairedAncestor.relativize(repairTarget)) {
            descendant = descendant.resolve(segment);
            repairSinglePathAcl(descendant);
        }
    }

    private static void repairSinglePathAcl(Path path) throws IOException {
        Path root = path.getRoot();
        if (path.equals(root)) {
            throw new IOException("Refusing to repair filesystem root ACL: " + path);
        }
        if (Files.notExists(path)) {
            throw new IOException("Cannot repair missing path: " + path);
        }

        try {
            String user = buildIcaclsTrustee();
            CommandResult takeownResult = runCommand(CMD_TAKEOWN, "/F", path.toString());
            if (takeownResult.exitCode() != 0 && log.isDebugEnabled()) {
                log.debug("[acl-repair] takeown failed for {}: {}", path, takeownResult.output());
            }
            runRequiredCommand(path, "Reset folder ACL",
                    CMD_ICACLS,
                    path.toString(),
                    ICACLS_RESET);
            runRequiredCommand(path, "Grant folder control",
                    CMD_ICACLS,
                    path.toString(),
                    ICACLS_GRANT_REPLACE,
                    user + ":(OI)(CI)F");
            runRequiredCommand(path, "Remove deny ACL",
                    CMD_ICACLS,
                    path.toString(),
                    ICACLS_REMOVE_DENY,
                    user);
            runRequiredCommand(path, "Remove Everyone deny ACL",
                    CMD_ICACLS,
                    path.toString(),
                    ICACLS_REMOVE_DENY,
                    WINDOWS_EVERYONE_SID);
            runOptionalCommand(path, "Remove hidden attributes",
                    CMD_ATTRIB, "-h", "-s", path.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ACL repair interrupted for: " + path, e);
        }
    }

    /**
     * Execute a system command and throw when it exits unsuccessfully.
     * <p>
     * @param path    path used for diagnostics
     * @param action  action name used for diagnostics
     * @param command command and arguments to execute
     * @throws IOException          if command execution fails
     * @throws InterruptedException if command is interrupted
     */
    private static void runRequiredCommand(Path path, String action, String... command)
            throws IOException, InterruptedException {
        CommandResult result = runCommand(command);
        if (result.exitCode() != 0) {
            log.warn("[cmd] {} failed with exit code {} for {}: {}",
                    action, result.exitCode(), path, result.output());
            throw new IOException(action + " failed for " + path + " (exit " + result.exitCode() + "): "
                    + result.output());
        }
    }

    private static void runOptionalCommand(Path path, String action, String... command)
            throws IOException, InterruptedException {
        CommandResult result = runCommand(command);
        if (result.exitCode() != 0 && log.isDebugEnabled()) {
            log.debug("[cmd] Optional operation '{}' failed for {} (exit {}): {}",
                    action, path, result.exitCode(), result.output());
        }
    }

    private static CommandResult runCommand(String... command) throws IOException, InterruptedException {
        return WINDOWS_COMMAND_SERVICE.runCmdWithOutput(null, command);
    }

    @FunctionalInterface
    private interface AclOperation {
        void run() throws IOException;
    }

    /**
     * Validate backup file integrity using SHA-256 sidecar files.
     * <p>
     * Validation order:
     * 1) Compare file hash with {@code <name>.sha256}
     * 2) If mismatch/missing, compare with {@code <name>.sha256.backup}
     * 3) Return false if neither sidecar matches
     * <p>
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

            boolean primaryValid = actualChecksum.equalsIgnoreCase(expectedPrimary);
            boolean backupValid = actualChecksum.equalsIgnoreCase(expectedBackup);

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
