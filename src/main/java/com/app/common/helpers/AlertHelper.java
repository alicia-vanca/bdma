package com.app.common.helpers;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.app.MainApp;
import com.app.common.utils.StageUtil;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

public final class AlertHelper {

    private static final Logger log = LoggerFactory.getLogger(AlertHelper.class);

    // Dialog text container for title, header, and content
    public record DialogText(String title, String header, String content) {
    }

    // Table column configuration for two-column tables
    public record TableColumns(String col1Name, String col1Property, String col2Name, String col2Property) {
    }

    private static final String LOGO_PATH = "/image/logo.png";

    // UI Layout Constants
    private static final int DIALOG_SPACING = 10;
    private static final int DIALOG_PADDING = 10;
    private static final int TABLE_COLUMN_WIDTH = 300;
    private static final int TABLE_SCROLL_HEIGHT = 400;
    // actual row height after CSS is applied, used for dynamic height calculation
    private static final double TABLE_HEADER_HEIGHT = 41;
    private static final double TABLE_ROW_HEIGHT = 32.5;

    private static final int DIALOG_PREFERRED_WIDTH = 1000;
    private static final int LOGO_SIZE = 48;
    private static final double SCREEN_SIZE_RATIO = 0.8;
    private static final double DIALOG_MIN_WIDTH = 360;
    private static final double DIALOG_HORIZONTAL_PADDING = 150;

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

