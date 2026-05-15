package com.app.common.services;

import com.app.MainApp;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.Language;
import com.app.common.definitions.enums.Theme;
import com.app.common.models.UserSetting;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.theme.ThemeManager;
import com.app.common.repositories.UserSettingRepository;

import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

@Service
public class UserSettingService {

    private static final Logger log = LoggerFactory.getLogger(UserSettingService.class);

    // Key constants live on the model; service references them to stay in sync.
    private final UserSettingRepository repository;
    private final FolderManagerService folderManagerService;

    public UserSettingService(UserSettingRepository repository, FolderManagerService folderManagerService) {
        this.repository = repository;
        this.folderManagerService = folderManagerService;
    }

    // ── Typed getters ────────────────────────────────────────────────────────

    /** Returns the user's stored theme, defaulting to LIGHT if not yet set. */
    public Theme getTheme(Long userId) {
        return repository.findValue(userId, UserSetting.KEY_THEME)
                .map(Theme::valueOf)
                .orElse(Theme.LIGHT);
    }

    /** Returns the user's stored language, defaulting to VI if not yet set. */
    public Language getLanguage(Long userId) {
        return repository.findValue(userId, UserSetting.KEY_LANGUAGE)
                .map(Language::valueOf)
                .orElse(Language.VI);
    }

    // ── Full settings object (used for UI population) ─────────────────────────

    /**
     * Load all settings for the user in one query.
     * Persists defaults on first access so subsequent reads always find a row.
     */
    public UserSetting getOrDefault(Long userId) {
        return repository.findByUserId(userId).orElseGet(() -> {
            UserSetting defaults = new UserSetting(userId, Map.of(
                    UserSetting.KEY_THEME, Theme.LIGHT.name(),
                    UserSetting.KEY_LANGUAGE, Language.VI.name()));
            repository.save(defaults);
            log.info("Created default UserSetting for userId={}", userId);
            return defaults;
        });
    }

    // ── Apply settings at login ───────────────────────────────────────────────

    /**
     * Load user preferences and apply them to the active scene.
     * Called once at login; persists defaults if the user has no saved settings.
     */
    public UserSetting applyRuntimeSettings(Long userId) {
        UserSetting setting = getOrDefault(userId);

        ThemeManager.setTheme(toThemeName(setting.getTheme()));
        I18n.setLocale(toLocale(setting.getLanguage()));

        Scene scene = MainApp.getScene();
        if (scene != null) {
            ThemeManager.apply(scene);
        }

        log.info("Applied runtime settings [userId={}]: theme={}, language={}",
                userId, setting.getTheme(), setting.getLanguage());
        return setting;
    }

    // ── Typed setters ─────────────────────────────────────────────────────────

    /** Persist the user's theme choice. */
    public void saveTheme(Long userId, Theme theme) {
        repository.upsertValue(userId, UserSetting.KEY_THEME, theme.name());
        log.info("Theme saved [userId={}]: {}", userId, theme);
    }

    /** Persist the user's language choice. */
    public void saveLanguage(Long userId, Language language) {
        repository.upsertValue(userId, UserSetting.KEY_LANGUAGE, language.name());
        log.info("Language saved [userId={}]: {}", userId, language);
    }

    // ── Last open path ────────────────────────────────────────────────────────

    /**
     * Returns the last directory the user opened in a file/folder picker.
     * Falls back to the system Downloads folder if no path is stored or the
     * drive it lives on is no longer accessible (e.g. removed USB drive).
     */
    public String getLastOpenPath(Long userId) {
        String stored = getConfigValue(userId, AppConstants.KEY_USER_LAST_OPEN_PATH);
        if (stored != null && folderManagerService.isDriveAccessible(new File(stored))) {
            return stored;
        }
        return Path.of(System.getProperty("user.home"), "Downloads").toString();
    }

    /**
     * Persist the directory the user most recently opened in a file/folder picker.
     */
    public void saveLastOpenPath(Long userId, String path) {
        saveConfigValue(userId, AppConstants.KEY_USER_LAST_OPEN_PATH, path);
    }

    // ── Generic per-user key-value access ────────────────────────────────────

    /**
     * Returns the stored value for an arbitrary per-user key, or null if not set.
     */
    public String getConfigValue(Long userId, String key) {
        return repository.findValue(userId, key).orElse(null);
    }

    /** Persist an arbitrary per-user key-value entry. */
    public void saveConfigValue(Long userId, String key, String value) {
        repository.upsertValue(userId, key, value);
        log.debug("User config saved [userId={}] {}={}", userId, key, value);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private String toThemeName(Theme theme) {
        return theme == Theme.DARK ? AppConstants.THEME_DARK : AppConstants.THEME_LIGHT;
    }

    private Locale toLocale(Language language) {
        return language == Language.EN ? Locale.forLanguageTag("en") : Locale.forLanguageTag("vi");
    }
}
