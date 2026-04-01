package com.app.common.theme;

import javafx.scene.Scene;

import java.util.Objects;
import java.util.prefs.Preferences;

public class ThemeManager {

    private ThemeManager() {
    }

    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    private static final String PATH_LIGHT = "/css/theme-light.css";
    private static final String PATH_DARK = "/css/theme-dark.css";

    private static final Preferences prefs =
            Preferences.userNodeForPackage(ThemeManager.class);

    public static void apply(Scene scene) {
        scene.getStylesheets().removeIf(s -> s.contains("theme-"));
        String path = cssPathForTheme(getTheme());
        String ext = Objects.requireNonNull(
                ThemeManager.class.getResource(path),
                "Theme CSS not found: " + path
        ).toExternalForm();
        if (!scene.getStylesheets().contains(ext)) {
            scene.getStylesheets().add(ext);
        }
    }

    public static void setTheme(String theme) {
        prefs.put("theme", theme);
    }

    public static String getTheme() {
        return prefs.get("theme", THEME_LIGHT);
    }

    public static String cssPathForTheme(String theme) {
        return THEME_DARK.equals(theme) ? PATH_DARK : PATH_LIGHT;
    }
}