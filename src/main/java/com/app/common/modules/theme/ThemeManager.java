package com.app.common.modules.theme;

import com.app.common.definitions.AppConstants;
import com.app.common.helpers.SpringContextHolder;
import com.app.common.services.AppConfigService;
import javafx.scene.Scene;

import java.util.Objects;

public class ThemeManager {

    private ThemeManager() {
    }

    public static void apply(Scene scene) {
        scene.getStylesheets().removeIf(s -> s.contains("theme-"));
        String path = cssPathForTheme(getTheme());
        String ext = Objects.requireNonNull(
                ThemeManager.class.getResource(path),
                "Theme CSS not found: " + path).toExternalForm();
        if (!scene.getStylesheets().contains(ext)) {
            scene.getStylesheets().add(ext);
        }
    }

    public static void setTheme(String theme) {
        if (theme == null || theme.isBlank()) {
            return;
        }
        AppConfigService appConfigService = resolveConfigService();
        if (appConfigService == null) {
            return;
        }
        String current = appConfigService.getConfigValue(AppConstants.KEY_THEME);
        if (!theme.equals(current)) {
            appConfigService.saveConfigValue(AppConstants.KEY_THEME, theme);
        }
    }

    public static String getTheme() {
        AppConfigService appConfigService = resolveConfigService();
        if (appConfigService == null) {
            return AppConstants.THEME_LIGHT;
        }
        String theme = appConfigService.getConfigValue(AppConstants.KEY_THEME);
        return (theme == null || theme.isBlank()) ? AppConstants.THEME_LIGHT : theme;
    }

    public static String cssPathForTheme(String theme) {
        return AppConstants.THEME_DARK.equals(theme) ? AppConstants.PATH_DARK : AppConstants.PATH_LIGHT;
    }

    private static AppConfigService resolveConfigService() {
        try {
            return SpringContextHolder.getBean(AppConfigService.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}