    public static Alert createError(String title, String header, String content) {
        Alert alert = create(Alert.AlertType.ERROR, title, header, content);
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
     * Show a blocking alert dialog with a scrollable table and custom buttons.
     * Optional callback runs when the primary action is selected.
     */
    public static <T> void showAlertWithTable(DialogText dialogText, List<T> items, TableColumns columns,
            ButtonType primaryButton, ButtonType secondaryButton, Runnable onPrimarySelected) {
        if (items == null || items.isEmpty()) {
            return;
        }

        Platform.runLater(() -> showAlertWithTableNow(dialogText, items, columns, primaryButton, secondaryButton,
                onPrimarySelected));
    }

    /**
     * Shows a blocking table alert on the current thread. Call only from the FX
     * application thread when the caller needs to serialize several dialogs.
     */
    public static <T> void showAlertWithTableNow(DialogText dialogText, List<T> items, TableColumns columns,
            ButtonType primaryButton, ButtonType secondaryButton, Runnable onPrimarySelected) {
        if (items == null || items.isEmpty()) {
            return;
        }

        Alert alert = createInformation(dialogText.title(), dialogText.header(), dialogText.content());
        alert.setResizable(true);

        if (secondaryButton != null) {
            setButtons(alert, primaryButton, secondaryButton);
        } else {
            setButtons(alert, primaryButton);
        }
        TableView<T> table = createTable(items, columns);
        VBox tableContent = createTableContent(dialogText.content(), table);
        alert.getDialogPane().setContent(tableContent);
        alert.getDialogPane().setPrefWidth(DIALOG_PREFERRED_WIDTH);

        var chosen = alert.showAndWait();
        if (primaryButton != null
                && chosen.isPresent()
                && chosen.get() == primaryButton
                && onPrimarySelected != null) {
            onPrimarySelected.run();
        }
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

        // Keep alerts under a screen-relative max while still allowing content-driven
        // width.
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double maxWidth = screenBounds.getWidth() * SCREEN_SIZE_RATIO;
        alert.getDialogPane().setMaxWidth(maxWidth);

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
                applyDynamicContentWidth(alert, stage);
                applyScreenSizeLimit(stage);
            } else if (log.isDebugEnabled()) {
                log.debug("Skip applying app icon: dialog window is not a Stage ({})",
                        scene.getWindow().getClass().getName());
            }
        });
    }

    // Compute dialog width from the longest content line while keeping no-wrap
    // labels.
    @SuppressWarnings("java:S6885")
    private static void applyDynamicContentWidth(Alert alert, Stage stage) {
        String contentText = alert.getContentText();
        if (contentText == null || contentText.isBlank()) {
            return;
        }

        // Preserve custom content nodes (for example table dialogs).
        if (alert.getDialogPane().getContent() != null && !(alert.getDialogPane().getContent() instanceof Label)) {
            return;
        }

        Label contentLabel = new Label(contentText);
        contentLabel.setMinWidth(Region.USE_PREF_SIZE);
        contentLabel.setPrefWidth(Region.USE_COMPUTED_SIZE);
        contentLabel.setWrapText(false);
        contentLabel.setTextOverrun(OverrunStyle.CLIP);
        contentLabel.getStyleClass().add("content");

        alert.getDialogPane().setContent(contentLabel);

        // Ensure CSS metrics are applied before width calculation.
        alert.getDialogPane().applyCss();
        alert.getDialogPane().layout();

        double maxWidth = Screen.getPrimary().getVisualBounds().getWidth() * SCREEN_SIZE_RATIO;
        double measuredContentWidth = contentLabel.prefWidth(-1);

        Region headerLabel = (Region) alert.getDialogPane().lookup(".header-panel .label");
        double measuredHeaderWidth = headerLabel == null ? 0 : headerLabel.prefWidth(-1);

        Region buttonContainer = (Region) alert.getDialogPane().lookup(".button-bar > .container");
        double measuredButtonWidth = buttonContainer == null ? 0 : buttonContainer.prefWidth(-1);

        double preferredWidth = Math.max(Math.max(measuredContentWidth, measuredHeaderWidth), measuredButtonWidth)
                + DIALOG_HORIZONTAL_PADDING;
        double targetWidth = Math.clamp(preferredWidth, DIALOG_MIN_WIDTH, maxWidth);

        alert.getDialogPane().setMinWidth(Region.USE_PREF_SIZE);
        alert.getDialogPane().setPrefWidth(targetWidth);
        alert.getDialogPane().setMaxWidth(maxWidth);
        stage.setMinWidth(Math.min(targetWidth, maxWidth));
        stage.sizeToScene();
    }

    // Limit dialog initial size to 80% of screen dimensions
    private static void applyScreenSizeLimit(Stage stage) {
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double maxWidth = screenBounds.getWidth() * SCREEN_SIZE_RATIO;
        double maxHeight = screenBounds.getHeight() * SCREEN_SIZE_RATIO;

        if (stage.getWidth() > maxWidth) {
            stage.setWidth(maxWidth);
        }
        if (stage.getHeight() > maxHeight) {
            stage.setHeight(maxHeight);
        }
    }

    // Create table with two columns for displaying item details
    private static <T> TableView<T> createTable(List<T> items, TableColumns columns) {
        TableView<T> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<T, String> col1 = new TableColumn<>(columns.col1Name());
        col1.setCellValueFactory(new PropertyValueFactory<>(columns.col1Property()));
        col1.setPrefWidth(TABLE_COLUMN_WIDTH);
        col1.setStyle("-fx-padding: 5 10;");

        TableColumn<T, String> col2 = new TableColumn<>(columns.col2Name());
        col2.setCellValueFactory(new PropertyValueFactory<>(columns.col2Property()));
        col2.setPrefWidth(TABLE_COLUMN_WIDTH);
        col2.setStyle("-fx-padding: 5 10;");

        table.getColumns().add(col1);
        table.getColumns().add(col2);
        table.setRowFactory(tableView -> {
            // Empty rows don't receive mouse events, so they won't get highlighted
            TableRow<T> row = new TableRow<>();
            row.emptyProperty().addListener((observable, wasEmpty, isEmpty) -> row.setMouseTransparent(isEmpty));
            row.setMouseTransparent(row.isEmpty());
            return row;
        });
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
        // Set initial height based on row count, capped at max height
        int rowCount = table.getItems().size();
        double calculatedHeight = TABLE_HEADER_HEIGHT + (rowCount * TABLE_ROW_HEIGHT);
        double initialHeight = Math.min(calculatedHeight, TABLE_SCROLL_HEIGHT);

        table.setPrefHeight(initialHeight);
        table.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);

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
