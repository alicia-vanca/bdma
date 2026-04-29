package com.app.common.modules.foldermanager.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.AppDataPaths;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.exceptions.FileNotFoundOnAnyDriveException;
import com.app.common.services.AppConfigService;

import lombok.Getter;

/**
 * Manages a protected data folder and a temporary working folder.
 * Typical lifecycle:
 * init() -> ensure data folder exists and is locked, then clear temp.
 * shutdown() -> lock data and clear temp before application exit.
 */
@Service
public class FolderManagerService {

    private static final Logger log = LoggerFactory.getLogger(FolderManagerService.class);

    @Getter
    private final File tempDir;

    private final AppConfigService appConfigService;

    public FolderManagerService(AppConfigService appConfigService) {
        this.appConfigService = appConfigService;
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
        // Lock all unlocked paths with reference counting check
        FolderSecurityService.lockAllOnShutdown();
        clearTemp();
        log.info("DataFolderManager shut down");
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    // Fetch latest dataDir from database instead of using cached value
    public File getDataDir() {
        String dataDirPath = appConfigService.getConfigValue(AppConstants.KEY_DATA_DIR);
        if (dataDirPath == null || dataDirPath.isBlank()) {
            return null;
        }
        return new File(dataDirPath, AppConstants.DATA_FOLDER_NAME);
    }

    // Fetch latest backupDir from database instead of using cached value
    public File getBackupDir() {
        String backupDirPath = appConfigService.getConfigValue(AppConstants.KEY_BACKUP_DIR);
        if (backupDirPath == null || backupDirPath.isBlank()) {
            return null;
        }
        return new File(backupDirPath, AppConstants.BACKUP_FOLDER_NAME);
    }

    public File getTempFile(String fileName) {
        return new File(tempDir, fileName);
    }

    public boolean isDataDirConfigured() {
        return getDataDir() != null;
    }

    public boolean isBackupDirConfigured() {
        return getBackupDir() != null;
    }

    public String toRelativeDataPath(String absolutePath) throws IOException {

        Path path = Path.of(absolutePath);

        // Find the deepest "data_bdma" folder in the path
        // Ex: D:\BDMA_User_Dataxx\data_bdma\data_bdma\DataSave\data_bdma\
        Path deepestDataFolder = null;
        Path current = path;
        while (current != null) {
            if (current.getFileName() != null &&
                    current.getFileName().toString().equals(AppConstants.DATA_FOLDER_NAME)) {
                deepestDataFolder = current;
                break;
            }
            current = current.getParent();
        }

        if (deepestDataFolder == null) {
            throw new IOException("Path does not contain " + AppConstants.DATA_FOLDER_NAME + ": " + absolutePath);
        }

        // Get relative path from the deepest data_bdma folder
        return deepestDataFolder.relativize(path).toString();
    }

    public String getBackupPath(String relativePath) throws IOException {
        File currentBackupDir = getBackupDir();
        if (currentBackupDir == null) {
            throw new IOException("Backup directory is not configured yet.");
        }
        return new File(currentBackupDir, relativePath).getAbsolutePath();
    }

    // Unlocks a specific directory (ending with a file), runs the action, then
    // re-locks.
    public synchronized <T> T withSpecificDirUnlocked(File specificDir, Callable<T> action) throws Exception {
        if (specificDir == null) {
            throw new IOException("Specific path is null");
        }

        String stringDir = specificDir.getAbsolutePath();
        String parentPath = specificDir.getParent();

        // Unlock parent directory first
        if (parentPath != null) {
            FolderSecurityService.ensureUnlockedDir(parentPath);
        }

        try {
            // Check if the path is an existing file and unlock it
            if (specificDir.exists() && specificDir.isFile()) {
                try {
                    FolderSecurityService.unlockTarget(stringDir);
                } catch (IOException e) {
                    log.warn("Failed to unlock existing file: {} - {}", stringDir, e.getMessage());
                }
            }

            return action.call();
        } finally {
            if (parentPath != null) {
                FolderSecurityService.ensureLocked(parentPath);
            }
        }
    }

    public synchronized <T> T withDataAndBackupUnlocked(Callable<T> action) throws Exception {
        File currentDataDir = getDataDir();
        if (currentDataDir == null) {
            throw new IOException("Data directory is not configured yet.");
        }
        File currentBackupDir = getBackupDir();
        if (currentBackupDir == null) {
            throw new IOException("Backup directory is not configured yet.");
        }

        String dataPath = currentDataDir.getAbsolutePath();
        String backupPath = currentBackupDir.getAbsolutePath();

        FolderSecurityService.ensureUnlockedDir(dataPath);
        FolderSecurityService.ensureUnlockedDir(backupPath);
        try {
            return action.call();
        } finally {
            FolderSecurityService.ensureLocked(dataPath);
            FolderSecurityService.ensureLocked(backupPath);
        }
    }

    // ── Path Resolution ──────────────────────────────────────────────────────

    /**
     * Find absolute path from non-drive-letter synced path by checking all
     * available drives.
     * Handles locked directories by temporarily unlocking them to check existence.
     * Returns result that distinguishes between "not found" and other I/O errors.
     */
    public PathResolutionResult findAbsolutePathFromNonDriveSyncedPath(String nonDriverLetterSyncedPath) {
        try {
            Path resolved = resolvePathAcrossDrives(nonDriverLetterSyncedPath, AppConstants.DATA_FOLDER_NAME);
            return PathResolutionResult.found(resolved);
        } catch (FileNotFoundOnAnyDriveException e) {
            return PathResolutionResult.notFound();
        } catch (IOException e) {
            log.error("Unexpected I/O error resolving path: {}", nonDriverLetterSyncedPath, e);
            return PathResolutionResult.error(e);
        }
    }

    /**
     * Find absolute path from non-drive-letter backed up path by checking all
     * available drives.
     * Handles locked directories by temporarily unlocking them to check existence.
     * Returns result that distinguishes between "not found" and other I/O errors.
     */
    public PathResolutionResult findAbsolutePathFromNonDriveBackedUpPath(String nonDriverLetterBackedUpPath) {
        try {
            Path resolved = resolvePathAcrossDrives(nonDriverLetterBackedUpPath, AppConstants.BACKUP_FOLDER_NAME);
            return PathResolutionResult.found(resolved);
        } catch (FileNotFoundOnAnyDriveException e) {
            return PathResolutionResult.notFound();
        } catch (IOException e) {
            log.error("Unexpected I/O error resolving backup path: {}", nonDriverLetterBackedUpPath, e);
            return PathResolutionResult.error(e);
        }
    }

    // Find the deepest data_bdma folder in a path
    private Path findDeepestDataFolder(Path path) {
        Path current = path;
        while (current != null) {
            if (current.getFileName() != null &&
                    current.getFileName().toString().equals(AppConstants.DATA_FOLDER_NAME)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    // Find the deepest backup_bdma folder in a path
    private Path findDeepestBackupFolder(Path path) {
        Path current = path;
        while (current != null) {
            if (current.getFileName() != null &&
                    current.getFileName().toString().equals(AppConstants.BACKUP_FOLDER_NAME)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Search across all drives to find a file by non-drive-letter path.
     * Uses new FolderSecurityService to unlock/check/lock.
     */
    private Path resolvePathAcrossDrives(String nonDriverLetterPath, String folderName) throws IOException {
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            Path candidatePath = root.resolve(nonDriverLetterPath);

            // Find the deepest target folder in the path
            Path deepestFolder;
            if (folderName.equals(AppConstants.BACKUP_FOLDER_NAME)) {
                deepestFolder = findDeepestBackupFolder(candidatePath);
            } else {
                deepestFolder = findDeepestDataFolder(candidatePath);
            }

            if (deepestFolder == null) {
                continue;
            }

            // Check if file exists
            try {
                if (Files.exists(candidatePath)) {
                    return candidatePath;
                }
            } catch (Exception e) {
                log.warn("Drive {} check failed: {}", root, e.getMessage());
            }
        }

        throw new FileNotFoundOnAnyDriveException(nonDriverLetterPath);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void initDataDir(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank())
            return;

        File dataDir = new File(configuredPath, AppConstants.DATA_FOLDER_NAME);

        try {
            // Ensure directory exists
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }

            // Lock it
            FolderSecurityService.ensureLocked(dataDir.getAbsolutePath());
            log.info("DataDir initialized and locked: {}", dataDir.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to initialize data directory", e);
        }
    }

    private void initBackupDir(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank())
            return;

        File backupDir = new File(configuredPath, AppConstants.BACKUP_FOLDER_NAME);

        try {
            // Ensure directory exists
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }

            // Lock it
            FolderSecurityService.ensureLocked(backupDir.getAbsolutePath());
            log.info("BackupDir initialized and locked: {}", backupDir.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to initialize backup directory", e);
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

    public void backupFromSave(String nonDriverLetterSyncedPath) throws IOException {
        File currentBackupDir = getBackupDir();
        if (currentBackupDir == null) {
            throw new IOException("Backup directory not configured");
        }

        Path sourcePath;
        try {
            // Find the file across drives
            sourcePath = resolvePathAcrossDrives(nonDriverLetterSyncedPath, AppConstants.DATA_FOLDER_NAME);
        } catch (FileNotFoundOnAnyDriveException e) {
            throw new IOException("Source file not found on any drive: " + nonDriverLetterSyncedPath, e);
        }

        // Unlock source for reading
        FolderSecurityService.unlockTarget(sourcePath.toString());
        try {
            String relativeFromData = toRelativeDataPath(sourcePath.toString());
            Path target = currentBackupDir.toPath().resolve(relativeFromData);
            Path targetParent = target.getParent();

            // Unlock parent directory for writing (creates missing folders)
            if (targetParent != null) {
                FolderSecurityService.ensureUnlockedDir(targetParent.toString());
            }
            try {
                Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("Backed up to backup dir: {}", relativeFromData);

                // Lock the copied file
                FolderSecurityService.lockTarget(target.toString());
            } finally {
                if (targetParent != null) {
                    FolderSecurityService.ensureLocked(targetParent.toString());
                }
            }
        } finally {
            FolderSecurityService.lockTarget(sourcePath.toString());
        }
    }

    public String stripDriveLetter(String absolutePath) {
        Path path = Path.of(absolutePath);
        Path root = path.getRoot();
        return root != null ? root.relativize(path).toString() : absolutePath;
    }
}
