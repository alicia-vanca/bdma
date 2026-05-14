package com.app.common.modules.queuemanager.controllers;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.admin.layout.controllers.AdminLayoutController;
import com.app.common.events.LanguageChangedEvent;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.queuemanager.dtos.DeviceQueueItem;
import com.app.common.modules.queuemanager.dtos.FileQueueItem;
import com.app.common.modules.queuemanager.enums.ItemStatus;
import com.app.common.modules.queuemanager.events.QueueStatusChangedEvent;
import com.app.common.modules.queuemanager.services.QueueManagerService;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeTableRow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Controller for the queue dialog that displays sync and backup operations.
 */
@Component
public class QueueDialogController {

    private static final Logger log = LoggerFactory.getLogger(QueueDialogController.class);
    private static final String I18N_QUEUE_EMPTY = "queue.empty";

    // Sync tab components
    @FXML
    private TreeTableView<QueueTreeItem> syncTreeTable;
    @FXML
    private TreeTableColumn<QueueTreeItem, String> syncColName;
    @FXML
    private TreeTableColumn<QueueTreeItem, String> syncColStatus;

    // Backup tab components
    @FXML
    private TableView<FileQueueItem> backupTable;
    @FXML
    private TableColumn<FileQueueItem, String> backupColFile;
    @FXML
    private TableColumn<FileQueueItem, String> backupColStatus;
    @FXML
    private Label lblTitle;
    @FXML
    private Tab tabSync;
    @FXML
    private Tab tabBackup;

    private final QueueManagerService queueManagerService;
    private final AdminLayoutController adminLayoutController;

    public QueueDialogController(QueueManagerService queueManagerService, AdminLayoutController adminLayoutController) {
        this.queueManagerService = queueManagerService;
        this.adminLayoutController = adminLayoutController;
    }

    @FXML
    public void initialize() {
        setupSyncTab();
        setupBackupTab();
        refreshAllTabs();
    }

    // ── Sync Tab Setup ───────────────────────────────────────────────────────

    private void setupSyncTab() {
        configureSyncRowFactory();
        configureSyncColumns();
        initializeSyncTreeRoot();
    }

    private void configureSyncRowFactory() {
        syncTreeTable.setRowFactory(tableView -> new SyncDeviceTreeRow());
    }

