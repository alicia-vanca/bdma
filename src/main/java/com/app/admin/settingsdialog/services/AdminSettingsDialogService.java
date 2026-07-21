package com.app.admin.settingsdialog.services;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.Language;
import com.app.common.definitions.enums.Role;
import com.app.common.definitions.enums.Theme;
import com.app.common.modules.databackup.queues.DataBackupQueue;
import com.app.common.modules.datarestore.services.RestoreService;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.session.Session;
import com.app.common.services.AppConfigService;
import com.app.common.services.UserSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Manages all admin-level application settings: storage folders,
 * data-backup auto-delete, and Windows startup behavior.
 */
@Service
public class AdminSettingsDialogService {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsDialogService.class);
    private static final Path REG_EXE_PATH = Path.of("C:", "Windows", "System32", "reg.exe");

    private final AppConfigService appConfigService;
    private final FolderManagerService folderManagerService;
    private final RestoreService restoreService;
    private final UserSettingService userSettingService;
    private final Session session;
    private final DeviceSyncQueue deviceSyncQueue;
    private final DataBackupQueue dataBackupQueue;

    public AdminSettingsDialogService(AppConfigService appConfigService,
            FolderManagerService folderManagerService,
            RestoreService restoreService,
            UserSettingService userSettingService,
            Session session,
            DeviceSyncQueue deviceSyncQueue,
            DataBackupQueue dataBackupQueue) {
        this.appConfigService = appConfigService;
        this.folderManagerService = folderManagerService;
        this.restoreService = restoreService;
        this.userSettingService = userSettingService;
        this.session = session;
        this.deviceSyncQueue = deviceSyncQueue;
        this.dataBackupQueue = dataBackupQueue;
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    public Optional<String> getFolderPath(FolderType type) {
        // Export dir is per-user; reload from user settings rather than global config.
        if (type == FolderType.EXPORT) {
            return Optional.of(getExportFolderForUser(session.getCurrentUserId()));
        }
        String path = appConfigService.getConfigValue(type);
        return (path != null && !path.isBlank()) ? Optional.of(path) : Optional.empty();
    }

    public void saveFolder(FolderType type, String path) {
        String normalizedPath = Path.of(path).toAbsolutePath().normalize().toString();
        appConfigService.saveConfigValue(type, normalizedPath);
        folderManagerService.init(type);
        log.info("Saved folder [{}]: {}", type.name(), normalizedPath);
    }

    // ── Data sync ───────────────────────────────────────────────────────────

    public boolean getAutoDelete() {
        return Boolean.parseBoolean(appConfigService.getConfigValue(AppConstants.KEY_IS_AUTO_DELETE_AFTER_SYNC));
    }

    public boolean getDeleteEmptyDateFolders() {
        return Boolean.parseBoolean(
                appConfigService.getConfigValue(AppConstants.KEY_IS_DELETE_EMPTY_DATE_FOLDER_AFTER_SYNC));
    }

    /**
     * Persists the parent and child while maintaining child=true => parent=true.
     */
    @Transactional
    public void setAutoDeleteState(boolean autoDelete, boolean deleteEmptyDateFolders) {
        if (!autoDelete) {
            appConfigService.saveConfigValue(
                    AppConstants.KEY_IS_DELETE_EMPTY_DATE_FOLDER_AFTER_SYNC, Boolean.FALSE.toString());
            appConfigService.saveConfigValue(AppConstants.KEY_IS_AUTO_DELETE_AFTER_SYNC, Boolean.FALSE.toString());
        } else {
            appConfigService.saveConfigValue(AppConstants.KEY_IS_AUTO_DELETE_AFTER_SYNC, Boolean.TRUE.toString());
            appConfigService.saveConfigValue(
                    AppConstants.KEY_IS_DELETE_EMPTY_DATE_FOLDER_AFTER_SYNC,
                    String.valueOf(deleteEmptyDateFolders));
        }
        log.info("BodyCam autoDelete set to: {}, deleteEmptyDateFolders set to: {}",
                autoDelete, deleteEmptyDateFolders);
    }

    public void setDeleteEmptyDateFolders(boolean deleteEmptyDateFolders) {
        setAutoDeleteState(getAutoDelete(), deleteEmptyDateFolders);
    }

    // ── Export ─────────────────────────────────────────────────────────────

    /**
     * Returns the user's configured export directory used when ask-every-time is
     * disabled. A missing value is initialized to the system Downloads folder;
     * availability is checked by the export request before copying starts.
     */
    public String getExportFolderForUser(Long userId) {
        String stored = userSettingService.getConfigValue(userId, AppConstants.KEY_USER_EXPORT_DIR);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        String downloadsPath = Path.of(System.getProperty("user.home"), "Downloads").toString();

        log.warn("Export folder has not been set for user [{}], falling back to Downloads", userId);
        saveExportFolderForUser(userId, downloadsPath);

        return downloadsPath;
    }

    /**
     * Returns the last directory the user picked via the export chooser (used as
     * the initial directory when ask-every-time is enabled). Falls back to the
     * system Downloads folder if not set or the path no longer exists.
     */
    public String getLastExportDir(Long userId) {
        String stored = userSettingService.getConfigValue(userId, AppConstants.KEY_USER_LAST_EXPORT_DIR);
        if (stored != null && !stored.isBlank() && Files.exists(Path.of(stored))) {
            return stored;
        }
        if (stored != null && !stored.isBlank()) {
            log.warn("Last export dir path no longer exists for path [{}], falling back to Downloads", stored);
        }
        return Path.of(System.getProperty("user.home"), "Downloads").toString();
    }

    /**
     * Persists the user's configured export directory from the settings page.
     * Saves to both the configured-dir key and the last-chosen-dir key so the
     * chooser also opens at the newly configured location.
     */
    public void saveExportFolderForUser(Long userId, String path) {
        String normalizedPath = Path.of(path).toAbsolutePath().normalize().toString();
        userSettingService.saveConfigValue(userId, AppConstants.KEY_USER_EXPORT_DIR, normalizedPath);
        userSettingService.saveConfigValue(userId, AppConstants.KEY_USER_LAST_EXPORT_DIR, normalizedPath);
        log.info("Saved per-user export folder [userId={}]: {}", userId, normalizedPath);
    }

    /**
     * Persists the directory the user most recently selected in the export chooser.
     * Only updates the last-chosen key; the configured default is not changed.
     */
    public void saveLastExportDir(Long userId, String path) {
        String normalizedPath = Path.of(path).toAbsolutePath().normalize().toString();
        userSettingService.saveConfigValue(userId, AppConstants.KEY_USER_LAST_EXPORT_DIR, normalizedPath);
        log.debug("Saved last export dir [userId={}]: {}", userId, normalizedPath);
    }

    /**
     * Returns true when this user wants to be prompted for an export location on
     * every export action. Defaults to false if not yet set.
     */
    public boolean getAskEveryTimeExport(Long userId) {
        String value = userSettingService.getConfigValue(userId, AppConstants.KEY_USER_ASK_EVERY_TIME_EXPORT);
        if (value == null || value.isBlank()) {
            setAskEveryTimeExport(userId, false);
            return false;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Persists the per-user preference for asking the export location on each
     * export action.
     */
    public void setAskEveryTimeExport(Long userId, boolean askEveryTime) {
        userSettingService.saveConfigValue(userId, AppConstants.KEY_USER_ASK_EVERY_TIME_EXPORT,
                String.valueOf(askEveryTime));
        log.info("Ask every time export set to: {} [userId={}]", askEveryTime, userId);
    }

    // ── Start with Windows ────────────────────────────────────────────────────

    /**
     * Returns the stored preference; falls back to checking the registry.
     */
    public boolean getStartWithWindows() {
        String stored = appConfigService.getConfigValue(AppConstants.KEY_IS_START_WITH_WINDOWS);
        if (stored != null) {
            return Boolean.parseBoolean(stored);
        }
        return checkRegistryEntryExists();
    }

    public void setStartWithWindows(boolean enable) {
        RegistryCommandResult result = applyRegistryEntry(enable);
        if (!result.success()) {
            throw new IllegalStateException("Unable to update startup registry entry. " + result.message());
        }
        appConfigService.saveConfigValue(AppConstants.KEY_IS_START_WITH_WINDOWS, String.valueOf(enable));
        log.info("Start with Windows set to: {}", enable);
    }

    // ── Registry helpers ──────────────────────────────────────────────────────

    private boolean checkRegistryEntryExists() {
        try {
            Process proc = new ProcessBuilder(
                    REG_EXE_PATH.toString(), "query", AppConstants.STARTUP_REG_KEY, "/v",
                    AppConstants.STARTUP_REG_VALUE)
                    .redirectErrorStream(true)
                    .start();
            return proc.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while querying startup registry entry", e);
            return false;
        } catch (Exception e) {
            log.warn("Could not query startup registry entry", e);
            return false;
        }
    }

    // Execute registry add/delete and return a user-facing diagnostic message when
    // the command cannot be applied.
    private RegistryCommandResult applyRegistryEntry(boolean enable) {
        try {
            ProcessBuilder pb;
            if (enable) {
                String exePath = resolveAppExePath();
                File exeFile = new File(exePath);
                if (!exeFile.exists()) {
                    log.error("Launcher executable not found at: {}", exePath);
                    return new RegistryCommandResult(false,
                            "Cannot set startup when running from JAR. Install the application to enable this feature.");
                }
                // Wrap in quotes so Windows handles paths with spaces correctly.
                String quotedExePath = "\"" + exePath + "\"";
                pb = new ProcessBuilder(
                        REG_EXE_PATH.toString(), "add", AppConstants.STARTUP_REG_KEY,
                        "/v", AppConstants.STARTUP_REG_VALUE,
                        "/t", "REG_SZ",
                        "/d", quotedExePath,
                        "/f");
            } else {
                pb = new ProcessBuilder(
                        REG_EXE_PATH.toString(), "delete", AppConstants.STARTUP_REG_KEY,
                        "/v", AppConstants.STARTUP_REG_VALUE,
                        "/f");
            }
            Process process = pb.redirectErrorStream(true).start();
            int exitCode = process.waitFor();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (exitCode != 0) {
                log.warn("Startup registry command exited with code {}. Output: {}", exitCode, output);
                return new RegistryCommandResult(false,
                        "Command exited with code " + exitCode + (output.isBlank() ? "" : ". " + output));
            }
            return RegistryCommandResult.ok();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while applying startup registry entry", e);
            return new RegistryCommandResult(false, "Interrupted while applying startup registry entry");
        } catch (Exception e) {
            log.error("Failed to apply startup registry entry", e);
            return new RegistryCommandResult(false, e.getMessage() == null ? "Unknown error" : e.getMessage());
        }
    }

    private record RegistryCommandResult(boolean success, String message) {
        private static RegistryCommandResult ok() {
            return new RegistryCommandResult(true, "");
        }
    }

    // Resolve the installed launcher from the current process first. Unlike
    // user.dir, the process command is unaffected by a shortcut's "Start in"
    // directory.
    private String resolveAppExePath() {
        Optional<String> processCommand = ProcessHandle.current().info().command();
        if (processCommand.isPresent()) {
            Path runningExecutable = Path.of(processCommand.get()).toAbsolutePath().normalize();
            Path fileName = runningExecutable.getFileName();
            if (fileName != null
                    && isNativeLauncher(fileName.toString())
                    && Files.isRegularFile(runningExecutable)) {
                return runningExecutable.toString();
            }
        }

        // jpackage installs the launcher beside its runtime directory.
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path exeCandidate = Path.of(javaHome, "..", "BDMA.exe").toAbsolutePath().normalize();
            if (Files.isRegularFile(exeCandidate)) {
                return exeCandidate.toString();
            }
        }

        // Compatibility fallback for layouts that launch with the install directory
        // as their working directory.
        String userDir = System.getProperty("user.dir", "");
        return Path.of(userDir, "BDMA.exe").toAbsolutePath().normalize().toString();
    }

    private boolean isNativeLauncher(String fileName) {
        return fileName.regionMatches(true, fileName.length() - 4, ".exe", 0, 4)
                && !fileName.equalsIgnoreCase("java.exe")
                && !fileName.equalsIgnoreCase("javaw.exe");
    }

    public RestoreService.BackupSyncResult restore() {
        return restoreService.restore();
    }

    public RestoreService.BackupSyncResult retryFailed() {
        return restoreService.retryFailed();
    }

    public String getLastRestoreProgress() {
        return appConfigService.getConfigValue(AppConstants.KEY_LAST_RESTORE_PROGRESS);
    }

    // ── Default Settings Reset ──────────────────────────────────────────────

    /**
     * Checks whether a settings reset is currently blocked by active operations.
     * Only ADMIN and DEV scopes are blocked; USER scope can reset anytime.
     */
    public boolean isResetBlocked(Role scope) {
        if (scope == Role.USER) {
            return false;
        }
        return deviceSyncQueue.isActive() || dataBackupQueue.isActive() || restoreService.isRunning();
    }

    public boolean resetToDefaults(Long userId, Role scope) {
        try {
            log.info("Resetting settings to defaults: scope={}, userId={}", scope, userId);

            if (scope == Role.ADMIN || scope == Role.DEV
                    || scope == Role.USER) {
                userSettingService.saveLanguage(userId, Language.VI);
                userSettingService.saveTheme(userId, Theme.LIGHT);
            }

            if (scope == Role.ADMIN || scope == Role.USER) {
                String downloadsPath = Path.of(System.getProperty("user.home"), "Downloads").toString();
                userSettingService.saveConfigValue(userId, AppConstants.KEY_USER_EXPORT_DIR, downloadsPath);
                userSettingService.saveConfigValue(userId, AppConstants.KEY_USER_LAST_EXPORT_DIR, downloadsPath);
                userSettingService.saveConfigValue(userId, AppConstants.KEY_USER_ASK_EVERY_TIME_EXPORT, "false");
            }

            if (scope == Role.ADMIN || scope == Role.DEV) {
                appConfigService.saveConfigValue(AppConstants.KEY_IS_AUTO_DELETE_AFTER_SYNC, "false");
                appConfigService.saveConfigValue(AppConstants.KEY_IS_DELETE_EMPTY_DATE_FOLDER_AFTER_SYNC, "false");

                folderManagerService.resetDefaultStoragePaths();

                setStartWithWindows(false);
            }

            log.info("Settings reset completed successfully: scope={}", scope);
            return true;

        } catch (IllegalStateException e) {
            log.warn("Could not disable startup entry (may already be absent): {}", e.getMessage());
            return true;
        } catch (Exception e) {
            log.error("Failed to reset settings: scope={}, userId={}", scope, userId, e);
            return false;
        }
    }

}
