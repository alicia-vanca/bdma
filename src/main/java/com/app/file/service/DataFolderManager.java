package com.app.file.service;

import com.app.common.config.AppConfig;
import com.app.common.config.AppConfigService;
import com.app.common.config.AppPaths;
import com.app.common.enums.FolderType;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;

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
    private File dataDir;
    @Getter
    private File backupDir;
    @Getter
    private final File tempDir;

    private FolderSecurityService dataSecurity;
    private FolderSecurityService backupSecurity;

    private final AppConfigService appConfigService;

    public DataFolderManager(AppConfigService appConfigService) {
        this.appConfigService = appConfigService;
        this.tempDir = new File(AppPaths.appTmpDir());
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void init() {
        AppConfig.Storage storage = appConfigService.getConfig().getStorage();
        initDataDir(storage.getDataDir());
        initBackupDir(storage.getBackupDir());
        clearTemp();
    }

    public void shutdown() {
        if (dataSecurity != null) dataSecurity.ensureLocked();
        if (backupSecurity != null) backupSecurity.ensureLocked();
        clearTemp();
        log.info("DataFolderManager shut down");
    }

    // ── File operations ──────────────────────────────────────────────────────

    public File openFileToTemp(String fileName) throws IOException {
        try {
            return withDataDirUnlocked(() -> {
                ensureDir(tempDir);

                File dest = new File(tempDir, fileName);
                Files.copy(new File(dataDir, fileName).toPath(), dest.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                log.info("Opened {} to temp", fileName);
                return dest;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to open file to temp: " + fileName, e);
        }
    }

    public void saveFileFromTemp(String fileName) throws IOException {
        try {
            withDataDirUnlocked(() -> {
                File target = new File(dataDir, fileName);
                ensureParentDir(target);
                Files.copy(new File(tempDir, fileName).toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                log.info("Saved {} from temp to data", fileName);
                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to save file from temp: " + fileName, e);
        }
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public File getTempFile(String fileName) {
        return new File(tempDir, fileName);
    }

    public boolean isDataLocked() {
        return dataSecurity != null && dataSecurity.isLocked();
    }

    public boolean isDataDirConfigured() {
        return dataDir != null;
    }

    public boolean isBackupDirConfigured() {
        return backupDir != null;
    }

    public String toRelativeDataPath(String absolutePath) throws IOException {
        ensureDataReady();

        Path basePath = dataDir.toPath().toAbsolutePath().normalize();
        Path targetPath = Path.of(absolutePath).toAbsolutePath().normalize();

        if (!targetPath.startsWith(basePath)) {
            throw new IOException("Path is outside data directory: " + absolutePath);
        }

        return basePath.relativize(targetPath).toString();
    }

    public synchronized <T> T withDataDirUnlocked(Callable<T> action) throws Exception {
        ensureDataReady();
        dataSecurity.ensureUnlocked();
        try {
            ensureDir(dataDir);
            return action.call();
        } finally {
            dataSecurity.ensureLocked();
        }
    }

    public synchronized <T> T withDataAndBackupUnlocked(Callable<T> action) throws Exception {
        if (backupDir == null || backupSecurity == null) {
            throw new IOException("Backup directory is not configured yet.");
        }
        ensureDataReady();

        dataSecurity.ensureUnlocked();
        backupSecurity.ensureUnlocked();
        try {
            ensureDir(dataDir);
            ensureDir(backupDir);
            return action.call();
        } finally {
            dataSecurity.ensureLocked();
            backupSecurity.ensureLocked();
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void initDataDir(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) return;

        this.dataDir = new File(configuredPath + "/data_bdma");
        this.dataSecurity = new FolderSecurityService(dataDir.getAbsolutePath(), FolderType.SAVE);
        dataSecurity.ensureExists();
        dataSecurity.ensureLocked();
        log.info("DataDir initialized: {}", dataDir.getAbsolutePath());
    }

    private void initBackupDir(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) return;

        this.backupDir = new File(configuredPath + "/backup_bdma");
        this.backupSecurity = new FolderSecurityService(backupDir.getAbsolutePath(), FolderType.BACKUP);
        backupSecurity.ensureExists();
        backupSecurity.ensureLocked();
        log.info("BackupDir initialized: {}", backupDir.getAbsolutePath());
    }

    private void ensureDataReady() throws IOException {
        if (dataDir == null || dataSecurity == null) {
            throw new IOException("Data directory is not configured yet.");
        }
    }

    private void ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("Failed to create directory: {}", dir.getAbsolutePath());
        }
    }

    private void ensureParentDir(File file) {
        File parent = file.getParentFile();
        if (parent != null) {
            ensureDir(parent);
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

    public void backupFromSave(String fileName) throws IOException {
        try {
            withDataAndBackupUnlocked(() -> {
                File source = new File(dataDir, fileName);
                File target = new File(backupDir, fileName);
                ensureParentDir(target);
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("Backed up {} from save to backup", fileName);
                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to back up file: " + fileName, e);
        }
    }

    public void restoreFromBackup(String fileName) throws IOException {
        try {
            withDataAndBackupUnlocked(() -> {
                File source = new File(backupDir, fileName);
                File target = new File(dataDir, fileName);
                ensureParentDir(target);
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("Restored {} from backup to save", fileName);
                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to restore file: " + fileName, e);
        }
    }
}
