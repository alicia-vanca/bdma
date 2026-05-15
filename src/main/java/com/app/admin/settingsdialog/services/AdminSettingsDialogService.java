package com.app.admin.settingsdialog.services;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.session.Session;
import com.app.common.services.AppConfigService;
import com.app.common.services.UserSettingService;

/**
 * Manages all admin-level application settings: storage folders,
 * data-backup auto-delete, and Windows startup behavior.
 */
@Service
public class AdminSettingsDialogService {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsDialogService.class);

    private final AppConfigService appConfigService;
    private final FolderManagerService folderManagerService;
    private final RestoreService restoreService;
    private final UserSettingService userSettingService;
    private final Session session;

    public AdminSettingsDialogService(AppConfigService appConfigService,
            FolderManagerService folderManagerService,
            RestoreService restoreService,
            UserSettingService userSettingService,
            Session session) {
        this.appConfigService = appConfigService;
        this.folderManagerService = folderManagerService;
        this.restoreService = restoreService;
        this.userSettingService = userSettingService;
        this.session = session;
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

    public void setAutoDelete(boolean autoDelete) {
        appConfigService.saveConfigValue(AppConstants.KEY_IS_AUTO_DELETE_AFTER_SYNC, String.valueOf(autoDelete));
        log.info("BodyCam autoDelete set to: {}", autoDelete);
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
     * Returns true if the user has an export directory configured (non-blank stored
     * value). Does not check whether the directory is currently accessible.
     */
    public boolean isExportDirConfigured(Long userId) {
        String stored = userSettingService.getConfigValue(userId, AppConstants.KEY_USER_EXPORT_DIR);
        return stored != null && !stored.isBlank();
    }

    /**
     * Returns true if the user's configured export directory exists and its drive
     * is currently accessible.
     */
    public boolean isExportDirAvailable(Long userId) {
        String stored = userSettingService.getConfigValue(userId, AppConstants.KEY_USER_EXPORT_DIR);
        return stored != null && !stored.isBlank() && folderManagerService.isDriveAccessible(new File(stored));
    }

    /**
     * Returns the last directory the user picked via the export chooser (used as
     * the initial directory when ask-every-time is enabled). Falls back to the
     * system Downloads folder if not set or the drive is no longer accessible.
     */
    public String getLastExportDir(Long userId) {
        String stored = userSettingService.getConfigValue(userId, AppConstants.KEY_USER_LAST_EXPORT_DIR);
        if (stored != null && !stored.isBlank() && folderManagerService.isDriveAccessible(new File(stored))) {
            return stored;
        }
        if (stored != null && !stored.isBlank()) {
            log.warn("Last export dir drive unavailable for path [{}], falling back to Downloads", stored);
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

    /** Returns the stored preference; falls back to checking the registry. */
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
                    "reg", "query", AppConstants.STARTUP_REG_KEY, "/v", AppConstants.STARTUP_REG_VALUE)
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
                java.io.File exeFile = new java.io.File(exePath);
                if (!exeFile.exists()) {
                    log.error("Launcher executable not found at: {}", exePath);
                    return new RegistryCommandResult(false,
                            "Cannot set startup when running from JAR. Install the application to enable this feature.");
                }
                // Wrap in quotes so Windows handles paths with spaces correctly.
                String quotedExePath = "\"" + exePath + "\"";
                pb = new ProcessBuilder(
                        "reg", "add", AppConstants.STARTUP_REG_KEY,
                        "/v", AppConstants.STARTUP_REG_VALUE,
                        "/t", "REG_SZ",
                        "/d", quotedExePath,
                        "/f");
            } else {
                pb = new ProcessBuilder(
                        "reg", "delete", AppConstants.STARTUP_REG_KEY,
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

    // Resolve the launcher executable path from the running process or working
    // directory.
    private String resolveAppExePath() {
        // Try to locate the exe next to the JVM (packaged app installs the launcher
        // there).
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            java.io.File exeCandidate = new java.io.File(javaHome, "../../bdma.exe")
                    .toPath().normalize().toFile();
            if (exeCandidate.exists()) {
                return exeCandidate.getAbsolutePath();
            }
        }
        // Fallback: use the working directory.
        return System.getProperty("user.dir") + "\\bdma.exe";
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
}
