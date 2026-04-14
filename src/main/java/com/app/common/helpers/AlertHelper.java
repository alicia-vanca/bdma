package com.app.common.helpers;

import com.app.MainApp;
import com.app.common.utils.StageUtil;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AlertHelper {

    private static final String LOGO_PATH = "/image/logo.png";

    private AlertHelper() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Alert createConfirmation(String title, String header, String content) {
        Alert alert = create(Alert.AlertType.CONFIRMATION, title, header, content);
        styleButtons(alert, ButtonType.OK, ButtonType.CANCEL);
        return alert;
    }

    public static Alert createInformation(String title, String header, String content) {
        Alert alert = create(Alert.AlertType.INFORMATION, title, header, content);
        styleButtons(alert, ButtonType.OK);
        return alert;
    }

    // Build a preconfigured alert so dialogs stay consistent across modules.
    public static Alert create(Alert.AlertType type, String title, String header, String content) {
        Alert alert = new Alert(type);
        configure(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        return alert;
    }

    // Apply semantic classes for primary and secondary actions.
    public static void styleButtons(Alert alert, ButtonType primaryButton, ButtonType... secondaryButtons) {
        styleButton(alert, primaryButton, "btn-primary");
        if (secondaryButtons == null) {
            return;
        }

        for (ButtonType secondaryButton : secondaryButtons) {
            styleButton(alert, secondaryButton, "btn-secondary");
        }
    }

    // Replace dialog buttons and style them in one call.
    public static void setButtons(Alert alert, ButtonType primaryButton, ButtonType... secondaryButtons) {
        List<ButtonType> buttonTypes = new ArrayList<>();
        if (primaryButton != null) {
            buttonTypes.add(primaryButton);
        }
        if (secondaryButtons != null) {
            Collections.addAll(buttonTypes, secondaryButtons);
        }

        if (!buttonTypes.isEmpty()) {
            alert.getButtonTypes().setAll(buttonTypes);
        }

        styleButtons(alert, primaryButton, secondaryButtons);
    }

    private static void configure(Alert alert) {
        Stage owner = MainApp.getPrimaryStage();
        if (owner != null) {
            alert.initOwner(owner);
        }

        alert.getDialogPane().getStyleClass().add("app-alert");
        alert.setGraphic(createLogoGraphic());
        alert.setOnShown(event -> {
            Stage stage = (Stage) alert.getDialogPane().getScene().getWindow();
            StageUtil.applyAppIcon(stage);
        });
    }

    private static void styleButton(Alert alert, ButtonType buttonType, String styleClass) {
        if (buttonType == null) {
            return;
        }

        Button button = (Button) alert.getDialogPane().lookupButton(buttonType);
        if (button != null && !button.getStyleClass().contains(styleClass)) {
            button.getStyleClass().add(styleClass);
        }
    }

    private static ImageView createLogoGraphic() {
        InputStream logoStream = AlertHelper.class.getResourceAsStream(LOGO_PATH);
        if (logoStream == null) {
            return null;
        }

        Image image = new Image(logoStream);
        ImageView imageView = new ImageView(image);
        imageView.setFitWidth(48);
        imageView.setFitHeight(48);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        return imageView;
    }
}