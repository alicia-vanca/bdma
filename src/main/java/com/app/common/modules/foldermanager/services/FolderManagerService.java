package com.app.common.modules.foldermanager.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;

import com.app.common.services.DriveResolverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.AppDataPaths;
import com.app.common.definitions.enums.FolderType;
import com.app.common.services.AppConfigService;

import lombok.Getter;

/**
 * Manages a protected data folder and a temporary working folder.
 * Typical lifecycle:
 * init() -> ensure data folder exists and is locked, then clear temp.
 * openFileToTemp() -> unlock data, copy file to temp, lock data again.
 * saveFileFromTemp() -> unlock data, copy file from temp to data, lock data
 * again.
 * shutdown() -> lock data and clear temp before application exit.
 */
@Service
public class FolderManagerService {

    private static final Logger log = LoggerFactory.getLogger(FolderManagerService.class);

    @Getter
    private File dataDir;
    @Getter
    private File backupDir;
    @Getter
    private final File tempDir;

    private FolderSecurityService dataSecurity;
    private FolderSecurityService backupSecurity;

    private final AppConfigService appConfigService;
    private final boolean storageProtectionEnabled;
    private final DriveResolverService driveResolverService;

    public FolderManagerService(AppConfigService appConfigService,
            DriveResolverService driveResolverService,
            @Value("${app.storage.protection.enabled:true}") boolean storageProtectionEnabled) {
        this.appConfigService = appConfigService;
        this.driveResolverService = driveResolverService;
        this.storageProtectionEnabled = storageProtectionEnabled;
        this.tempDir = new File(AppDataPaths.appTmpDir());
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void init() {
        String dataDirPath = appConfigService.getConfigValue(AppConstants.KEY_DATA_DIR);
        if (dataDirPath == null || dataDirPath.isBlank()) {
            dataDirPath = Path.of("C:", "BDMA_User_Data", "DataSave").toString();
            appConfigService.saveConfigValue(AppConstants.KEY_DATA_DIR, dataDirPath);
            log.info("Initialized default save folder: {}", dataDirPath);
        }

        String backupDirPath = appConfigService.getConfigValue(AppConstants.KEY_BACKUP_DIR);
        if (backupDirPath == null || backupDirPath.isBlank()) {
            backupDirPath = Path.of("C:", "BDMA_User_Data", "DataBackup").toString();
            appConfigService.saveConfigValue(AppConstants.KEY_BACKUP_DIR, backupDirPath);
            log.info("Initialized default backup folder: {}", backupDirPath);
        }

        initDataDir(dataDirPath);
        initBackupDir(backupDirPath);
        clearTemp();
    }

    public void shutdown() {
        if (dataSecurity != null)
            dataSecurity.ensureLocked();
        if (backupSecurity != null)
            backupSecurity.ensureLocked();
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

        Path targetPath = Path.of(absolutePath).toAbsolutePath().normalize();

        // Find the deepest "data_bdma" folder in the path
        // Ex: D:\BDMA_User_Dataxx\data_bdma\data_bdma\DataSave\data_bdma\
        Path deepestDataFolder = null;
        Path current = targetPath;
        while (current != null) {
            if (current.getFileName() != null &&
                    current.getFileName().toString().equals(AppConstants.DATA_FOLDER_NAME)) {
                deepestDataFolder = current;
                break;
            }
            current = current.getParent();
        }

        if (deepestDataFolder == null) {
            throw new IOException("Path is outside data directory: " + absolutePath);
        }

        // Get relative path from the deepest data_bdma folder
        return deepestDataFolder.relativize(targetPath).toString();
    }

    public String getBackupPath(String relativePath) throws IOException {
        if (backupDir == null) {
            throw new IOException("Backup directory is not configured yet.");
        }
        return new File(backupDir, relativePath).getAbsolutePath();
    }

    // Unlocks the data folder, runs the action, then re-locks in finally.
    // If the thread is interrupted while locking, the folder is locked best-effort
    // and the interrupted flag is restored so callers can detect shutdown.
    public synchronized <T> T withDataDirUnlocked(Callable<T> action) throws Exception {
        ensureDataReady();
        return withSpecificDirUnlocked(dataDir, action);
    }

    // Unlocks a specific data directory, runs the action, then re-locks.
    // Used during sync to ensure operations use the captured saveDir.
    public synchronized <T> T withSpecificDirUnlocked(File specificDir, Callable<T> action) throws Exception {
        if (specificDir == null) {
            throw new IOException("Specific directory is null");
        }

        // If the specific dir matches current dataDir, use the existing security
        // service
        FolderSecurityService security;
        if (dataDir != null && specificDir.getAbsolutePath().equals(dataDir.getAbsolutePath())) {
            security = dataSecurity;
        } else {
            // Create a temporary security service for this specific directory
            security = new FolderSecurityService(
                    specificDir.getAbsolutePath(),
                    FolderType.SAVE,
                    storageProtectionEnabled);
        }

        security.ensureUnlocked();
        try {
            ensureDir(specificDir);
            return action.call();
        } finally {
            boolean wasInterrupted = Thread.interrupted();
            try {
                security.ensureLocked();
            } finally {
                if (wasInterrupted) {
                    Thread.currentThread().interrupt();
                }
            }
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
            boolean wasInterrupted = Thread.interrupted();
            try {
                dataSecurity.ensureLocked();
                backupSecurity.ensureLocked();
            } finally {
                if (wasInterrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void initDataDir(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank())
            return;

        this.dataDir = new File(configuredPath, AppConstants.DATA_FOLDER_NAME);
        this.dataSecurity = new FolderSecurityService(
                dataDir.getAbsolutePath(),
                FolderType.SAVE,
                storageProtectionEnabled);
        dataSecurity.ensureExists();
        if (storageProtectionEnabled) {
            dataSecurity.ensureLocked();
        } else {
            dataSecurity.ensureUnlocked();
        }
        log.info("DataDir initialized: {} (protectionEnabled={})",
                dataDir.getAbsolutePath(), storageProtectionEnabled);
    }

    private void initBackupDir(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank())
            return;

        this.backupDir = new File(configuredPath, AppConstants.BACKUP_FOLDER_NAME);
        this.backupSecurity = new FolderSecurityService(
                backupDir.getAbsolutePath(),
                FolderType.BACKUP,
                storageProtectionEnabled);
        backupSecurity.ensureExists();
        if (storageProtectionEnabled) {
            backupSecurity.ensureLocked();
        } else {
            backupSecurity.ensureUnlocked();
        }
        log.info("BackupDir initialized: {} (protectionEnabled={})",
                backupDir.getAbsolutePath(), storageProtectionEnabled);
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
        if (!tempDir.exists())
            return;
        try {
            deleteDirectory(tempDir);
            log.debug("Temp cleared");
        } catch (Exception e) {
            log.warn("Failed to clear temp: {}", e.getMessage());
        }
    }

    private void deleteDirectory(File dir) {
        if (!dir.exists())
            return;

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
        if (backupDir == null) {
            throw new IOException("Backup directory not configured");
        }

        try {
            withDataAndBackupUnlocked(() -> {
                if (!backupDir.exists()) {
                    throw new IOException("Backup directory not found after unlock: "
                            + backupDir.getAbsolutePath());
                }

                Path source = driveResolverService.resolve(fileName)
                        .orElseThrow(() -> new IOException("Source file not found on any drive: " + fileName));

                String relativeFromData = toRelativeDataPath(source.toString());

                Path target = backupDir.toPath().resolve(relativeFromData);
                ensureParentDir(target.toFile());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("Backed up to backup dir: {}", relativeFromData);
                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to back up file: " + fileName, e);
        }
    }

    public String stripDriveLetter(String absolutePath) {
        Path path = Path.of(absolutePath);
        Path root = path.getRoot();
        return root != null ? root.relativize(path).toString() : absolutePath;
    }
}
