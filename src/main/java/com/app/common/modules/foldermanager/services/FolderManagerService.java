package com.app.common.modules.foldermanager.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;

import com.app.common.services.WindowsCommandService;
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
    private final WindowsCommandService windowsCommandService;
    private final VssService vssService;

    private static class ResolvedPath {
        final Path filePath;
        final FolderSecurityService security;

        ResolvedPath(Path filePath, FolderSecurityService security) {
            this.filePath = filePath;
            this.security = security;
        }
    }

    public FolderManagerService(AppConfigService appConfigService,
                                @Value("${app.storage.protection.enabled:true}") boolean storageProtectionEnabled,
                                WindowsCommandService windowsCommandService,
                                VssService vssService) {
        this.appConfigService = appConfigService;
        this.storageProtectionEnabled = storageProtectionEnabled;
        this.windowsCommandService = windowsCommandService;
        this.vssService = vssService;
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
        cleanupSyncTempDir();
        cleanupBackupTempDir();
    }

    public void shutdown() {
        if (dataSecurity != null) dataSecurity.ensureLocked();
        if (backupSecurity != null) backupSecurity.ensureLocked();
        cleanupSyncTempDir();
        cleanupBackupTempDir();
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

    public void saveFileFromTemp(String nonDriverLetterSyncedPath) throws IOException {
        ResolvedPath resolved = null;
        try {
            resolved = resolveAndUnlockDataPath(nonDriverLetterSyncedPath);
            String fileName = resolved.filePath.getFileName().toString();
            File target = resolved.filePath.toFile();
            ensureParentDir(target);
            Files.copy(new File(tempDir, fileName).toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("Saved {} from temp to data", fileName);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to save file from temp: " + nonDriverLetterSyncedPath, e);
        } finally {
            if (resolved != null) {
                boolean wasInterrupted = Thread.interrupted();
                try {
                    resolved.security.ensureLocked();
                } finally {
                    if (wasInterrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Backup files from data_bdma to backup_bdma using a VSS snapshot.
     */
    public void backupFromSave(String nonDriverLetterSyncedPath) throws IOException {
        if (backupDir == null) {
            throw new IOException("Backup directory not configured");
        }
        ensureDataReady();

        try {
            Path sourceAbsolutePath = resolveLockedDataPath(nonDriverLetterSyncedPath);
            if (sourceAbsolutePath == null) {
                throw new IOException("File not found in data directory: " + nonDriverLetterSyncedPath);
            }

            String relativePath = extractRelativePathAfterDataFolder(nonDriverLetterSyncedPath);
            Path targetInBackup = backupDir.toPath().resolve(relativePath);

            File tempBackupFile = createBackupTempFile(targetInBackup.toString());

            vssService.copyViaSnapshot(sourceAbsolutePath, tempBackupFile.toPath());

            moveBackupTempFileToBackup(tempBackupFile, targetInBackup.toFile());
            log.info("Backed up via VSS: {}", relativePath);

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to back up file: " + nonDriverLetterSyncedPath, e);
        } finally {
            cleanupBackupTempDir();
        }
    }

    private String extractRelativePathAfterDataFolder(String nonDriverLetterSyncedPath) {
        String dataFolderPrefix = AppConstants.DATA_FOLDER_NAME + File.separator;
        int index = nonDriverLetterSyncedPath.indexOf(dataFolderPrefix);
        if (index >= 0) {
            return nonDriverLetterSyncedPath.substring(index + dataFolderPrefix.length());
        }
        return nonDriverLetterSyncedPath;
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

    public synchronized <T> T withBackupDirUnlocked(Callable<T> action) throws Exception {
        if (backupDir == null || backupSecurity == null) {
            throw new IOException("Backup directory is not configured yet.");
        }
        backupSecurity.ensureUnlocked();
        try {
            ensureDir(backupDir);
            return action.call();
        } finally {
            boolean wasInterrupted = Thread.interrupted();
            try {
                backupSecurity.ensureLocked();
            } finally {
                if (wasInterrupted) Thread.currentThread().interrupt();
            }
        }
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
                    storageProtectionEnabled,
                    windowsCommandService);
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

    // ── Path Resolution ──────────────────────────────────────────────────────

    /**
     * Find absolute path from non-drive-letter synced path by checking all
     * available drives.
     * Handles locked directories by temporarily unlocking them to check existence.
     * Returns null if file not found on any drive.
     */
    public Path findAbsolutePathFromNonDriveSyncedPath(String nonDriverLetterSyncedPath) {
        try {
            ResolvedPath resolved = resolveAndUnlockDataPath(nonDriverLetterSyncedPath);
            // Lock it back since caller doesn't need it unlocked
            resolved.security.ensureLocked();
            return resolved.filePath;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Find absolute path from non-drive-letter backed up path by checking all
     * available drives.
     * Handles locked directories by temporarily unlocking them to check existence.
     * Returns null if file not found on any drive.
     */
    public Path findAbsolutePathFromNonDriveBackedUpPath(String nonDriverLetterBackedUpPath) {
        try {
            ResolvedPath resolved = resolveAndUnlockBackupPath(nonDriverLetterBackedUpPath);
            // Lock it back since caller doesn't need it unlocked
            resolved.security.ensureLocked();
            return resolved.filePath;
        } catch (IOException e) {
            return null;
        }
    }

    private Path resolveLockedDataPath(String nonDriverLetterSyncedPath) {
        int dataFolderIndex = findDataFolderIndex(nonDriverLetterSyncedPath);
        if (dataFolderIndex < 0) {
            log.warn("Cannot find data folder in path: {}", nonDriverLetterSyncedPath);
            return null;
        }

        String parentRelative = extractParentRelative(nonDriverLetterSyncedPath, dataFolderIndex);
        String relativePart = extractRelativePart(nonDriverLetterSyncedPath, dataFolderIndex);

        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            Path parentPath = parentRelative.isBlank() ? root : root.resolve(parentRelative);
            Path lockedPath = parentPath.resolve(FolderType.SAVE.getLockedName());

            if (!Files.exists(lockedPath)) {
                continue;
            }

            Path result = processDrive(root, parentPath, relativePart);
            if (result != null) {
                return result;
            }
        }

        log.warn("File not found on any drive: {}", nonDriverLetterSyncedPath);
        return null;
    }

    private int findDataFolderIndex(String path) {
        String prefix = AppConstants.DATA_FOLDER_NAME + File.separator;

        int index = path.indexOf(prefix);
        if (index >= 0) {
            return index;
        }

        return path.indexOf(AppConstants.DATA_FOLDER_NAME);
    }

    private String extractParentRelative(String path, int index) {
        String parent = path.substring(0, index);
        return parent.endsWith(File.separator)
                ? parent.substring(0, parent.length() - 1)
                : parent;
    }

    private String extractRelativePart(String path, int index) {
        String prefix = AppConstants.DATA_FOLDER_NAME + File.separator;
        int start = index + prefix.length();

        return start < path.length() ? path.substring(start) : "";
    }

    private Path processDrive(Path root, Path parentPath, String relativePart) {
        FolderSecurityService tempSecurity = new FolderSecurityService(
                parentPath.resolve(AppConstants.DATA_FOLDER_NAME).toString(),
                FolderType.SAVE,
                storageProtectionEnabled,
                windowsCommandService);

        try {
            tempSecurity.ensureUnlocked();

            Path candidate = parentPath
                    .resolve(AppConstants.DATA_FOLDER_NAME)
                    .resolve(relativePart);

            if (Files.exists(candidate)) {
                return parentPath
                        .resolve(FolderType.SAVE.getLockedName())
                        .resolve(relativePart);
            }

        } catch (Exception e) {
            log.warn("Failed to check drive {}: {}", root, e.getMessage());
        } finally {
            try {
                tempSecurity.ensureLocked();
            } catch (Exception e) {
                log.warn("Failed to re-lock on drive {}: {}", root, e.getMessage());
            }
        }

        return null;
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
     * Try each drive letter to find and unlock the data directory containing the
     * file.
     * Returns the resolved file path and the unlocked directory.
     */
    private ResolvedPath resolveAndUnlockDataPath(String nonDriverLetterSyncedPath) throws IOException {
        return resolveAndUnlock(nonDriverLetterSyncedPath, FolderType.SAVE);
    }

    /**
     * Try each drive letter to find and unlock the backup directory containing
     * the file.
     * Returns the resolved file path and the unlocked directory.
     */
    private ResolvedPath resolveAndUnlockBackupPath(String nonDriverLetterBackedUpPath) throws IOException {
        return resolveAndUnlock(nonDriverLetterBackedUpPath, FolderType.BACKUP);
    }

    /**
     * Generic method to find and unlock a directory containing a file.
     * Searches across all drives based on folder type.
     */
    private ResolvedPath resolveAndUnlock(String nonDriverLetterPath, FolderType folderType) throws IOException {
        String folderName = (folderType == FolderType.BACKUP)
                ? AppConstants.BACKUP_FOLDER_NAME
                : AppConstants.DATA_FOLDER_NAME;

        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            Path result = (Path) tryResolveOnDrive(root, nonDriverLetterPath, folderType, folderName);
            if (result != null) {
                return (ResolvedPath) result;
            }
        }

        throw new IOException("File not found on any drive: " + nonDriverLetterPath);
    }

    private ResolvedPath tryResolveOnDrive(Path root,
                                           String nonDriverLetterPath,
                                           FolderType folderType,
                                           String folderName) {

        Path candidatePath = root.resolve(nonDriverLetterPath);
        Path deepestFolder = resolveDeepestFolder(candidatePath, folderType);

        if (deepestFolder == null) {
            log.warn("No {} folder found in path on drive {}: {}", folderName, root, candidatePath);
            return null;
        }

        File dirToUnlock = deepestFolder.getParent().toFile();
        if (!dirToUnlock.exists()) {
            log.info("Directory not found on drive {}: {}", root, dirToUnlock.getAbsolutePath());
            return null;
        }

        return processUnlock(root, candidatePath, deepestFolder, folderType);
    }

    private Path resolveDeepestFolder(Path candidatePath, FolderType folderType) {
        return (folderType == FolderType.BACKUP)
                ? findDeepestBackupFolder(candidatePath)
                : findDeepestDataFolder(candidatePath);
    }

    private ResolvedPath processUnlock(Path root,
                                       Path candidatePath,
                                       Path deepestFolder,
                                       FolderType folderType) {

        FolderSecurityService tempSecurity = new FolderSecurityService(
                deepestFolder.toString(),
                folderType,
                storageProtectionEnabled,
                windowsCommandService);

        try {
            tempSecurity.ensureUnlocked();

            if (Files.exists(candidatePath)) {
                log.info("Found file on drive {}: {}", root, candidatePath);
                return new ResolvedPath(candidatePath, tempSecurity);
            }

            log.info("File not found on drive {} after unlock: {}", root, candidatePath);

        } catch (Exception e) {
            log.info("Failed to check drive {}: {}", root, e.getMessage());
        } finally {
            try {
                tempSecurity.ensureLocked();
            } catch (Exception ignored) {
                // ignore re-lock failure
            }
        }

        return null;
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void initDataDir(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank())
            return;

        this.dataDir = new File(configuredPath, AppConstants.DATA_FOLDER_NAME);
        this.dataSecurity = new FolderSecurityService(
                dataDir.getAbsolutePath(),
                FolderType.SAVE,
                storageProtectionEnabled,
                windowsCommandService);
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
                storageProtectionEnabled,
                windowsCommandService);
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

    public File createSyncTempFile(String targetAbsolutePath) throws IOException {
        ensureDataReady();

        File tempRoot = getSyncTempRoot();
        if (tempRoot == null) {
            throw new IOException("Sync temp root is not available.");
        }

        ensureDir(tempRoot);
        setHidden(tempRoot);

        Path targetPath = Path.of(targetAbsolutePath).toAbsolutePath().normalize();
        Path relativeTarget = Path.of(dataDir.getAbsolutePath())
                .toAbsolutePath()
                .normalize()
                .relativize(targetPath);

        File tempFile = tempRoot.toPath().resolve(relativeTarget).toFile();
        ensureParentDir(tempFile);

        return tempFile;
    }

    public void moveSyncTempFileToData(File tempFile, File targetFile) throws Exception {
        withDataDirUnlocked(() -> {
            ensureParentDir(targetFile);
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return null;
        });
    }

    public void cleanupSyncTempDir() {
        File tempRoot = getSyncTempRoot();
        if (tempRoot != null) deleteDirectory(tempRoot);
    }

    public File createBackupTempFile(String targetAbsolutePath) throws IOException {
        if (backupDir == null) {
            throw new IOException("Backup directory is not configured yet.");
        }

        File tempRoot = getBackupTempRoot();
        if (tempRoot == null) {
            throw new IOException("Backup temp root is not available.");
        }

        ensureDir(tempRoot);
        setHidden(tempRoot);

        Path targetPath = Path.of(targetAbsolutePath).toAbsolutePath().normalize();
        Path relativeTarget = Path.of(backupDir.getAbsolutePath())
                .toAbsolutePath()
                .normalize()
                .relativize(targetPath);

        File tempFile = tempRoot.toPath().resolve(relativeTarget).toFile();
        ensureParentDir(tempFile);

        return tempFile;
    }

    public void moveBackupTempFileToBackup(File tempFile, File targetFile) throws Exception {
        withBackupDirUnlocked(() -> {
            ensureParentDir(targetFile);
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return null;
        });
    }

    public void cleanupBackupTempDir() {
        File tempRoot = getBackupTempRoot();
        if (tempRoot != null) deleteDirectory(tempRoot);
    }

    private File getSyncTempRoot() {
        if (dataDir == null || dataDir.getParentFile() == null) return null;
        return new File(dataDir.getParentFile(), AppConstants.SYNC_TEMP_FOLDER_NAME);
    }

    private File getBackupTempRoot() {
        if (backupDir == null || backupDir.getParentFile() == null) return null;
        return new File(backupDir.getParentFile(), AppConstants.BACKUP_TEMP_FOLDER_NAME);
    }

    private void setHidden(File directory) {
        try {
            Path path = directory.toPath();
            if (Files.exists(path)) {
                Files.setAttribute(path, "dos:hidden", true);
            }
        } catch (Exception e) {
            log.warn("Failed to set hidden attribute on {}: {}", directory.getAbsolutePath(), e.getMessage());
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

    public String stripDriveLetter(String absolutePath) {
        Path path = Path.of(absolutePath);
        Path root = path.getRoot();
        return root != null ? root.relativize(path).toString() : absolutePath;
    }
}
