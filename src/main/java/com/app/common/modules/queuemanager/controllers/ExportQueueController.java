package com.app.common.modules.queuemanager.controllers;

import static com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper.I18N_COMMON_RETRY;
import static com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper.I18N_QUEUE_COLUMN_STATUS;
import static com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper.I18N_QUEUE_EMPTY;
import static com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper.STYLE_LAST_CELL;
import static com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper.STYLE_QUEUE_CELL_CONTENT;
import static com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper.STYLE_REASON_LABEL;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.common.modules.dataexport.services.DataExportService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.queuemanager.dtos.ExportDirectoryQueueItem;
import com.app.common.modules.queuemanager.dtos.ExportQueueRowItem;
import com.app.common.modules.queuemanager.dtos.FileQueueItem;
import com.app.common.modules.queuemanager.enums.ItemStatus;
import com.app.common.modules.queuemanager.helpers.GroupNodeTreeRow;
import com.app.common.modules.queuemanager.helpers.QueueStatusFormatHelper;
import com.app.common.modules.queuemanager.services.QueueManagerService;

import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Controller for the export queue tab.
 */
@Component
public class ExportQueueController {

    private static final Logger log = LoggerFactory.getLogger(ExportQueueController.class);

    @FXML
    private TreeTableView<ExportQueueRowItem> exportTreeTable;
    @FXML
    private TreeTableColumn<ExportQueueRowItem, String> exportColName;
    @FXML
    private TreeTableColumn<ExportQueueRowItem, String> exportColStatus;

    private final QueueManagerService queueManagerService;
    private final DataExportService dataExportService;

    public ExportQueueController(QueueManagerService queueManagerService, DataExportService dataExportService) {
        this.queueManagerService = queueManagerService;
        this.dataExportService = dataExportService;
    }

