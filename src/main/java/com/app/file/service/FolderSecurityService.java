package com.app.file.service;

import com.app.common.enums.FolderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FolderSecurityService {

    private static final Logger log = LoggerFactory.getLogger(FolderSecurityService.class);

    private final Path parentPath;
    private final String unlockedFolderName;
    private final Path unlockedPath;
    private final Path lockedPath;

    public FolderSecurityService(String folderPath, FolderType folderType) {
        this.unlockedPath = Path.of(folderPath);
        Path parent = unlockedPath.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("folderPath must have parent: " + folderPath);
        }
        this.parentPath = parent;
        this.unlockedFolderName = unlockedPath.getFileName().toString();
        this.lockedPath = parentPath.resolve(folderType.getLockedName());
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void ensureExists() {
        if (unlockedPath.toFile().exists() || lockedPath.toFile().exists()) return;

        try {
            Files.createDirectories(unlockedPath);
            log.info("Data folder created: {}", unlockedPath);
        } catch (Exception e) {
            log.error("Failed to create data folder: {}", unlockedPath, e);
        }
    }

    public void ensureUnlocked() {
        if (isLocked()) unlock();
    }

    public void ensureLocked() {
        if (!isLocked()) lock();
    }

    public boolean isLocked() {
        return lockedPath.toFile().exists();
    }

    // ── Lock / Unlock ────────────────────────────────────────────────────────

    public void lock() {
        if (lockedPath.toFile().exists()) {
            log.debug("Already locked");
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

            runCommand("cmd", "/c", "attrib", "+h", "+s", lockedPath.toString());
            applyDeleteProtection(lockedPath.toString());
            log.info("Data folder locked: {}", lockedPath);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Lock interrupted", e);
        } catch (IOException e) {
            log.error("Lock failed", e);
        }
    }

    public void unlock() {
        if (!lockedPath.toFile().exists()) {
            log.debug("Already unlocked");
            return;
        }

        try {
            runCommand("cmd", "/c", "attrib", "-h", "-s", lockedPath.toString());
            removeDeleteProtection(lockedPath.toString());

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

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int code = process.waitFor();

        if (!output.isEmpty()) {
            log.debug("[cmd] {}", output);
        }

        return code;
    }
}