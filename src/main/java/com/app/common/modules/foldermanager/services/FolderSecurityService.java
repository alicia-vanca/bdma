package com.app.common.modules.foldermanager.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.app.common.definitions.AppConstants;

/**
 * Static utility service for protecting paths inside bdma folders.
 * 
 * Strategy:
 * - bdma root folder: deny delete with inheritance ((OI)(CI)(D))
 * - child folders created by ensureBdmaDir: lock with the same delete-deny rule
 */
public class FolderSecurityService {

    private static final Logger log = LoggerFactory.getLogger(FolderSecurityService.class);

    private static final String CMD_ATTRIB = "attrib";
    private static final String CMD_ICACLS = "icacls";
    private static final String PROPERTY_USER_NAME = "user.name";
    private static final String ICACLS_DENY = "/deny";
    private static final String ICACLS_REMOVE_DENY = "/remove:d";

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private FolderSecurityService() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Ensure directory path exists inside a protected bdma folder.
     *
     * Steps:
     * 1) Lock bdma folder with inheritable delete-deny ((OI)(CI)(D))
     * 2) Walk from bdma folder to target and create missing directories
     *
     * @param dirPath absolute path to the directory
     * @throws IOException if path is not in a protected bdma folder or not a
     *                     directory path
     */
    public static void ensureBdmaDataDir(String dirPath) throws IOException {
        ensureBdmaDataDir(dirPath, true);
    }

    /**
     * Ensure directory path exists inside a bdma folder and apply protection state.
     *
     * @param dirPath           absolute path to the directory
     * @param protectionEnabled true to lock bdma folder, false to unlock it
     * @throws IOException if path is not in a protected bdma folder or not a
     *                     directory path
     */
    public static void ensureBdmaDataDir(String dirPath, boolean protectionEnabled) throws IOException {
        Path bdmaFolder = findDeepestBdmaFolder(dirPath);
        if (bdmaFolder == null) {
            throw new IOException("Path is not in a protected bdma folder: " + dirPath);
        }

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

        if (protectionEnabled) {
            lockSinglePath(bdmaFolder);
        } else {
            unlockSinglePath(bdmaFolder);
        }
        // Unlock any non-bdma parent folders to avoid unintended access issues, but
        // keep the bdma folder protected
        if (!target.equals(bdmaFolder)) {
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
    private static void lockSinglePath(Path path) throws IOException {
        try {
            String user = buildIcaclsTrustee();
            runCommand(CMD_ATTRIB, "+h", "+s", path.toString());
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
     * Unlock a single bdma folder path by removing delete deny ACL from the user.
     * Existing files/subdirectories retain inherited ACL updates from the root.
     */
    private static void unlockSinglePath(Path path) throws IOException {
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

    private static String buildIcaclsTrustee() {
        String user = System.getProperty(PROPERTY_USER_NAME);
        String domain = System.getenv("USERDOMAIN");

        String principal = (domain != null && !domain.isBlank())
                ? domain + "\\" + user
                : user;

        return principal;
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
                if (name.equals(AppConstants.DATA_FOLDER_NAME) || name.equals(AppConstants.BACKUP_FOLDER_NAME)) {
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
        } else {
            log.debug("[cmd] {} => {}", String.join(" ", command), output.isEmpty() ? "(no output)" : output);
        }

        return code;
    }
}
