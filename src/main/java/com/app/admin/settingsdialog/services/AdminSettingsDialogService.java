package com.app.admin.settingsdialog.services;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.services.AppConfigService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

import java.util.Optional;

/**
 * Manages all admin-level application settings: storage folders,
 * data-backup auto-delete, and Windows startup behavior.
 */
@Service
public class AdminSettingsDialogService {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsDialogService.class);

    private final AppConfigService appConfigService;
    private final FolderManagerService folderManagerService;

    public AdminSettingsDialogService(AppConfigService appConfigService,
            FolderManagerService folderManagerService) {
        this.appConfigService = appConfigService;
        this.folderManagerService = folderManagerService;
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    public Optional<String> getFolderPath(FolderType type) {
        String key = type == FolderType.SAVE ? AppConstants.KEY_DATA_DIR : AppConstants.KEY_BACKUP_DIR;
        String path = appConfigService.getConfigValue(key);
        return (path != null && !path.isBlank()) ? Optional.of(path) : Optional.empty();
    }

    public void saveFolder(String path, FolderType type) {
        String key = type == FolderType.SAVE ? AppConstants.KEY_DATA_DIR : AppConstants.KEY_BACKUP_DIR;
        String normalizedPath = Path.of(path).toAbsolutePath().normalize().toString();
        appConfigService.saveConfigValue(key, normalizedPath);
        folderManagerService.init();
        log.info("Saved folder [{}]: {}", type.name(), normalizedPath);
    }

    // ── Data backup ───────────────────────────────────────────────────────────

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
        if (!applyRegistryEntry(enable)) {
            throw new IllegalStateException("Unable to update startup registry entry");
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

    private boolean applyRegistryEntry(boolean enable) {
        try {
            ProcessBuilder pb;
            if (enable) {
                String exePath = resolveAppExePath();
                java.io.File exeFile = new java.io.File(exePath);
                if (!exeFile.exists()) {
                    log.error("Launcher executable not found at: {}", exePath);
                    return false;
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
            int exitCode = pb.redirectErrorStream(true).start().waitFor();
            if (exitCode != 0) {
                log.warn("Startup registry command exited with code {}", exitCode);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while applying startup registry entry", e);
            return false;
        } catch (Exception e) {
            log.error("Failed to apply startup registry entry", e);
            return false;
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
}
