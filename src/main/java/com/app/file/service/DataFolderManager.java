package com.app.file.service;

import com.app.common.config.AppPaths;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Manages a protected data folder and a temporary working folder.
 * Typical lifecycle:
 * init() -> ensure data folder exists and is locked, then clear temp.
 * openFileToTemp() -> unlock data, copy file to temp, lock data again.
 * saveFileFromTemp() -> unlock data, copy file from temp to data, lock data again.
 * shutdown() -> lock data and clear temp before application exit.
 */
@Service
public class DataFolderManager {

    private static final Logger log = LoggerFactory.getLogger(DataFolderManager.class);

    @Getter
    private final File dataDir;
    @Getter
    private final File tempDir;
    private final FolderSecurityService security;

    public DataFolderManager() {
        this.dataDir = new File(AppPaths.appDir() + "/data");
        this.tempDir = new File(AppPaths.tmpDir());
        this.security = new FolderSecurityService(dataDir.getAbsolutePath());
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void init() {
        security.ensureExists();
        security.ensureLocked();
        clearTemp();
        log.info("DataFolderManager initialized, data={}", dataDir.getAbsolutePath());
    }

    public void shutdown() {
        security.ensureLocked();
        clearTemp();
        log.info("DataFolderManager shut down");
    }

    // ── File operations ──────────────────────────────────────────────────────

    public File openFileToTemp(String fileName) throws IOException {
        ensureDir(tempDir);

        File dest = new File(tempDir, fileName);

        security.ensureUnlocked();
        try {
            Files.copy(new File(dataDir, fileName).toPath(), dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            security.ensureLocked();
        }

        log.info("Opened {} to temp", fileName);
        return dest;
    }

    public void saveFileFromTemp(String fileName) throws IOException {
        security.ensureUnlocked();
        try {
            Files.copy(new File(tempDir, fileName).toPath(), new File(dataDir, fileName).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            security.ensureLocked();
        }

        log.info("Saved {} from temp to data", fileName);
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public File getTempFile(String fileName) {
        return new File(tempDir, fileName);
    }

    public boolean isDataLocked() {
        return security.isLocked();
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("Failed to create directory: {}", dir.getAbsolutePath());
        }
    }

    private void clearTemp() {
        if (!tempDir.exists()) return;
        try {
            deleteDirectory(tempDir);
            log.debug("Temp cleared");
        } catch (Exception e) {
            log.warn("Failed to clear temp: {}", e.getMessage());
        }
    }

    private void deleteDirectory(File dir) {
        if (!dir.exists()) return;

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    try {
                        Files.delete(f.toPath());
                    } catch (IOException e) {
                        log.warn("Failed to delete file: {}", f.getAbsolutePath());
                    }
                }
            }
        }

        try {
            Files.delete(dir.toPath());
        } catch (IOException e) {
            log.warn("Failed to delete directory: {}", dir.getAbsolutePath());
        }
    }
}