package com.app.common.utils;

import javafx.scene.image.Image;
import javafx.stage.Stage;

public final class StageUtil {

    private static final String ICON_PATH = "/image/logo.png";

    private StageUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void applyAppIcon(Stage stage) {
        if (stage.getIcons().isEmpty()) {
            stage.getIcons().add(
                    new Image(StageUtil.class.getResourceAsStream(ICON_PATH)));
        }
    }
}