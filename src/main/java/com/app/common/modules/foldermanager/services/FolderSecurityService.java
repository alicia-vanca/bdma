package com.app.common.modules.foldermanager.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.app.common.definitions.enums.FolderType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class FolderSecurityService {

    private static final Logger log = LoggerFactory.getLogger(FolderSecurityService.class);

    private static final String MSG_LOCK_INTERRUPTED = "Lock interrupted";

    private static final String MSG_LOCK_FAILED = "Lock failed";

    private final Path parentPath;
    private final String unlockedFolderName;
    private final Path unlockedPath;
    private final Path lockedPath;
    private final boolean protectionEnabled;

    public FolderSecurityService(String folderPath, FolderType folderType) {
        this(folderPath, folderType, true);
    }

    public FolderSecurityService(String folderPath, FolderType folderType, boolean protectionEnabled) {
        this.unlockedPath = Path.of(folderPath);
        Path parent = unlockedPath.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("folderPath must have parent: " + folderPath);
        }
        this.parentPath = parent;
        this.unlockedFolderName = unlockedPath.getFileName().toString();
        this.lockedPath = parentPath.resolve(folderType.getLockedName());
        this.protectionEnabled = protectionEnabled;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public synchronized void ensureExists() {
        if (unlockedPath.toFile().exists() || lockedPath.toFile().exists())
            return;

        try {
            Files.createDirectories(unlockedPath);
            log.info("Data folder created: {}", unlockedPath);
        } catch (Exception e) {
            log.error("Failed to create data folder: {}", unlockedPath, e);
        }
    }

    public synchronized void ensureUnlocked() {
        if (isLocked())
            unlock();
    }

    public synchronized void ensureLocked() {
        if (!protectionEnabled) {
            ensureUnlocked();
            return;
        }

        if (!isLocked()) {
            lock();
            return;
        }

        try {
            unlockLockedPathForMaintenance();
            reconcileDuplicateUnlockedFolder();
            applyLockedAttributes();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(MSG_LOCK_INTERRUPTED, e);
        } catch (IOException e) {
            log.error(MSG_LOCK_FAILED, e);
        }
    }

    public synchronized boolean isLocked() {
        return lockedPath.toFile().exists();
    }

    // ── Lock / Unlock ────────────────────────────────────────────────────────

    public synchronized void lock() {
        if (!protectionEnabled) {
            ensureExists();
            if (lockedPath.toFile().exists()) {
                unlock();
            }
            return;
        }

        if (lockedPath.toFile().exists()) {
            try {
                unlockLockedPathForMaintenance();
                reconcileDuplicateUnlockedFolder();
                applyLockedAttributes();
                log.debug("Already locked");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error(MSG_LOCK_INTERRUPTED, e);
            } catch (IOException e) {
                log.error(MSG_LOCK_FAILED, e);
            }
            return;
        }

        if (!unlockedPath.toFile().exists()) {
            log.warn("Nothing to lock — data folder missing");
            return;
        }

        try {
            int renameCode = runCommand("cmd", "/c", "ren", unlockedFolderName, lockedPath.getFileName().toString());
            if (renameCode != 0 || !lockedPath.toFile().exists()) {
                log.error("Lock failed — rename exit code: {}", renameCode);
                return;
            }

            applyLockedAttributes();
            log.info("Data folder locked: {}", lockedPath);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(MSG_LOCK_INTERRUPTED, e);
        } catch (IOException e) {
            log.error(MSG_LOCK_FAILED, e);
        }
    }

    public synchronized void unlock() {
        if (!lockedPath.toFile().exists()) {
            log.debug("Already unlocked");
            return;
        }

        try {
            unlockLockedPathForMaintenance();
            reconcileDuplicateUnlockedFolder();

            int renameCode = runCommand("cmd", "/c", "ren", lockedPath.getFileName().toString(), unlockedFolderName);
            if (renameCode != 0 || !unlockedPath.toFile().exists()) {
                log.error("Unlock failed — rename exit code: {}", renameCode);
                return;
            }

            log.info("Data folder unlocked: {}", unlockedPath);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Unlock interrupted", e);
        } catch (IOException e) {
            log.error("Unlock failed", e);
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void unlockLockedPathForMaintenance() throws IOException, InterruptedException {
        runCommand("cmd", "/c", "attrib", "-h", "-s", lockedPath.toString());
        removeDeleteProtection(lockedPath.toString());
    }

    private void applyLockedAttributes() throws IOException, InterruptedException {
        runCommand("cmd", "/c", "attrib", "+h", "+s", lockedPath.toString());
        applyDeleteProtection(lockedPath.toString());
    }

    private void reconcileDuplicateUnlockedFolder() throws IOException {
        if (!Files.exists(unlockedPath) || !Files.exists(lockedPath)) {
            return;
        }

        log.warn("Detected duplicate storage folders, merging {} into {}", unlockedPath, lockedPath);
        mergeDirectory(unlockedPath, lockedPath);
        deleteDirectory(unlockedPath);
    }

    private void mergeDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }

        Files.createDirectories(target);

        try (var entries = Files.list(source)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Path targetEntry = target.resolve(entry.getFileName().toString());
                if (Files.isDirectory(entry)) {
                    mergeDirectory(entry, targetEntry);
                } else {
                    Path parent = targetEntry.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.move(entry, targetEntry, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }

        try (var entries = Files.list(dir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (Files.isDirectory(entry)) {
                    deleteDirectory(entry);
                } else {
                    Files.deleteIfExists(entry);
                }
            }
        }

        Files.deleteIfExists(dir);
    }

    private void applyDeleteProtection(String path) {
        try {
            String user = System.getProperty("user.name");
            int code = runCommand("icacls", path, "/deny", user + ":(D)");
            if (code == 0) {
                log.debug("Delete protection applied");
            } else {
                log.warn("Delete protection failed, exit code: {}", code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to apply delete protection", e);
        } catch (IOException e) {
            log.error("Failed to apply delete protection", e);
        }
    }

    private void removeDeleteProtection(String path) {
        try {
            String user = System.getProperty("user.name");
            int code = runCommand("icacls", path, "/remove:d", user);
            if (code == 0) {
                log.debug("Delete protection removed");
            } else {
                log.warn("Remove delete protection failed, exit code: {}", code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to remove delete protection", e);
        } catch (IOException e) {
            log.error("Failed to remove delete protection", e);
        }
    }

    private int runCommand(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(parentPath.toFile())
                .redirectErrorStream(true)
                .start();

        StringBuilder sb = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
        }
        String output = sb.toString().trim();
        int code = process.waitFor();

        if (!output.isEmpty()) {
            log.debug("[cmd] {}", output);
        }

        return code;
    }
}
