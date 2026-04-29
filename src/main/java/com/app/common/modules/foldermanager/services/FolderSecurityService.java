package com.app.common.modules.foldermanager.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.app.common.definitions.AppConstants;

/**
 * Static utility service for locking/unlocking protected bdma folders.
 * 
 * Optimized strategy:
 * - Ancestor folders (bdma root to parent): Read-only permission (prevents
 * move/rename/delete)
 * - Target file: Fully locked (deny all access)
 * 
 * This allows fast existence checks without unlocking while maintaining
 * security.
 */
public class FolderSecurityService {

    private static final Logger log = LoggerFactory.getLogger(FolderSecurityService.class);

    private static final String CMD_ATTRIB = "attrib";
    private static final String CMD_ICACLS = "icacls";
    private static final String PROPERTY_USER_NAME = "user.name";
    private static final String ICACLS_DENY = "/deny";
    private static final String ICACLS_GRANT = "/grant";

    // Reference counting for each individual path (file/folder)
    private static final Map<Path, Integer> pathRefCount = new ConcurrentHashMap<>();
    private static final Map<Path, ReentrantLock> pathLocks = new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private FolderSecurityService() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Check if there are any paths still unlocked (reference count > 0).
     *
     * @return true if any paths have active unlock references
     */
    public static boolean hasUnlockedPaths() {
        return !pathRefCount.isEmpty();
    }

    /**
     * Get count of paths that are currently unlocked.
     *
     * @return number of paths with active unlock references
     */
    public static int getUnlockedPathCount() {
        return pathRefCount.size();
    }

    /**
     * Force lock all paths that are currently unlocked, regardless of reference
     * count.
     * Should be called during application shutdown to ensure all paths are secured.
     * Logs warning for each path that had non-zero reference count.
     */
    public static void lockAllOnShutdown() {
        log.info("Shutdown: Locking all unlocked paths (count={})", pathRefCount.size());

        for (Map.Entry<Path, Integer> entry : pathRefCount.entrySet()) {
            Path path = entry.getKey();
            int refCount = entry.getValue();

            if (refCount > 0) {
                log.warn("Path still unlocked at shutdown (refCount={}): {}", refCount, path);
            }

            try {
                forceLockPath(path);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Failed to lock path during shutdown (interrupted): {}", path, e);
            } catch (Exception e) {
                log.error("Failed to lock path during shutdown: {}", path, e);
            }
        }

        pathRefCount.clear();
        log.info("Shutdown: All paths locked");
    }

    /**
     * Unlock target file for reading/writing. Only unlocks the target file, not the
     * entire chain.
     * Ancestor folders remain read-only.
     *
     * @param filePath absolute path to the file to unlock
     * @throws IOException if path is not in a protected bdma folder or does not
     *                     exist
     */
    public static void unlockTarget(String filePath) throws IOException {
        Path bdmaFolder = findDeepestBdmaFolder(filePath);
        if (bdmaFolder == null) {
            throw new IOException("Path is not in a protected bdma folder: " + filePath);
        }

        Path target = Path.of(filePath);

        // Only unlock the target file itself
        if (Files.exists(target)) {
            unlockSinglePath(target);
        } else {
            throw new IOException("Path does not exist: " + filePath);
        }
    }

    /**
     * Lock target file only. Fully locks the target file (deny all access).
     * Does not lock ancestor folders - they should already be read-only.
     *
     * @param filePath absolute path to the file to lock
     * @throws IOException if path is not in a protected bdma folder or does not
     *                     exist
     */
    public static void lockTarget(String filePath) throws IOException {
        Path bdmaFolder = findDeepestBdmaFolder(filePath);
        if (bdmaFolder == null) {
            throw new IOException("Path is not in a protected bdma folder: " + filePath);
        }

        Path target = Path.of(filePath);

        if (!Files.exists(target)) {
            throw new IOException("Path does not exist: " + filePath);
        }

        // Fully lock the target file only
        lockSinglePath(target);
    }

    /**
     * Ensure directory path is unlocked for writing, create missing folders if
     * needed.
     *
     * @param dirPath absolute path to the directory to unlock
     * @throws IOException if path is not in a protected bdma folder
     */
    public static void ensureUnlockedDir(String dirPath) throws IOException {
        Path bdmaFolder = findDeepestBdmaFolder(dirPath);
        if (bdmaFolder == null) {
            throw new IOException("Path is not in a protected bdma folder: " + dirPath);
        }

        Path target = Path.of(dirPath);
        List<Path> pathChain = collectPathChain(target, bdmaFolder);

        // Unlock outside-in (bdmaFolder → target), create missing folders
        Collections.reverse(pathChain);
        for (Path path : pathChain) {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            unlockSinglePath(path);
        }
    }

    /**
     * Ensure path is locked with optimized strategy. Silently handles non-existent
     * paths.
     * Locks ancestor folders with read-only permission and fully locks the target.
     *
     * @param filePath absolute path to the file or directory to lock
     * @throws IOException if locking operations fail
     */
    public static void ensureLocked(String filePath) throws IOException {
        Path bdmaFolder = findDeepestBdmaFolder(filePath);
        if (bdmaFolder == null || !Files.exists(bdmaFolder)) {
            return;
        }

        Path target = Path.of(filePath);

        if (!Files.exists(target)) {
            return;
        }

        // Lock inside-out (target → bdmaFolder)
        // Fully lock target
        if (Files.isDirectory(target)) {
            lockFolderReadOnly(target);
        } else {
            lockSinglePath(target);
        }

        // Lock ancestor folders with read-only
        List<Path> ancestors = collectPathChain(target.getParent(), bdmaFolder);
        for (Path ancestor : ancestors) {
            if (Files.exists(ancestor)) {
                lockFolderReadOnly(ancestor);
            }
        }
    }