    @FXML
    public void initialize() {
        exportTreeTable.setRowFactory(tableView -> new GroupNodeTreeRow<>(ExportQueueRowItem::isDirectory));
        exportColName.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().getValue().getName()));
        exportColStatus.setCellValueFactory(
                param -> new SimpleStringProperty(formatStatusForDisplay(param.getValue().getValue())));
        exportColStatus.setCellFactory(ExportStatusTreeCell::new);
        exportColStatus.getStyleClass().add(STYLE_LAST_CELL);
        exportTreeTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));

        TreeItem<ExportQueueRowItem> root = new TreeItem<>(new ExportQueueRowItem());
        root.setExpanded(true);
        exportTreeTable.setRoot(root);
        exportTreeTable.setShowRoot(false);
    }

    public boolean isInitialized() {
        return exportTreeTable != null;
    }

    public void refresh() {
        TreeItem<ExportQueueRowItem> root = exportTreeTable.getRoot();
        List<TreeItem<ExportQueueRowItem>> directoryNodes = new ArrayList<>();
        List<ExportDirectoryQueueItem> directories = new ArrayList<>(
                queueManagerService.getAllTrackingExportDirectories());

        for (ExportDirectoryQueueItem directory : directories) {
            TreeItem<ExportQueueRowItem> dirNode = new TreeItem<>(new ExportQueueRowItem(directory));
            dirNode.setExpanded(!QueueStatusFormatHelper.isFinishedStatus(directory.getStatus()));
            for (FileQueueItem file : new ArrayList<>(directory.getFiles())) {
                dirNode.getChildren().add(new TreeItem<>(new ExportQueueRowItem(file)));
            }
            directoryNodes.add(dirNode);
        }

        // Replace rows in one notification so JavaFX selection state is less likely to
        // observe a transient empty tree while a mouse selection is being processed.
        root.getChildren().setAll(directoryNodes);
    }

    public void refreshStaticTexts() {
        exportColName.setText(I18n.get("queue.column.name"));
        exportColStatus.setText(I18n.get(I18N_QUEUE_COLUMN_STATUS));
        exportTreeTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));
    }

    /**
     * Repaints, appends, or updates an export row without rebuilding the whole
     * tree.
     */
    public void refreshRow(String rowId, String rootRowId) {
        if (rootRowId != null) {
            refreshFileRow(rowId, rootRowId);
            return;
        }

        refreshDirectoryRow(rowId);
    }

    private void refreshDirectoryRow(String directoryPath) {
        TreeItem<ExportQueueRowItem> row = findDirectoryRow(directoryPath);
        if (row != null) {
            row.setValue(row.getValue().copy());
            return;
        }

        refresh();
    }

    private void refreshFileRow(String filePath, String directoryPath) {
        TreeItem<ExportQueueRowItem> fileRow = findChildRow(directoryPath, filePath);
        if (fileRow != null) {
            fileRow.setValue(fileRow.getValue().copy());
            refreshDirectoryRow(directoryPath);
            return;
        }

        TreeItem<ExportQueueRowItem> directoryRow = findDirectoryRow(directoryPath);
        FileQueueItem file = queueManagerService.getTrackingExportFile(filePath);
        if (directoryRow != null && file != null) {
            directoryRow.getChildren().add(new TreeItem<>(new ExportQueueRowItem(file)));
            directoryRow.setValue(directoryRow.getValue().copy());
            return;
        }

        refresh();
    }

    private TreeItem<ExportQueueRowItem> findDirectoryRow(String directoryPath) {
        if (directoryPath == null) {
            return null;
        }
        for (TreeItem<ExportQueueRowItem> directoryNode : exportTreeTable.getRoot().getChildren()) {
            if (directoryPath.equals(directoryNode.getValue().getRowId())) {
                return directoryNode;
            }
        }
        return null;
    }

    private TreeItem<ExportQueueRowItem> findChildRow(String directoryPath, String filePath) {
        TreeItem<ExportQueueRowItem> directoryNode = findDirectoryRow(directoryPath);
        if (directoryNode == null || filePath == null) {
            return null;
        }
        for (TreeItem<ExportQueueRowItem> fileNode : directoryNode.getChildren()) {
            if (filePath.equals(fileNode.getValue().getRowId())) {
                return fileNode;
            }
        }
        return null;
    }

    private String formatStatusForDisplay(ExportQueueRowItem item) {
        if (item.isDirectory()) {
            return QueueStatusFormatHelper.formatRootStatusSummary(
                    item.getStatus(), item.getTotal(), item.getPassed(), item.getFailed());
        }
        return QueueStatusFormatHelper.formatStatusWithProgress(item.getStatus(), item.getProgress());
    }

    /**
     * Renders export status, optional directory retry button, and optional reason.
     */
    private class ExportStatusTreeCell extends javafx.scene.control.TreeTableCell<ExportQueueRowItem, String> {
        private final Label statusLabel = new Label();
        private final Label reasonLabel = new Label();
        private final Button retryButton = new Button(I18n.get(I18N_COMMON_RETRY));
        private final Region statusSpacer = new Region();
        private final HBox statusWithButton = new HBox(10, statusLabel, statusSpacer, retryButton);
        private final VBox container = new VBox(1, statusWithButton, reasonLabel);

        private ExportStatusTreeCell(TreeTableColumn<ExportQueueRowItem, String> column) {
            getStyleClass().add(STYLE_LAST_CELL);
            container.getStyleClass().add(STYLE_QUEUE_CELL_CONTENT);
            statusLabel.setWrapText(false);
            reasonLabel.setWrapText(false);
            reasonLabel.setStyle(STYLE_REASON_LABEL);
            statusWithButton.setAlignment(Pos.CENTER_LEFT);
            statusWithButton.prefWidthProperty().bind(container.prefWidthProperty());
            container.setAlignment(Pos.CENTER_LEFT);
            container.prefWidthProperty().bind(column.widthProperty().subtract(24));
            statusLabel.maxWidthProperty().bind(container.prefWidthProperty());
            reasonLabel.maxWidthProperty().bind(container.prefWidthProperty());
            HBox.setHgrow(statusSpacer, Priority.ALWAYS);
            retryButton.getStyleClass().add("btn-primary-small");
            retryButton.setOnAction(e -> onRetryAction());
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            ExportQueueRowItem rowItem = currentRowItem();
            retryButton.setText(I18n.get(I18N_COMMON_RETRY));
            statusLabel.setText(item);
            updateRetryButton(rowItem);
            updateReasonLabel(rowItem);

            setText(null);
            setGraphic(container);
        }

        private void onRetryAction() {
            ExportQueueRowItem rowItem = currentRowItem();
            if (rowItem != null && rowItem.isDirectory() && rowItem.getStatus() == ItemStatus.COMPLETED_WITH_ERRORS) {
                log.debug("Retry button clicked for export dir: {}", rowItem.getName());
                dataExportService.retryFailedDirectoryFiles(rowItem.getRowId());
            }
        }

        private ExportQueueRowItem currentRowItem() {
            if (getTableRow() == null) {
                return null;
            }
            TreeItem<ExportQueueRowItem> treeItem = getTableRow().getTreeItem();
            return treeItem != null ? treeItem.getValue() : null;
        }

        private void updateRetryButton(ExportQueueRowItem rowItem) {
            boolean showRetry = rowItem != null
                    && rowItem.isDirectory()
                    && rowItem.getStatus() == ItemStatus.COMPLETED_WITH_ERRORS;
            retryButton.setManaged(showRetry);
            retryButton.setVisible(showRetry);
        }

        private void updateReasonLabel(ExportQueueRowItem rowItem) {
            // Directory nodes group files; they don't carry their own error message.
            if (rowItem != null && !rowItem.isDirectory()
                    && QueueStatusFormatHelper.hasErrorReason(rowItem.getStatus(), rowItem.getReason())) {
                reasonLabel.setText(QueueStatusFormatHelper.translateIfKey(rowItem.getReason()));
                reasonLabel.setManaged(true);
                reasonLabel.setVisible(true);
                return;
            }
            reasonLabel.setText(null);
            reasonLabel.setManaged(false);
            reasonLabel.setVisible(false);
        }
    }
}
