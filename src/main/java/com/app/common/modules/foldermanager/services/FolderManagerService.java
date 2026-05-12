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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.AppDataPaths;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
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
    private static final String DEFAULT_ROOT_FOLDER = "BDMA_User_Data";

    @Getter
    private final File tempDir;

    private final AppConfigService appConfigService;
    private final ApplicationEventPublisher publisher;
    private final boolean storageProtectionEnabled;

    public FolderManagerService(AppConfigService appConfigService,
            ApplicationEventPublisher publisher,
            @Value("${app.storage.protection.enabled:true}") boolean storageProtectionEnabled) {
        this.appConfigService = appConfigService;
        this.publisher = publisher;
        this.storageProtectionEnabled = storageProtectionEnabled;
        this.tempDir = new File(AppDataPaths.appTmpDir());
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void init() {
        init(null);
    }

    public void init(FolderType target) {
        if (target == null || target == FolderType.SYNC) {
            String dataDirPath = appConfigService.getConfigValue(AppConstants.KEY_DATA_DIR);
            if (dataDirPath == null || dataDirPath.isBlank()) {
                dataDirPath = Path.of("C:", DEFAULT_ROOT_FOLDER, "DataSave").toString();
                appConfigService.saveConfigValue(AppConstants.KEY_DATA_DIR, dataDirPath);
                log.info("Initialized default save folder: {}", dataDirPath);
            }
            initDataDir(dataDirPath);
        }
        if (target == null || target == FolderType.BACKUP) {
            String backupDirPath = appConfigService.getConfigValue(AppConstants.KEY_BACKUP_DIR);
            if (backupDirPath == null || backupDirPath.isBlank()) {
                backupDirPath = Path.of("C:", DEFAULT_ROOT_FOLDER, "DataBackup").toString();
                appConfigService.saveConfigValue(AppConstants.KEY_BACKUP_DIR, backupDirPath);
                log.info("Initialized default backup folder: {}", backupDirPath);
            }
            initBackupDir(backupDirPath);
        }
        clearTemp();
    }

    public void shutdown() {
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
        return new File(dataDirPath, AppConstants.SYNC_FOLDER_NAME);
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

    public boolean isDataDirAccessible() {
        return isDirAccessible(getDataDir());
    }

    public boolean isBackupDirAccessible() {
        return isDirAccessible(getBackupDir());
    }

    public boolean isDataDirAccessible(File dataDirPath) {
        return isDirAccessible(dataDirPath);
    }

    public boolean isBackupDirAccessible(File backupDirPath) {
        return isDirAccessible(backupDirPath);
    }

    /**
     * Check if directory is accessible by verifying its drive is available.
     * Use this before attempting any folder operations to prevent
     * NoSuchFileException.
     */
    public boolean isDirAccessible(File dir) {
        return isDriveAccessible(dir);
    }

    public boolean isDriveAccessible(File dir) {
        if (dir == null) {
            return false;
        }

        Path root = Path.of(dir.getAbsolutePath()).getRoot();
        if (root == null) {
            return false;
        }

        File rootFile = root.toFile();
        return rootFile.exists();
    }

    public boolean hasSufficientSpace(File dir, long requiredBytes) {
        if (requiredBytes <= 0) {
            return true;
        }

        File target = dir;
        if (target == null) {
            return false;
        }

        Path root = Path.of(target.getAbsolutePath()).getRoot();
        if (root == null) {
            return false;
        }

        File rootFile = root.toFile();
        if (!rootFile.exists()) {
            return false;
        }

        return rootFile.getUsableSpace() >= requiredBytes;
    }

    public String toRelativeDataPath(String absolutePath) throws IOException {

        Path path = Path.of(absolutePath);

        // Find the deepest "data_bdma" folder in the path
        // Ex: D:\BDMA_User_Dataxx\data_bdma\data_bdma\DataSave\data_bdma\
        Path deepestDataFolder = null;
        Path current = path;
        while (current != null) {
            if (current.getFileName() != null &&
                    current.getFileName().toString().equals(AppConstants.SYNC_FOLDER_NAME)) {
                deepestDataFolder = current;
                break;
            }
            current = current.getParent();
        }

        if (deepestDataFolder == null) {
            throw new IOException("Path does not contain " + AppConstants.SYNC_FOLDER_NAME + ": " + absolutePath);
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

    // Ensures the target directory exists and is protected, then runs the action.
    public synchronized <T> T withSpecificDirPrepared(File specificDir, Callable<T> action) throws Exception {
        if (specificDir == null) {
            throw new IOException("Directory path is null");
        }

        // Fail fast if drive is not accessible
        if (!isDriveAccessible(specificDir)) {
            throw new IOException("Drive not accessible: " + specificDir.getAbsolutePath());
        }

        ensureBdmaDirState(specificDir.getAbsolutePath());

        return action.call();
    }

    public synchronized <T> T withDataAndBackupPrepared(Callable<T> action) throws Exception {
        File currentDataDir = getDataDir();
        if (currentDataDir == null) {
            throw new IOException("Data directory is not configured yet.");
        }
        File currentBackupDir = getBackupDir();
        if (currentBackupDir == null) {
            throw new IOException("Backup directory is not configured yet.");
        }

        // Fail fast if drives are not accessible
        if (!isDriveAccessible(currentDataDir)) {
            throw new IOException("Data directory drive not accessible: " + currentDataDir.getAbsolutePath());
        }
        if (!isDriveAccessible(currentBackupDir)) {
            throw new IOException("Backup directory drive not accessible: " + currentBackupDir.getAbsolutePath());
        }

        String dataPath = currentDataDir.getAbsolutePath();
        String backupPath = currentBackupDir.getAbsolutePath();

        ensureBdmaDirState(dataPath);
        ensureBdmaDirState(backupPath);
        return action.call();
    }

    // ── Path Resolution ──────────────────────────────────────────────────────

    /**
     * Find absolute path from a non-drive-letter path by checking all available
     * drives.
     * Returns result that distinguishes between "not found" and other I/O errors.
     */
    public PathResolutionResult findAbsolutePathFromNonDriveLetterPath(String path, long expectedSize) {
        for (File root : File.listRoots()) {
            File candidate = new File(root, path);
            if (candidate.exists() && candidate.length() == expectedSize) {
                return PathResolutionResult.found(candidate.toPath());
            }
        }
        return PathResolutionResult.notFound();
    }

    public long resolveExistingFileSize(String nonDriveLetterPath) throws IOException {
        Path resolved = resolvePathAcrossDrives(nonDriveLetterPath);
        return Files.size(resolved);
    }

    /**
     * Search across all drives to find a file by non-drive-letter path.
     */
    private Path resolvePathAcrossDrives(String nonDriverLetterPath) throws IOException {
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            Path candidatePath = root.resolve(nonDriverLetterPath);
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

        File dataDir = new File(configuredPath, AppConstants.SYNC_FOLDER_NAME);

        // Check drive accessibility before attempting folder operations
        if (!isDriveAccessible(dataDir)) {
            log.warn("DataDir drive not accessible during initialization: {}", dataDir.getAbsolutePath());
            log.debug("FolderManagerService.initDataDir firing StorageUnavailableEvent: target={} reason={}",
                    FolderType.SYNC, StorageIssueReason.DRIVE_UNAVAILABLE);
            publisher.publishEvent(
                    new StorageUnavailableEvent(FolderType.SYNC, StorageIssueReason.DRIVE_UNAVAILABLE));
            return;
        }

        try {
            ensureBdmaDirState(dataDir.getAbsolutePath());
            log.info("DataDir initialized: {} (protectionEnabled={})",
                    dataDir.getAbsolutePath(), storageProtectionEnabled);
        } catch (IOException e) {
            log.error("Failed to initialize data directory", e);
        }
    }

    private void initBackupDir(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank())
            return;

        File backupDir = new File(configuredPath, AppConstants.BACKUP_FOLDER_NAME);

        // Check drive accessibility before attempting folder operations
        if (!isDriveAccessible(backupDir)) {
            log.warn("BackupDir drive not accessible during initialization: {}", backupDir.getAbsolutePath());
            log.debug("FolderManagerService.initBackupDir firing StorageUnavailableEvent: target={} reason={}",
                    FolderType.BACKUP, StorageIssueReason.DRIVE_UNAVAILABLE);
            publisher.publishEvent(
                    new StorageUnavailableEvent(FolderType.BACKUP, StorageIssueReason.DRIVE_UNAVAILABLE));
            return;
        }

        try {
            ensureBdmaDirState(backupDir.getAbsolutePath());
            log.info("BackupDir initialized: {} (protectionEnabled={})",
                    backupDir.getAbsolutePath(), storageProtectionEnabled);
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

    public String backupFromSave(String nonDriverLetterSyncedPath) throws IOException {
        File currentBackupDir = getBackupDir();
        if (currentBackupDir == null) {
            init(); // Attempt to initialize backup dir if not configured
            currentBackupDir = getBackupDir();
            if (currentBackupDir == null) {
                throw new IOException("Backup directory not configured");
            }
        }

        Path sourcePath;
        // Find the file across drives
        sourcePath = resolvePathAcrossDrives(nonDriverLetterSyncedPath);

        String relativeFromData = toRelativeDataPath(sourcePath.toString());
        Path target = currentBackupDir.toPath().resolve(relativeFromData);
        Path targetParent = target.getParent();

        // Ensure parent directory for writing (creates missing folders)
        if (targetParent != null) {
            ensureBdmaDirState(targetParent.toString());
        }
        Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Backed up to backup dir: {}", relativeFromData);
        return target.toString();
    }

    private void ensureBdmaDirState(String dirPath) throws IOException {
        FolderSecurityService.ensureBdmaDataDir(dirPath, storageProtectionEnabled);
    }
}