    // ── Internal Implementation ──────────────────────────────────────────────

    // ── Helper Methods ───────────────────────────────────────────────────────

    /**
     * Collect all paths from target up to bdmaFolder (inclusive).
     *
     * @param target     the starting path
     * @param bdmaFolder the root bdma folder to stop at
     * @return list of paths from target to bdmaFolder: [target, parent, ...,
     *         bdmaFolder]
     */
    private static List<Path> collectPathChain(Path target, Path bdmaFolder) {
        List<Path> paths = new ArrayList<>();
        Path current = target;

        while (current != null && current.startsWith(bdmaFolder)) {
            paths.add(current);
            if (current.equals(bdmaFolder)) {
                break;
            }
            current = current.getParent();
        }

        return paths; // [target, parent, ..., bdmaFolder]
    }

    /**
     * Unlock a single path using reference counting.
     * First unlock removes deny rules and grants full permissions.
     *
     * @param path the path to unlock
     * @throws IOException if unlock operations fail
     */
    private static void unlockSinglePath(Path path) throws IOException {
        ReentrantLock lock = pathLocks.computeIfAbsent(path, k -> new ReentrantLock());
        lock.lock();
        try {
            int count = pathRefCount.getOrDefault(path, 0);
            if (count == 0) {
                // First unlock - remove deny rules and grant full permissions
                try {
                    String user = System.getProperty(PROPERTY_USER_NAME);
                    runCommand(CMD_ATTRIB, "-h", "-s", path.toString());
                    runCommand(CMD_ICACLS, path.toString(), "/remove:d", user);
                    runCommand(CMD_ICACLS, path.toString(), ICACLS_GRANT, user + ":(R,W)");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Unlock interrupted for: " + path, e);
                }
            }
            pathRefCount.put(path, count + 1);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Lock a single path using reference counting.
     * Last lock applies hidden/system attributes and denies all access.
     * Handles newly created files that were never unlocked.
     *
     * @param path the path to lock
     * @throws IOException if lock operations fail
     */
    private static void lockSinglePath(Path path) throws IOException {
        ReentrantLock lock = pathLocks.computeIfAbsent(path, k -> new ReentrantLock());
        lock.lock();
        try {
            int count = pathRefCount.getOrDefault(path, 0);
            if (count <= 1) {
                // First lock for new file (count=0) or last lock (count=1)
                try {
                    String user = System.getProperty(PROPERTY_USER_NAME);
                    runCommand(CMD_ATTRIB, "+h", "+s", path.toString());
                    runCommand(CMD_ICACLS, path.toString(), ICACLS_DENY, user + ":(R,W,D,X)");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Lock interrupted for: " + path, e);
                }
                pathRefCount.remove(path);
            } else {
                pathRefCount.put(path, count - 1);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Lock folder with read-only permission (prevents write/delete but allows
     * file.exists() check).
     * Uses reference counting to avoid redundant operations.
     *
     * @param path the folder path to lock as read-only
     * @throws IOException if lock operations fail
     */
    private static void lockFolderReadOnly(Path path) throws IOException {
        ReentrantLock lock = pathLocks.computeIfAbsent(path, k -> new ReentrantLock());
        lock.lock();
        try {
            int count = pathRefCount.getOrDefault(path, 0);
            if (count <= 1) {
                // First lock for new folder (count=0) or last lock (count=1)
                try {
                    String user = System.getProperty(PROPERTY_USER_NAME);
                    runCommand(CMD_ATTRIB, "+h", "+s", path.toString());
                    runCommand(CMD_ICACLS, path.toString(), ICACLS_DENY, user + ":(D,W,X)");
                    runCommand(CMD_ICACLS, path.toString(), ICACLS_GRANT, user + ":(R)");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Lock interrupted for: " + path, e);
                }
                pathRefCount.remove(path);
            } else {
                pathRefCount.put(path, count - 1);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Force lock a path without reference counting, used during shutdown.
     * Applies full lock (deny all access) for files, read-only lock for
     * directories.
     *
     * @param path the path to force lock
     * @throws IOException          if lock operations fail
     * @throws InterruptedException if lock is interrupted
     */
    private static void forceLockPath(Path path) throws IOException, InterruptedException {
        String user = System.getProperty(PROPERTY_USER_NAME);
        runCommand(CMD_ATTRIB, "+h", "+s", path.toString());

        if (Files.isDirectory(path)) {
            // Read-only lock for directories
            runCommand(CMD_ICACLS, path.toString(), ICACLS_DENY, user + ":(D,W,X)");
            runCommand(CMD_ICACLS, path.toString(), ICACLS_GRANT, user + ":(R)");
        } else {
            // Full lock for files
            runCommand(CMD_ICACLS, path.toString(), ICACLS_DENY, user + ":(R,W,D,X)");
        }
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
        } else if (!output.isEmpty()) {
            log.debug("[cmd] {}", output);
        }

        return code;
    }
}