    private void configureSyncColumns() {
        syncColName.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().getValue().getName()));
        syncColStatus.setCellValueFactory(
                param -> new SimpleStringProperty(formatStatusForDisplay(param.getValue().getValue())));
        syncColStatus.setCellFactory(SyncStatusTreeCell::new);
        syncTreeTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));
    }

    private void initializeSyncTreeRoot() {
        TreeItem<QueueTreeItem> root = new TreeItem<>(new QueueTreeItem());
        root.setExpanded(true);
        syncTreeTable.setRoot(root);
        syncTreeTable.setShowRoot(false);
    }

    private void refreshSyncTab() {
        TreeItem<QueueTreeItem> root = syncTreeTable.getRoot();
        root.getChildren().clear();

        // Create defensive copy to avoid ConcurrentModificationException
        List<DeviceQueueItem> devices = new ArrayList<>(queueManagerService.getAllTrackingSyncDevices());

        for (DeviceQueueItem device : devices) {
            // Create defensive copy of files list
            List<FileQueueItem> files = new ArrayList<>(device.getFiles());

            TreeItem<QueueTreeItem> deviceNode = new TreeItem<>(new QueueTreeItem(device));
            deviceNode.setExpanded(true);

            for (FileQueueItem file : files) {
                TreeItem<QueueTreeItem> fileNode = new TreeItem<>(new QueueTreeItem(file));
                deviceNode.getChildren().add(fileNode);
            }

            root.getChildren().add(deviceNode);
        }

        // Force refresh the TreeTableView
        syncTreeTable.refresh();
    }

    // ── Backup Tab Setup ─────────────────────────────────────────────────────

    private void setupBackupTab() {
        backupColFile.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().getFileName()));
        backupColStatus
                .setCellValueFactory(param -> new SimpleStringProperty(formatStatusWithProgress(param.getValue())));
        backupColStatus.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            private final Label statusLabel = new Label();
            private final Label reasonLabel = new Label();
            private final VBox container = new VBox(1, statusLabel, reasonLabel);

            {
                statusLabel.setWrapText(false);
                reasonLabel.setWrapText(false);
                reasonLabel.setStyle("-fx-text-fill: #c62828; -fx-font-size: 15px;");
                container.setAlignment(Pos.CENTER_LEFT);
                container.setMaxHeight(40);
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

                FileQueueItem rowItem = getTableRow() == null ? null : getTableRow().getItem();
                statusLabel.setText(item);

                if (rowItem != null && hasErrorReason(rowItem.getStatus(), rowItem.getErrorMessage())) {
                    String translatedReason = translateIfKey(rowItem.getErrorMessage());
                    reasonLabel.setText(translatedReason);
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

    private void refreshBackupTab() {
        List<FileQueueItem> files = queueManagerService.getAllTrackingBackupFiles();
        backupTable.setItems(FXCollections.observableArrayList(files));
        backupTable.refresh();
    }

    // ── Event Listeners ──────────────────────────────────────────────────────

    @EventListener
    public void onQueueStatusChanged(QueueStatusChangedEvent event) {
        Platform.runLater(() -> {
            // Skip if dialog not initialized yet
            if (syncTreeTable == null || backupTable == null) {
                return;
            }

            switch (event.getQueueType()) {
                case SYNC -> refreshSyncTab();
                case BACKUP -> refreshBackupTab();
            }
        });
    }

    /**
     * Called by DialogHelper when language changes.
     * Refreshes all tabs to re-translate error messages.
     */
    public void onLanguageChanged(LanguageChangedEvent event) {
        if (log.isDebugEnabled()) {
            log.debug("Queue dialog language changed: {}", event.getLanguageTag());
        }
        Platform.runLater(() -> {
            // Skip if dialog not initialized yet
            if (syncTreeTable == null || backupTable == null) {
                return;
            }

            refreshStaticTexts();
            refreshAllTabs();
        });
    }

    // ── Refresh ──────────────────────────────────────────────────────────────

    public void refreshAllTabs() {
        refreshSyncTab();
        refreshBackupTab();
    }

    /**
     * Re-apply i18n text for static controls because this dialog can stay open
     * while language changes at runtime.
     */
    private void refreshStaticTexts() {
        if (lblTitle != null) {
            lblTitle.setText(I18n.get("queue.dialog.title"));
        }
        if (tabSync != null) {
            tabSync.setText(I18n.get("queue.tab.sync"));
        }
        if (tabBackup != null) {
            tabBackup.setText(I18n.get("queue.tab.backup"));
        }
        if (syncColName != null) {
            syncColName.setText(I18n.get("queue.column.name"));
        }
        if (syncColStatus != null) {
            syncColStatus.setText(I18n.get("queue.column.status"));
        }
        if (backupColFile != null) {
            backupColFile.setText(I18n.get("queue.column.file"));
        }
        if (backupColStatus != null) {
            backupColStatus.setText(I18n.get("queue.column.status"));
        }
        if (syncTreeTable != null) {
            syncTreeTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));
        }
        if (backupTable != null) {
            backupTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));
        }
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    private String formatStatus(ItemStatus status) {
        if (status == null)
            return "";
        return switch (status) {
            case QUEUED -> "⏳ " + I18n.get("queue.status.queued");
            case PROCESSING -> "⟳ " + I18n.get("queue.status.processing");
            case COMPLETED -> "✓ " + I18n.get("queue.status.completed");
            case COMPLETED_WITH_ERRORS -> "⚠ " + I18n.get("queue.status.completed_with_errors");
            case FAILED -> "✕ " + I18n.get("queue.status.failed");
            case SKIPPED -> "⊘ " + I18n.get("queue.status.skipped");
            case CANCELLED -> "✕ " + I18n.get("queue.status.cancelled");
            case DEFERRED -> "⏸ " + I18n.get("queue.status.deferred");
        };
    }

    private String formatStatusForDisplay(QueueTreeItem item) {
        if (item.isDevice()
                && (item.getStatus() == ItemStatus.COMPLETED || item.getStatus() == ItemStatus.COMPLETED_WITH_ERRORS)) {
            return I18n.get("device.sync.summary.content", item.getTotal(), item.getPassed(), item.getFailed());
        }
        return formatStatusWithProgress(item);
    }

    private String formatStatusWithProgress(QueueTreeItem item) {
        String status = formatStatus(item.getStatus());
        if (item.getStatus() == ItemStatus.PROCESSING && item.getProgress() > 0) {
            status += " (" + item.getProgress() + "%)";
        }
        return status;
    }

    private String formatStatusWithProgress(FileQueueItem file) {
        String status = formatStatus(file.getStatus());
        if (file.getStatus() == ItemStatus.PROCESSING && file.getProgress() > 0) {
            status += " (" + file.getProgress() + "%)";
        }
        return status;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasErrorReason(ItemStatus status, String reason) {
        if (!hasText(reason)) {
            return false;
        }
        return status == ItemStatus.FAILED || status == ItemStatus.COMPLETED_WITH_ERRORS
                || status == ItemStatus.DEFERRED;
    }

    /**
     * Translate error message if it's an i18n key, otherwise return as-is.
     * Tries to look up the key; if not found, returns the original string.
     */
    private String translateIfKey(String messageOrKey) {
        if (messageOrKey == null || messageOrKey.isBlank()) {
            return messageOrKey;
        }
        try {
            return I18n.get(messageOrKey);
        } catch (Exception e) {
            // Not a valid key, return original string
            return messageOrKey;
        }
    }

    /**
     * Custom row to keep disclosure arrow vertically centered for non-leaf device
     * rows.
     */
    private static class SyncDeviceTreeRow extends TreeTableRow<QueueTreeItem> {
        @Override
        protected void layoutChildren() {
            super.layoutChildren();

            Node disclosureNode = lookup(".tree-disclosure-node");
            if (disclosureNode == null) {
                return;
            }

            QueueTreeItem item = getItem();
            boolean centerForDevice = item != null && item.isDevice() && getTreeItem() != null
                    && !getTreeItem().isLeaf();
            if (!centerForDevice) {
                disclosureNode.setTranslateY(0);
                return;
            }

            double centeredOffset = (getHeight() - disclosureNode.getLayoutBounds().getHeight()) / 2.0
                    - disclosureNode.getLayoutY();
            disclosureNode.setTranslateY(centeredOffset);
        }
    }

    /**
     * Tree table cell that renders sync status, optional retry action, and optional
     * reason.
     */
    private class SyncStatusTreeCell extends javafx.scene.control.TreeTableCell<QueueTreeItem, String> {
        private final Label statusLabel = new Label();
        private final Label reasonLabel = new Label();
        private final Button retryButton = new Button(I18n.get("common.retry"));
        private final Region statusSpacer = new Region();
        private final HBox statusWithButton = new HBox(10, statusLabel, statusSpacer, retryButton);
        private final VBox container = new VBox(1, statusWithButton, reasonLabel);

        private SyncStatusTreeCell(TreeTableColumn<QueueTreeItem, String> column) {
            statusLabel.setWrapText(false);
            reasonLabel.setWrapText(false);
            reasonLabel.setStyle("-fx-text-fill: #c62828; -fx-font-size: 15px;");
            statusWithButton.setAlignment(Pos.CENTER_LEFT);
            statusWithButton.prefWidthProperty().bind(container.prefWidthProperty());
            container.setAlignment(Pos.CENTER_LEFT);
            container.setMaxHeight(40);
            container.prefWidthProperty().bind(column.widthProperty().subtract(24));
            statusLabel.maxWidthProperty().bind(container.prefWidthProperty());
            reasonLabel.maxWidthProperty().bind(container.prefWidthProperty());
            HBox.setHgrow(statusSpacer, Priority.ALWAYS);
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

            QueueTreeItem rowItem = currentRowItem();
            retryButton.setText(I18n.get("common.retry"));
            statusLabel.setText(item);
            updateRetryButton(rowItem);
            updateReasonLabel(rowItem);

            setText(null);
            setGraphic(container);
        }

        private void onRetryAction() {
            QueueTreeItem rowItem = currentRowItem();
            if (rowItem != null && rowItem.isDevice()) {
                log.debug("Retry button clicked for device: {}", rowItem.getName());
                onRetryDevice(rowItem);
            }
        }

        private void onRetryDevice(QueueTreeItem deviceItem) {
            String hardwareId = deviceItem.getHardwareId();
            adminLayoutController.registerDeviceForSync(hardwareId, deviceItem.getName());
        }

        private QueueTreeItem currentRowItem() {
            TreeItem<QueueTreeItem> treeItem = getTableRow() == null ? null : getTableRow().getTreeItem();
            return treeItem == null ? null : treeItem.getValue();
        }

        private void updateRetryButton(QueueTreeItem rowItem) {
            boolean showRetry = rowItem != null
                    && rowItem.isDevice()
                    && (rowItem.getStatus() == ItemStatus.COMPLETED
                            || rowItem.getStatus() == ItemStatus.COMPLETED_WITH_ERRORS);
            retryButton.setManaged(showRetry);
            retryButton.setVisible(showRetry);
        }

        private void updateReasonLabel(QueueTreeItem rowItem) {
            if (rowItem != null
                    && !rowItem.isDevice()
                    && hasErrorReason(rowItem.getStatus(), rowItem.getReason())) {
                reasonLabel.setText(translateIfKey(rowItem.getReason()));
                reasonLabel.setManaged(true);
                reasonLabel.setVisible(true);
                return;
            }
            reasonLabel.setText(null);
            reasonLabel.setManaged(false);
            reasonLabel.setVisible(false);
        }
    }

    // ── Helper Classes ───────────────────────────────────────────────────────

    /**
     * Tree item wrapper for device/file display in sync tab.
     */
    public static class QueueTreeItem {
        private final DeviceQueueItem device;
        private final FileQueueItem file;

        // Root constructor
        public QueueTreeItem() {
            this.device = null;
            this.file = null;
        }

        // Device constructor
        public QueueTreeItem(DeviceQueueItem device) {
            this.device = device;
            this.file = null;
        }

        // File constructor
        public QueueTreeItem(FileQueueItem file) {
            this.device = null;
            this.file = file;
        }

        public String getName() {
            if (device != null) {
                return device.getDeviceName();
            }
            if (file != null) {
                return file.getFileName();
            }
            return "Root";
        }

        public ItemStatus getStatus() {
            if (device != null) {
                return device.getStatus();
            }
            if (file != null) {
                return file.getStatus();
            }
            return ItemStatus.QUEUED;
        }

        public int getProgress() {
            if (file != null) {
                return file.getProgress();
            }
            return 0;
        }

        public String getReason() {
            if (file != null) {
                return file.getErrorMessage();
            }
            return null;
        }

        public boolean isDevice() {
            return device != null;
        }

        public int getTotal() {
            return device != null ? device.getTotal() : 0;
        }

        public int getPassed() {
            return device != null ? device.getPassed() : 0;
        }

        public int getFailed() {
            return device != null ? device.getFailed() : 0;
        }

        public String getHardwareId() {
            return device != null ? device.getHardwareId() : null;
        }
    }
}
