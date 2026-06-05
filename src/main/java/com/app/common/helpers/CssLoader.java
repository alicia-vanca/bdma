package com.app.common.helpers;

import com.app.MainApp;
import com.app.common.modules.theme.ThemeManager;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class CssLoader {

    private static final Logger log = LoggerFactory.getLogger(CssLoader.class);

    private CssLoader() {
    }

    public static void applyBase(Scene scene) {
        addIfAbsent(scene, "/css/base.css");
    }

    public static void applyAdmin(Scene scene) {
        scene.getStylesheets().removeIf(s -> s.contains("login.css"));
        addIfAbsent(scene, "/css/admin.css");
    }

    public static void applyLogin(Scene scene) {
        scene.getStylesheets().removeIf(s -> s.contains("admin.css")
                || s.contains("/css/admin/")
                || s.contains("/css/dev/")
                || s.contains("/css/user/"));
        addIfAbsent(scene, "/css/auth/login.css");
    }

    public static void applyModule(Scene scene, String fxml) {
        String moduleCss = resolveCssPath(fxml);
        if (moduleCss == null)
            return;

        String baseExt = toExternalForm("/css/base.css");
        String adminExt = toExternalForm("/css/admin.css");
        String themeExt = toExternalForm(ThemeManager.cssPathForTheme(ThemeManager.getTheme()));
        String moduleExt = toExternalForm(moduleCss);

        // Clear all module CSS before applying the current module stylesheet.
        scene.getStylesheets()
                .removeIf(s -> s.contains("/css/admin/") || s.contains("/css/dev/") || s.contains("/css/user/"));

        // Rebuild in stable order: base → theme → admin → module.
        List<String> ordered = new ArrayList<>();
        if (baseExt != null)
            ordered.add(baseExt);
        if (themeExt != null)
            ordered.add(themeExt);
        if (adminExt != null)
            ordered.add(adminExt);
        if (moduleExt != null)
            ordered.add(moduleExt);

        // Set lại toàn bộ theo thứ tự đúng
        scene.getStylesheets().setAll(ordered);
    }

    public static void applyDialog(Scene scene, String fxml) {
        scene.getStylesheets().removeIf(s -> s.contains("theme-"));
        applyBase(scene);
        addIfAbsent(scene, ThemeManager.cssPathForTheme(ThemeManager.getTheme()));
        addIfAbsent(scene, resolveCssPath(fxml));
    }

    private static void addIfAbsent(Scene scene, String path) {
        if (path == null)
            return;
        URL url = MainApp.class.getResource(path);
        if (url == null) {
            log.warn("[CssLoader] CSS not found: {}", path);
            return;
        }
        String ext = url.toExternalForm();
        if (!scene.getStylesheets().contains(ext)) {
            scene.getStylesheets().add(ext);
        }
    }

    static String resolveCssPath(String fxml) {
        if (fxml == null)
            return null;
        return fxml
                .replace("/fxml/", "/css/")
                .replace(".fxml", ".css");
    }

    private static String toExternalForm(String path) {
        URL url = MainApp.class.getResource(path);
        return url != null ? url.toExternalForm() : null;
    }
}