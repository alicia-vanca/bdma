package com.app.common.ui;

import com.app.MainApp;
import com.app.common.css.CssLoader;
import com.app.common.theme.ThemeManager;
import javafx.scene.Parent;
import javafx.scene.Scene;

public class NavigationService {

    private NavigationService() {
    }

    public static void goToLogin(Parent root) {
        Scene scene = MainApp.getScene();
        scene.setRoot(root);
        CssLoader.applyLogin(scene);
        ThemeManager.apply(scene);
    }

    public static void goToAdmin(Parent root) {
        Scene scene = MainApp.getScene();
        scene.setRoot(root);
        CssLoader.applyAdmin(scene);
        ThemeManager.apply(scene);
    }
}