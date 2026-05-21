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

import com.app.admin.layout.controllers.AdminLayoutController;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.queuemanager.dtos.DeviceQueueItem;
import com.app.common.modules.queuemanager.dtos.FileQueueItem;
import com.app.common.modules.queuemanager.dtos.SyncQueueRowItem;
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
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Controller for the sync queue tab.
 */
@Component
public class SyncQueueController {

    private static final Logger log = LoggerFactory.getLogger(SyncQueueController.class);

    @FXML
    private TreeTableView<SyncQueueRowItem> syncTreeTable;
    @FXML
    private TreeTableColumn<SyncQueueRowItem, String> syncColName;
    @FXML
    private TreeTableColumn<SyncQueueRowItem, String> syncColStatus;

    private final QueueManagerService queueManagerService;
    private final AdminLayoutController adminLayoutController;

    public SyncQueueController(QueueManagerService queueManagerService, AdminLayoutController adminLayoutController) {
        this.queueManagerService = queueManagerService;
        this.adminLayoutController = adminLayoutController;
    }

    @FXML
    public void initialize() {
        syncTreeTable.setRowFactory(tableView -> new GroupNodeTreeRow<>(SyncQueueRowItem::isDevice));
        syncColName.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().getValue().getName()));
        syncColStatus.setCellValueFactory(
                param -> new SimpleStringProperty(formatStatusForDisplay(param.getValue().getValue())));
        syncColStatus.setCellFactory(SyncStatusTreeCell::new);
        syncColStatus.getStyleClass().add(STYLE_LAST_CELL);
        syncTreeTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));

        TreeItem<SyncQueueRowItem> root = new TreeItem<>(new SyncQueueRowItem());
        root.setExpanded(true);
        syncTreeTable.setRoot(root);
        syncTreeTable.setShowRoot(false);
    }

    public boolean isInitialized() {
        return syncTreeTable != null;
    }

    public void refresh() {
        TreeItem<SyncQueueRowItem> root = syncTreeTable.getRoot();
        List<TreeItem<SyncQueueRowItem>> deviceNodes = new ArrayList<>();
        List<DeviceQueueItem> devices = new ArrayList<>(queueManagerService.getAllTrackingSyncDevices());

        for (DeviceQueueItem device : devices) {
            List<FileQueueItem> files = new ArrayList<>(device.getFiles());
            TreeItem<SyncQueueRowItem> deviceNode = new TreeItem<>(new SyncQueueRowItem(device));
            deviceNode.setExpanded(!QueueStatusFormatHelper.isFinishedStatus(device.getStatus()));

            for (FileQueueItem file : files) {
                deviceNode.getChildren().add(new TreeItem<>(new SyncQueueRowItem(file)));
            }

            deviceNodes.add(deviceNode);
        }

        // Replace rows in one notification so JavaFX selection state is less likely to
        // observe a transient empty tree while a mouse selection is being processed.
        root.getChildren().setAll(deviceNodes);
    }

    public void refreshStaticTexts() {
        syncColName.setText(I18n.get("queue.column.name"));
        syncColStatus.setText(I18n.get(I18N_QUEUE_COLUMN_STATUS));
        syncTreeTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));
    }

    /**
     * Repaints, appends, or updates a sync row without rebuilding the whole tree.
     */
    public void refreshRow(String rowId, String rootRowId) {
        if (rootRowId != null) {
            refreshFileRow(rowId, rootRowId);
            return;
        }

        refreshDeviceRow(rowId);
    }

    private void refreshDeviceRow(String rootRowId) {
        TreeItem<SyncQueueRowItem> row = findDeviceRow(rootRowId);
        if (row != null) {
            row.setValue(row.getValue().copy());
            return;
        }

        DeviceQueueItem device = queueManagerService.getTrackingSyncDevice(rootRowId);
        if (device != null) {
            TreeItem<SyncQueueRowItem> deviceNode = new TreeItem<>(new SyncQueueRowItem(device));
            deviceNode.setExpanded(!QueueStatusFormatHelper.isFinishedStatus(device.getStatus()));
            syncTreeTable.getRoot().getChildren().add(deviceNode);
            return;
        }

        refresh();
    }

    private void refreshFileRow(String filePath, String rootRowId) {
        TreeItem<SyncQueueRowItem> fileRow = findChildRow(rootRowId, filePath);
        if (fileRow != null) {
            fileRow.setValue(fileRow.getValue().copy());
            refreshDeviceRow(rootRowId);
            return;
        }

        TreeItem<SyncQueueRowItem> deviceRow = findDeviceRow(rootRowId);
        FileQueueItem file = queueManagerService.getTrackingSyncFiles(rootRowId).stream()
                .filter(syncFile -> filePath.equals(syncFile.getRowId()))
                .findFirst()
                .orElse(null);
        if (deviceRow != null && file != null) {
            deviceRow.getChildren().add(new TreeItem<>(new SyncQueueRowItem(file)));
            deviceRow.setValue(deviceRow.getValue().copy());
            return;
        }

        refresh();
    }

    private TreeItem<SyncQueueRowItem> findDeviceRow(String rootRowId) {
        if (rootRowId == null) {
            return null;
        }
        for (TreeItem<SyncQueueRowItem> deviceNode : syncTreeTable.getRoot().getChildren()) {
            if (rootRowId.equals(deviceNode.getValue().getRowId())) {
                return deviceNode;
            }
        }
        return null;
    }

    private TreeItem<SyncQueueRowItem> findChildRow(String rootRowId, String filePath) {
        TreeItem<SyncQueueRowItem> deviceNode = findDeviceRow(rootRowId);
        if (deviceNode == null || filePath == null) {
            return null;
        }
        for (TreeItem<SyncQueueRowItem> fileNode : deviceNode.getChildren()) {
            if (filePath.equals(fileNode.getValue().getRowId())) {
                return fileNode;
            }
        }
        return null;
    }

    private String formatStatusForDisplay(SyncQueueRowItem item) {
        if (item.isDevice()) {
            return QueueStatusFormatHelper.formatRootStatusSummary(
                    item.getStatus(), item.getTotal(), item.getPassed(), item.getFailed());
        }
        return QueueStatusFormatHelper.formatStatusWithProgress(item.getStatus(), item.getProgress());
    }

    /**
     * Renders sync status, optional retry action, and optional file-level reason.
     */
    private class SyncStatusTreeCell extends TreeTableCell<SyncQueueRowItem, String> {
        private final Label statusLabel = new Label();
        private final Label reasonLabel = new Label();
        private final Button retryButton = new Button(I18n.get(I18N_COMMON_RETRY));
        private final Region statusSpacer = new Region();
        private final HBox statusWithButton = new HBox(10, statusLabel, statusSpacer, retryButton);
        private final VBox container = new VBox(1, statusWithButton, reasonLabel);

        private SyncStatusTreeCell(TreeTableColumn<SyncQueueRowItem, String> column) {
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

            SyncQueueRowItem rowItem = currentRowItem();
            retryButton.setText(I18n.get(I18N_COMMON_RETRY));
            statusLabel.setText(item);
            updateRetryButton(rowItem);
            updateReasonLabel(rowItem);

            setText(null);
            setGraphic(container);
        }

        private void onRetryAction() {
            SyncQueueRowItem rowItem = currentRowItem();
            if (rowItem != null && rowItem.isDevice()) {
                log.debug("Retry button clicked for device: {}", rowItem.getName());
                adminLayoutController.registerDeviceForSync(rowItem.getCameraId());
            }
        }

        private SyncQueueRowItem currentRowItem() {
            TreeItem<SyncQueueRowItem> treeItem = null;
            if (getTableRow() != null) {
                treeItem = getTableRow().getTreeItem();
            }
            return treeItem != null ? treeItem.getValue() : null;
        }

        private void updateRetryButton(SyncQueueRowItem rowItem) {
            boolean showRetry = rowItem != null
                    && rowItem.isDevice()
                    && rowItem.getStatus() == ItemStatus.COMPLETED_WITH_ERRORS
                    && rowItem.getCameraId() != null;
            retryButton.setManaged(showRetry);
            retryButton.setVisible(showRetry);
        }

        private void updateReasonLabel(SyncQueueRowItem rowItem) {
            if (rowItem != null
                    && !rowItem.isDevice()
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
