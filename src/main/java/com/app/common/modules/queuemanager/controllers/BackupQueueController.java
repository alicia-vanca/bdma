package com.app.common.modules.queuemanager.controllers;

import static com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper.I18N_QUEUE_COLUMN_STATUS;
import static com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper.I18N_QUEUE_EMPTY;
import static com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper.STYLE_LAST_CELL;
import static com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper.STYLE_QUEUE_CELL_CONTENT;
import static com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper.STYLE_REASON_LABEL;

import java.util.List;

import org.springframework.stereotype.Component;

import com.app.common.modules.i18n.I18n;
import com.app.common.modules.queuemanager.dtos.FileQueueItem;
import com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper;
import com.app.common.modules.queuemanager.services.QueueManagerService;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

/**
 * Controller for the backup queue tab.
 */
@Component
public class BackupQueueController {

    @FXML
    private TableView<FileQueueItem> backupTable;
    @FXML
    private TableColumn<FileQueueItem, String> backupColFile;
    @FXML
    private TableColumn<FileQueueItem, String> backupColStatus;

    private final QueueManagerService queueManagerService;

    public BackupQueueController(QueueManagerService queueManagerService) {
        this.queueManagerService = queueManagerService;
    }

    @FXML
    public void initialize() {
        backupColFile.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().getFileName()));
        backupColStatus.setCellValueFactory(
                param -> new SimpleStringProperty(QueueStatusFormatHelper.formatStatusWithProgress(param.getValue())));
        backupColStatus.getStyleClass().add(STYLE_LAST_CELL);
        backupColStatus.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            private final Label statusLabel = new Label();
            private final Label reasonLabel = new Label();
            private final VBox container = new VBox(1, statusLabel, reasonLabel);

            {
                getStyleClass().add(STYLE_LAST_CELL);
                container.getStyleClass().add(STYLE_QUEUE_CELL_CONTENT);
                statusLabel.setWrapText(false);
                reasonLabel.setWrapText(false);
                reasonLabel.setStyle(STYLE_REASON_LABEL);
                container.setAlignment(Pos.CENTER_LEFT);
                container.prefWidthProperty().bind(column.widthProperty().subtract(24));
                statusLabel.maxWidthProperty().bind(container.prefWidthProperty());
                reasonLabel.maxWidthProperty().bind(container.prefWidthProperty());
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                FileQueueItem rowItem = null;
                if (getTableRow() != null) {
                    rowItem = getTableRow().getItem();
                }
                statusLabel.setText(item);

                if (rowItem != null
                        && QueueStatusFormatHelper.hasErrorReason(rowItem.getStatus(), rowItem.getErrorMessage())) {
                    reasonLabel.setText(QueueStatusFormatHelper.translateIfKey(rowItem.getErrorMessage()));
                    reasonLabel.setManaged(true);
                    reasonLabel.setVisible(true);
                } else {
                    reasonLabel.setText(null);
                    reasonLabel.setManaged(false);
                    reasonLabel.setVisible(false);
                }

                setText(null);
                setGraphic(container);
            }
        });
        backupTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));
    }

    public boolean isInitialized() {
        return backupTable != null;
    }

    public void refresh() {
        List<FileQueueItem> files = queueManagerService.getAllTrackingBackupFiles();
        backupTable.setItems(FXCollections.observableArrayList(files));
        backupTable.refresh();
    }

    /**
     * Repaints or appends a changed backup row without replacing the whole table.
     */
    public void refreshRow(String filePath) {
        if (filePath == null) {
            refresh();
            return;
        }

        for (int index = 0; index < backupTable.getItems().size(); index++) {
            FileQueueItem file = backupTable.getItems().get(index);
            if (filePath.equals(file.getRowId())) {
                backupTable.getItems().set(index, file);
                return;
            }
        }

        FileQueueItem file = queueManagerService.getTrackingBackupFile(filePath);
        if (file != null) {
            backupTable.getItems().add(file);
        }
    }

    public void refreshStaticTexts() {
        backupColFile.setText(I18n.get("queue.column.file"));
        backupColStatus.setText(I18n.get(I18N_QUEUE_COLUMN_STATUS));
        backupTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));
    }
}
