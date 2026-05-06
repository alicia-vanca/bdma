package com.app.common.helpers;

import com.app.MainApp;
import com.app.common.utils.StageUtil;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.scene.Scene;

public final class AlertHelper {

    private static final Logger log = LoggerFactory.getLogger(AlertHelper.class);

    // Dialog text container for title, header, and content
    public record DialogText(String title, String header, String content) {
    }

    private static final String LOGO_PATH = "/image/logo.png";

    // UI Layout Constants
    private static final int DIALOG_SPACING = 10;
    private static final int DIALOG_PADDING = 10;
    private static final int TABLE_COLUMN_WIDTH = 300;
    private static final int TABLE_SCROLL_HEIGHT = 400;
    private static final int TABLE_ROW_HEIGHT = 32;
    private static final int TABLE_HEADER_HEIGHT = 28;
    private static final int DIALOG_PREFERRED_WIDTH = 700;
    private static final int LOGO_SIZE = 48;

    private AlertHelper() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Alert createConfirmation(String title, String header, String content) {
        Alert alert = create(Alert.AlertType.CONFIRMATION, title, header, content);
        styleButtons(alert, ButtonType.OK, ButtonType.CANCEL);
        return alert;
    }

    public static void showConfirmation(String title, String header, String content, Runnable onDismissed) {
        Platform.runLater(() -> {
            Alert alert = createConfirmation(title, header, content);
            alert.showAndWait();
            if (onDismissed != null) {
                onDismissed.run();
            }
        });
    }

    public static Alert createInformation(String title, String header, String content) {
        Alert alert = create(Alert.AlertType.INFORMATION, title, header, content);
        styleButtons(alert, ButtonType.OK);
        return alert;
    }

    public static void showInformation(String title, String header, String content, Runnable onDismissed) {
        Platform.runLater(() -> {
            Alert alert = createInformation(title, header, content);
            alert.showAndWait();
            if (onDismissed != null) {
                onDismissed.run();
            }
        });
    }

    /**
     * Show a blocking alert dialog with a scrollable table of items.
     * Use this for displaying lists of failed items or detailed reports.
     */
    public static <T> void showAlertWithTable(DialogText dialogText, List<T> items,
            String col1Name, String col1Property, String col2Name, String col2Property) {
        if (items == null || items.isEmpty()) {
            return;
        }

        Platform.runLater(() -> {
            Alert alert = createInformation(dialogText.title(), dialogText.header(), dialogText.content());
            TableView<T> table = createTable(items, col1Name, col1Property, col2Name, col2Property);
            VBox tableContent = createTableContent(dialogText.content(), table);
            alert.getDialogPane().setContent(tableContent);
            alert.getDialogPane().setPrefWidth(DIALOG_PREFERRED_WIDTH);
            alert.showAndWait();
        });
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

    public static Alert createWithScroll(Alert.AlertType type, String title, String header, String content) {
        Alert alert = new Alert(type);
        configure(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);

        // Dùng ScrollPane thay vì contentText
        Label lblContent = new Label(content);
        lblContent.setWrapText(true);
        lblContent.setMaxWidth(500);

        ScrollPane scrollPane = new ScrollPane(lblContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(200);
        scrollPane.setMaxHeight(300);
        scrollPane.setStyle("-fx-background-color: transparent;");

        alert.getDialogPane().setContent(scrollPane);

        return alert;
    }

    // Apply semantic classes for primary and secondary actions.
    public static void styleButtons(Alert alert, ButtonType primaryButton, ButtonType... secondaryButtons) {
        styleButton(alert, primaryButton, "btn-primary");
        if (secondaryButtons == null) {
            return;
        }

        for (ButtonType secondaryButton : secondaryButtons) {
            if (secondaryButton != null) {
                styleButton(alert, secondaryButton, "btn-secondary");
            }
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
            Scene scene = alert.getDialogPane().getScene();
            if (scene == null || scene.getWindow() == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Skip applying app icon: dialog scene/window not available yet");
                }
                return;
            }

            if (scene.getWindow() instanceof Stage stage) {
                StageUtil.applyAppIcon(stage);
            } else if (log.isDebugEnabled()) {
                log.debug("Skip applying app icon: dialog window is not a Stage ({})",
                        scene.getWindow().getClass().getName());
            }
        });
    }

    // Create table with two columns for displaying item details
    private static <T> TableView<T> createTable(List<T> items, String col1Name, String col1Property,
            String col2Name, String col2Property) {
        TableView<T> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<T, String> col1 = new TableColumn<>(col1Name);
        col1.setCellValueFactory(new PropertyValueFactory<>(col1Property));
        col1.setPrefWidth(TABLE_COLUMN_WIDTH);

        TableColumn<T, String> col2 = new TableColumn<>(col2Name);
        col2.setCellValueFactory(new PropertyValueFactory<>(col2Property));
        col2.setPrefWidth(TABLE_COLUMN_WIDTH);

        table.getColumns().add(col1);
        table.getColumns().add(col2);
        table.getItems().addAll(items);

        return table;
    }

    // Create content layout with optional header and scrollable table
    private static VBox createTableContent(String header, TableView<?> table) {
        VBox content = new VBox(DIALOG_SPACING);
        content.setPadding(new Insets(DIALOG_PADDING));

        if (header != null && !header.isBlank()) {
            Label headerLabel = new Label(header);
            headerLabel.setStyle("-fx-font-weight: bold;");
            content.getChildren().add(headerLabel);
        }

        // Calculate dynamic height based on number of rows, capped at max height
        int rowCount = table.getItems().size();
        int calculatedHeight = TABLE_HEADER_HEIGHT + (rowCount * TABLE_ROW_HEIGHT) + rowCount; // +1px border per row
        int tableHeight = Math.min(calculatedHeight, TABLE_SCROLL_HEIGHT);

        table.setPrefHeight(tableHeight);

        content.getChildren().add(table);
        return content;
    }

    private static void styleButton(Alert alert, ButtonType buttonType, String styleClass) {
        if (buttonType == null) {
            return;
        }

        var node = alert.getDialogPane().lookupButton(buttonType);
        if (node instanceof Button button && !button.getStyleClass().contains(styleClass)) {
            button.getStyleClass().add(styleClass);
        }
    }

    private static ImageView createLogoGraphic() {
        try (InputStream logoStream = AlertHelper.class.getResourceAsStream(LOGO_PATH)) {
            if (logoStream == null) {
                return null;
            }
            Image image = new Image(logoStream, LOGO_SIZE, LOGO_SIZE, true, true);
            ImageView imageView = new ImageView(image);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);
            return imageView;
        } catch (IOException e) {
            log.warn("Failed to load logo graphic from {}: {}", LOGO_PATH, e.getMessage());
            return null;
        }
    }
}
