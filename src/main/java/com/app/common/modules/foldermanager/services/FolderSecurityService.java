package com.app.common.modules.foldermanager.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.app.common.definitions.enums.FolderType;
import com.app.common.services.WindowsCommandService;

public class FolderSecurityService {

    private static final Logger log = LoggerFactory.getLogger(FolderSecurityService.class);

    private static final String MSG_LOCK_INTERRUPTED = "Lock interrupted";
    private static final String MSG_LOCK_FAILED = "Lock failed";

    private final Path parentPath;
    private final String unlockedFolderName;
    private final Path unlockedPath;
    private final Path lockedPath;
    private final boolean protectionEnabled;
    private final WindowsCommandService windowsCommandService;

    public FolderSecurityService(String folderPath, FolderType folderType,
                                 boolean protectionEnabled, WindowsCommandService windowsCommandService) {
        this.unlockedPath = Path.of(folderPath);
        Path parent = unlockedPath.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("folderPath must have parent: " + folderPath);
        }
        this.parentPath = parent;
        this.unlockedFolderName = unlockedPath.getFileName().toString();
        this.lockedPath = parentPath.resolve(folderType.getLockedName());
        this.protectionEnabled = protectionEnabled;
        this.windowsCommandService = windowsCommandService;
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
            if (lockedPath.toFile().exists()) unlock();
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
            int renameCode = windowsCommandService.renameFolder(
                    parentPath.toFile(), unlockedFolderName, lockedPath.getFileName().toString());
            if (renameCode != 0 || !lockedPath.toFile().exists()) {
                log.error("Lock failed — rename exit code: {}, from: {}, to: {}",
                        renameCode, unlockedPath, lockedPath);
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

            int renameCode = windowsCommandService.renameFolder(
                    parentPath.toFile(), lockedPath.getFileName().toString(), unlockedFolderName);
            if (renameCode != 0 || !unlockedPath.toFile().exists()) {
                log.error("Unlock failed — rename exit code: {}, from: {}, to: {}",
                        renameCode, lockedPath, unlockedPath);
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
        windowsCommandService.removeHiddenSystem(lockedPath.toString());
        windowsCommandService.removeDeleteProtection(lockedPath.toString());
    }

    private void applyLockedAttributes() throws IOException, InterruptedException {
        windowsCommandService.applyHiddenSystem(lockedPath.toString());
        windowsCommandService.applyDeleteProtection(lockedPath.toString());
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
}