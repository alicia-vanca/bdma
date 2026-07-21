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

import com.app.common.configs.AppContext;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.AppDataPaths;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.models.User;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.exceptions.FileNotFoundOnAnyDriveException;
import com.app.common.services.AppConfigService;
import com.app.common.services.UserService;

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
    private static final String DEFAULT_SYNC_PARENT_FOLDER = "DataSync";
    private static final String DEFAULT_BACKUP_PARENT_FOLDER = "DataBackup";
    private static final String STORAGE_PROTECTION_ENABLED_PROPERTY = "app.storage.protection.enabled";
    private static final long TMP_DELETE_RETRY_DELAY_MILLIS = 5_000L;
    private static final int TMP_DELETE_MAX_ATTEMPTS = 3;

    @Getter
    private final File tempDir;

    private final AppConfigService appConfigService;
    private final DefaultStorageLocationService defaultStorageLocationService;
    private final UserService userService;
    private final ApplicationEventPublisher publisher;
    private final boolean storageProtectionEnabled;

    public FolderManagerService(AppConfigService appConfigService,
            DefaultStorageLocationService defaultStorageLocationService,
            UserService userService,
            ApplicationEventPublisher publisher,
            @Value("${app.storage.protection.enabled:true}") boolean storageProtectionEnabled) {
        this.appConfigService = appConfigService;
        this.defaultStorageLocationService = defaultStorageLocationService;
        this.userService = userService;
        this.publisher = publisher;
        this.storageProtectionEnabled = allowDisabledOnlyForDev(storageProtectionEnabled);
        this.tempDir = new File(AppDataPaths.appTmpDir());
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void init() {
        init(null);
    }

    public void init(FolderType target) {
        initializeDefaultStoragePaths(target);
        if (target == null || target == FolderType.SYNC) {
            initSyncDir(appConfigService.getConfigValue(AppConstants.KEY_SYNC_DIR));
        }
        if (target == null || target == FolderType.BACKUP) {
            initBackupDir(appConfigService.getConfigValue(AppConstants.KEY_BACKUP_DIR));
        }
        clearTemp();
    }

    void initializeDefaultStoragePaths(FolderType target) {
        setDefaultStoragePaths(target, false);
    }

    public void resetDefaultStoragePaths() {
        setDefaultStoragePaths(null, true);
    }

    private void setDefaultStoragePaths(FolderType target, boolean overwriteExisting) {
        Path operatingSystemRoot = null;
        if (target == null || target == FolderType.SYNC) {
            String syncDirPath = appConfigService.getConfigValue(AppConstants.KEY_SYNC_DIR);
            if (overwriteExisting || syncDirPath == null || syncDirPath.isBlank()) {
                operatingSystemRoot = defaultStorageLocationService.getOperatingSystemRoot();
                syncDirPath = defaultParentPath(operatingSystemRoot, DEFAULT_SYNC_PARENT_FOLDER);
                appConfigService.saveConfigValue(AppConstants.KEY_SYNC_DIR, syncDirPath);
                log.info("Initialized default sync folder: {}", syncDirPath);
            }
        }
        if (target == null || target == FolderType.BACKUP) {
            String backupDirPath = appConfigService.getConfigValue(AppConstants.KEY_BACKUP_DIR);
            if (overwriteExisting || backupDirPath == null || backupDirPath.isBlank()) {
                if (operatingSystemRoot == null) {
                    operatingSystemRoot = defaultStorageLocationService.getOperatingSystemRoot();
                }
                Path backupRoot = defaultStorageLocationService.findBackupRoot(operatingSystemRoot)
                        .orElse(operatingSystemRoot);
                backupDirPath = defaultParentPath(backupRoot, DEFAULT_BACKUP_PARENT_FOLDER);
                appConfigService.saveConfigValue(AppConstants.KEY_BACKUP_DIR, backupDirPath);
                log.info("Initialized default backup folder: {}", backupDirPath);
            }
        }
    }

    private String defaultParentPath(Path driveRoot, String parentFolder) {
        return driveRoot.resolve(DEFAULT_ROOT_FOLDER).resolve(parentFolder).toString();
    }

    public void shutdown() {
        cleanupConfiguredTmpFiles(FolderType.SYNC);
        cleanupConfiguredTmpFiles(FolderType.BACKUP);
        clearTemp();
        log.info("DataFolderManager shut down");
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    // Fetch latest sync directory from database instead of using cached value.
    public File getSyncDir() {
        String syncDirPath = appConfigService.getConfigValue(AppConstants.KEY_SYNC_DIR);
        if (syncDirPath == null || syncDirPath.isBlank()) {
            return null;
        }
        return new File(syncDirPath, AppConstants.SYNC_FOLDER_NAME);
    }

    // Fetch latest backupDir from database instead of using cached value
    public File getBackupDir() {
        String backupDirPath = appConfigService.getConfigValue(AppConstants.KEY_BACKUP_DIR);
        if (backupDirPath == null || backupDirPath.isBlank()) {
            return null;
        }
        return new File(backupDirPath, AppConstants.BACKUP_FOLDER_NAME);
    }

    public boolean isBackupDirConfigured() {
        return getBackupDir() != null;
    }

    public boolean isSyncDirAccessible() {
        return isDirAccessible(getSyncDir());
    }

    public boolean isBackupDirAccessible() {
        return isDirAccessible(getBackupDir());
    }

    public boolean isSyncDirAccessible(File syncDirPath) {
        return isDirAccessible(syncDirPath);
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

        if (dir == null) {
            return false;
        }

        Path root = Path.of(dir.getAbsolutePath()).getRoot();
        if (root == null) {
            return false;
        }

        File rootFile = root.toFile();
        if (!rootFile.exists()) {
            return false;
        }

        return rootFile.getUsableSpace() >= requiredBytes;
    }

    // Get the relative path after BDMA folder
    // Ex: 000000\image\0001.jpg
    public String toRelativeDataPath(String absolutePath) throws IOException {
        return toRelativeDataPath(Path.of(absolutePath)).toString();
    }

    public Path toRelativeDataPath(Path absolutePath) throws IOException {
        if (absolutePath == null) {
            throw new IOException("Absolute path is null");
        }

        // Find the deepest "sync_bdma" or "backup_bdma" folder in the path
        // Ex: D:\BDMA_User_Dataxx\sync_bdma\sync_bdma\DataSync\sync_bdma\
        Path deepestDataFolder = null;
        Path current = absolutePath;
        while (current != null) {
            if (current.getFileName() != null &&
                    (current.getFileName().toString().equals(AppConstants.SYNC_FOLDER_NAME)
                            || current.getFileName().toString().equals(AppConstants.BACKUP_FOLDER_NAME))) {
                deepestDataFolder = current;
                break;
            }
            current = current.getParent();
        }

        if (deepestDataFolder == null) {
            throw new IOException("Path does not contain BDMA data folder: " + absolutePath);
        }

        // Get relative path from the deepest BDMA folder
        return deepestDataFolder.relativize(absolutePath);
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

        ensureDirAccessible(specificDir.getAbsolutePath());

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
    public Path resolvePathAcrossDrives(String nonDriveLetterPath) throws IOException {
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            Path candidatePath = root.resolve(nonDriveLetterPath);
            try {
                if (Files.exists(candidatePath)) {
                    return candidatePath;
                }
            } catch (Exception e) {
                log.warn("Drive {} check failed: {}", root, e.getMessage());
            }
        }

        throw new FileNotFoundOnAnyDriveException(nonDriveLetterPath);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void initSyncDir(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank())
            return;

        File syncDir = new File(configuredPath, AppConstants.SYNC_FOLDER_NAME);

        // Check drive accessibility before attempting folder operations
        if (!isDriveAccessible(syncDir)) {
            log.warn("SyncDir drive not accessible during initialization: {}", syncDir.getAbsolutePath());
            log.debug("FolderManagerService.initSyncDir firing StorageUnavailableEvent: target={} reason={}",
                    FolderType.SYNC, StorageIssueReason.DRIVE_UNAVAILABLE);
            publisher.publishEvent(
                    new StorageUnavailableEvent(FolderType.SYNC, StorageIssueReason.DRIVE_UNAVAILABLE));
            return;
        }

        try {
            ensureDirAccessible(syncDir.getAbsolutePath());
            log.info("SyncDir initialized: {} (protectionEnabled={})",
                    syncDir.getAbsolutePath(), storageProtectionEnabled);
        } catch (IOException e) {
            log.error("Failed to initialize sync directory", e);
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
            ensureDirAccessible(backupDir.getAbsolutePath());
            log.info("BackupDir initialized: {} (protectionEnabled={})",
                    backupDir.getAbsolutePath(), storageProtectionEnabled);
        } catch (IOException e) {
            log.error("Failed to initialize backup directory", e);
        }
    }

    private void cleanupConfiguredTmpFiles(FolderType type) {
        File directory = type == FolderType.SYNC ? getSyncDir() : getBackupDir();
        if (isDirAccessible(directory)) {
            cleanupTmpFilesOnWorker(directory.toPath());
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

    private void cleanupTmpFilesOnWorker(Path directory) {
        try {
            for (User user : userService.findUsersOnly()) {
                cleanupUserTmpFiles(directory, user);
            }
        } catch (Exception e) {
            log.debug("Skipping temporary file cleanup for {}: {}", directory, e.getMessage());
        }
    }

    private void cleanupUserTmpFiles(Path directory, User user) {
        try {
            Path userDirectory = directory.resolve(user.getUsername());
            if (Files.isDirectory(userDirectory)) {
                deleteTmpFilesInAccessibleDirectory(userDirectory);
            }
        } catch (Exception e) {
            log.debug("Skipping temporary file cleanup for user {} in {}: {}",
                    user.getUsername(), directory, e.getMessage());
        }
    }

    /**
     * Deletes leftover temporary files inside user folders without listing the
     * protected sync or backup root directory.
     */
    private void deleteTmpFilesInAccessibleDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(AppConstants.TMP_EXTENSION))
                    .forEach(this::deleteTmpFileWithRetry);
        } catch (IOException e) {
            log.debug("Skipping temporary file cleanup for {}: {}", directory, e.getMessage());
        }
    }

    private void deleteTmpFileWithRetry(Path path) {
        for (int attempt = 1; attempt <= TMP_DELETE_MAX_ATTEMPTS; attempt++) {
            try {
                if (Files.deleteIfExists(path)) {
                    log.debug("Deleted temporary file: {}", path);
                }
                return;
            } catch (IOException e) {
                if (attempt == TMP_DELETE_MAX_ATTEMPTS) {
                    log.warn("Failed to delete temporary file after {} attempts: {} ({})",
                            TMP_DELETE_MAX_ATTEMPTS, path, e.getMessage());
                    return;
                }
                if (!sleepBeforeTmpDeleteRetry(path)) {
                    return;
                }
            }
        }
    }

    private boolean sleepBeforeTmpDeleteRetry(Path path) {
        try {
            Thread.sleep(TMP_DELETE_RETRY_DELAY_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Temporary file cleanup interrupted while waiting to retry {}", path);
            return false;
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
            ensureDirAccessible(targetParent.toString());
        }

        // Copy to temporary file first
        Path tempTarget = Path.of(target + AppConstants.TMP_EXTENSION);
        Files.copy(sourcePath, tempTarget, StandardCopyOption.REPLACE_EXISTING);

        // Validate temporary file matches source before renaming
        long sourceSize = Files.size(sourcePath);
        long tempSize = Files.size(tempTarget);
        if (sourceSize != tempSize) {
            Files.deleteIfExists(tempTarget);
            throw new IOException("Size mismatch after copy: expected " + sourceSize + " but got " + tempSize);
        }

        // Rename temporary file to final name
        Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);

        // Generate hash files for the final file
        FolderSecurityService.generateBackupHashFiles(target);

        // File got backed up to backup dir
        return target.toString();
    }

    private void ensureDirAccessible(String dirPath) throws IOException {
        FolderSecurityService.ensureDirAccessible(dirPath, storageProtectionEnabled);
    }

    private boolean allowDisabledOnlyForDev(boolean enabled) {
        if (enabled || isDevVersion()) {
            return enabled;
        }

        log.error("Ignoring {}=false because app version is not dev: {}", STORAGE_PROTECTION_ENABLED_PROPERTY,
                AppContext.getVersion());
        return true;
    }

    private boolean isDevVersion() {
        String version = AppContext.getVersion();
        return AppConstants.VERSION_DEV.equalsIgnoreCase(version == null ? "" : version.trim());
    }
}
