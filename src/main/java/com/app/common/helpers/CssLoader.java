package com.app.common.helpers;

import com.app.MainApp;
import com.app.common.modules.theme.ThemeManager;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;

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
                || s.contains("/css/common/")
                || s.contains("/css/dev/")
                || s.contains("/css/user/"));
        addIfAbsent(scene, "/css/auth/login.css");
    }

    public static void applyModule(Node node, String fxml) {
        if (!(node instanceof Parent parent)) {
            return;
        }

        String moduleCss = resolveCssPath(fxml);
        if (moduleCss == null)
            return;

        addIfAbsent(parent, moduleCss);
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

    private static void addIfAbsent(Parent parent, String path) {
        if (path == null)
            return;
        URL url = MainApp.class.getResource(path);
        if (url == null) {
            log.warn("[CssLoader] CSS not found: {}", path);
            return;
        }
        String ext = url.toExternalForm();
        if (!parent.getStylesheets().contains(ext)) {
            parent.getStylesheets().add(ext);
        }
    }

    static String resolveCssPath(String fxml) {
        if (fxml == null)
            return null;
        return fxml
                .replace("/fxml/", "/css/")
                .replace(".fxml", ".css");
    }

}
