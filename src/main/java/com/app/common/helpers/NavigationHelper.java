package com.app.common.helpers;

import com.app.MainApp;
import com.app.common.modules.theme.ThemeManager;
import javafx.scene.Parent;
import javafx.scene.Scene;

public class NavigationHelper {

    private NavigationHelper() {
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
    }
}