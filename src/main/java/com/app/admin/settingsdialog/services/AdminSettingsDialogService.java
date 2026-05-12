package com.app.admin.settingsdialog.services;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.services.AppConfigService;

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

    public AdminSettingsDialogService(AppConfigService appConfigService,
            FolderManagerService folderManagerService,
            RestoreService restoreService) {
        this.appConfigService = appConfigService;
        this.folderManagerService = folderManagerService;
        this.restoreService = restoreService;
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    public Optional<String> getFolderPath(FolderType type) {
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
