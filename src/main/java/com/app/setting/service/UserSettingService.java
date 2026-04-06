package com.app.setting.service;

import com.app.MainApp;
import com.app.common.enums.Language;
import com.app.common.enums.Theme;
import com.app.common.i18n.I18n;
import com.app.common.theme.ThemeManager;
import com.app.setting.model.UserSetting;
import com.app.setting.repository.UserSettingRepository;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class UserSettingService {

    private static final Logger log = LoggerFactory.getLogger(UserSettingService.class);

    private final UserSettingRepository repository;

    public UserSettingService(UserSettingRepository repository) {
        this.repository = repository;
    }

    public UserSetting getOrDefault(Long userId) {
        return repository.findByUserId(userId).orElseGet(() -> {
            // Tạo default nếu chưa có
            UserSetting defaultConfig = new UserSetting(null, userId, Theme.LIGHT, Language.VI);
            repository.save(defaultConfig);
            log.info("Created default UserConfig for userId={}", userId);
            return defaultConfig;
        });
    }

    public UserSetting applyRuntimeSettings(Long userId) {
        UserSetting userSetting = getOrDefault(userId);

        ThemeManager.setTheme(toThemeName(userSetting.getTheme()));
        I18n.setLocale(toLocale(userSetting.getLanguage()));

        Scene scene = MainApp.getScene();
        if (scene != null) {
            ThemeManager.apply(scene);
        }

        log.info("Applied runtime settings [userId={}]: theme={}, language={}",
                userId,
                userSetting.getTheme(),
                userSetting.getLanguage());
        return userSetting;
    }

    public void saveTheme(Long userId, Theme theme) {
        UserSetting userSetting = getOrDefault(userId);
        userSetting.setTheme(theme);
        repository.update(userSetting);
        log.info("Theme saved [userId={}]: {}", userId, theme);
    }

    public void saveLanguage(Long userId, Language language) {
        UserSetting userSetting = getOrDefault(userId);
        userSetting.setLanguage(language);
        repository.update(userSetting);
        log.info("Language saved [userId={}]: {}", userId, language);
    }

    public void save(Long userId, Theme theme, Language language) {
        UserSetting userSetting = getOrDefault(userId);
        userSetting.setTheme(theme);
        userSetting.setLanguage(language);
        repository.update(userSetting);
        log.info("UserConfig saved [userId={}]: theme={}, language={}", userId, theme, language);
    }

    private String toThemeName(Theme theme) {
        return theme == Theme.DARK ? ThemeManager.THEME_DARK : ThemeManager.THEME_LIGHT;
    }

    private Locale toLocale(Language language) {
        return language == Language.EN ? Locale.forLanguageTag("en") : Locale.forLanguageTag("vi");
    }
}