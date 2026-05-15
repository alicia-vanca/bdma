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
import com.app.common.modules.queuemanager.dtos.ExportDirectoryQueueItem;
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
import javafx.scene.control.TabPane;
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
    private static final String I18N_QUEUE_COLUMN_STATUS = "queue.column.status";
    private static final String STYLE_LAST_CELL = "last-cell";
    private static final String STYLE_REASON_LABEL = "-fx-text-fill: #c62828; -fx-font-size: 13px;";

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
    private TabPane tabPane;
    @FXML
    private Tab tabSync;
    @FXML
    private Tab tabBackup;
    @FXML
    private TreeTableView<QueueTreeItem> exportTreeTable;
    @FXML
    private TreeTableColumn<QueueTreeItem, String> exportColName;
    @FXML
    private TreeTableColumn<QueueTreeItem, String> exportColStatus;
    @FXML
    private Tab tabExport;

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
        setupExportTab();
        hideBackupTabByDefault();
        refreshAllTabs();
    }

    /**
     * Keep the backup queue available to the controller while removing its tab from
     * the default dialog layout.
     */
    private void hideBackupTabByDefault() {
        if (tabPane != null && tabBackup != null) {
            tabPane.getTabs().remove(tabBackup);
        }
    }

    // ── Sync Tab Setup ───────────────────────────────────────────────────────

    private void setupSyncTab() {
        configureSyncRowFactory();
        configureSyncColumns();
        initializeSyncTreeRoot();
    }

    private void configureSyncRowFactory() {
        syncTreeTable.setRowFactory(tableView -> new GroupNodeTreeRow());
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
            deviceNode.setExpanded(!isFinishedStatus(device.getStatus()));

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
                getStyleClass().add(STYLE_LAST_CELL);
                statusLabel.setWrapText(false);
                reasonLabel.setWrapText(false);
                reasonLabel.setStyle(STYLE_REASON_LABEL);
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

                FileQueueItem rowItem = null;
                if (getTableRow() != null) {
                    rowItem = getTableRow().getItem();
                }
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

    // ── Export Tab Setup ─────────────────────────────────────────────────────

    private void setupExportTab() {
        exportTreeTable.setRowFactory(tableView -> new GroupNodeTreeRow());
        exportColName.setCellValueFactory(
                param -> new SimpleStringProperty(param.getValue().getValue().getName()));
        exportColStatus.setCellValueFactory(
                param -> new SimpleStringProperty(formatStatusForDisplay(param.getValue().getValue())));
        exportColStatus.setCellFactory(ExportStatusTreeCell::new);
        exportTreeTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));
        TreeItem<QueueTreeItem> root = new TreeItem<>(new QueueTreeItem());
        root.setExpanded(true);
        exportTreeTable.setRoot(root);
        exportTreeTable.setShowRoot(false);
    }

    private void refreshExportTab() {
        TreeItem<QueueTreeItem> root = exportTreeTable.getRoot();
        root.getChildren().clear();

        // Defensive copy to avoid ConcurrentModificationException.
        List<ExportDirectoryQueueItem> directories = new ArrayList<>(
                queueManagerService.getAllTrackingExportDirectories());

        for (ExportDirectoryQueueItem directory : directories) {
            TreeItem<QueueTreeItem> dirNode = new TreeItem<>(new QueueTreeItem(directory));
            dirNode.setExpanded(!isFinishedStatus(directory.getStatus()));
            for (FileQueueItem file : new ArrayList<>(directory.getFiles())) {
                dirNode.getChildren().add(new TreeItem<>(new QueueTreeItem(file)));
            }
            root.getChildren().add(dirNode);
        }

        exportTreeTable.refresh();
    }

    // ── Event Listeners ──────────────────────────────────────────────────────

    @EventListener
    public void onQueueStatusChanged(QueueStatusChangedEvent event) {
        Platform.runLater(() -> {
            // Skip if dialog not initialized yet
            if (syncTreeTable == null || backupTable == null || exportTreeTable == null) {
                return;
            }

            switch (event.getQueueType()) {
                case SYNC -> refreshSyncTab();
                case BACKUP -> refreshBackupTab();
                case EXPORT -> refreshExportTab();
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
            if (syncTreeTable == null || backupTable == null || exportTreeTable == null) {
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
        refreshExportTab();
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
        if (tabExport != null) {
            tabExport.setText(I18n.get("queue.tab.export"));
        }
        if (syncColName != null) {
            syncColName.setText(I18n.get("queue.column.name"));
        }
        if (syncColStatus != null) {
            syncColStatus.setText(I18n.get(I18N_QUEUE_COLUMN_STATUS));
        }
        if (backupColFile != null) {
            backupColFile.setText(I18n.get("queue.column.file"));
        }
        if (backupColStatus != null) {
            backupColStatus.setText(I18n.get(I18N_QUEUE_COLUMN_STATUS));
        }
        if (exportColName != null) {
            exportColName.setText(I18n.get("queue.column.name"));
        }
        if (exportColStatus != null) {
            exportColStatus.setText(I18n.get(I18N_QUEUE_COLUMN_STATUS));
        }
        if (syncTreeTable != null) {
            syncTreeTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));
        }
        if (backupTable != null) {
            backupTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));
        }
        if (exportTreeTable != null) {
            exportTreeTable.setPlaceholder(new Label(I18n.get(I18N_QUEUE_EMPTY)));
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
        if ((item.isDevice() || item.isExportDir())
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
                || status == ItemStatus.DEFERRED || status == ItemStatus.SKIPPED;
    }

    private boolean isFinishedStatus(ItemStatus status) {
        return status == ItemStatus.COMPLETED || status == ItemStatus.COMPLETED_WITH_ERRORS
                || status == ItemStatus.FAILED || status == ItemStatus.CANCELLED
                || status == ItemStatus.SKIPPED;
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
     * Custom row to keep disclosure arrow vertically centered for sync and export
     * group rows that can expand into file children.
     */
    private static class GroupNodeTreeRow extends TreeTableRow<QueueTreeItem> {
        @Override
        protected void layoutChildren() {
            super.layoutChildren();

            Node disclosureNode = lookup(".tree-disclosure-node");
            if (disclosureNode == null) {
                return;
            }

            QueueTreeItem item = getItem();
            boolean centerForGroupNode = item != null && (item.isDevice() || item.isExportDir())
                    && getTreeItem() != null && !getTreeItem().isLeaf();
            if (!centerForGroupNode) {
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
            getStyleClass().add(STYLE_LAST_CELL);
            statusLabel.setWrapText(false);
            reasonLabel.setWrapText(false);
            reasonLabel.setStyle(STYLE_REASON_LABEL);
            statusWithButton.setAlignment(Pos.CENTER_LEFT);
            statusWithButton.prefWidthProperty().bind(container.prefWidthProperty());
            container.setAlignment(Pos.CENTER_LEFT);
            container.setMaxHeight(40);
            container.prefWidthProperty().bind(column.widthProperty().subtract(24));
            statusLabel.maxWidthProperty().bind(container.prefWidthProperty());
            reasonLabel.maxWidthProperty().bind(container.prefWidthProperty());
            HBox.setHgrow(statusSpacer, Priority.ALWAYS);
            retryButton.getStyleClass().add("btn-action");
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
            TreeItem<QueueTreeItem> treeItem = null;
            if (getTableRow() != null) {
                treeItem = getTableRow().getTreeItem();
            }
            if (treeItem == null) {
                return null;
            }
            return treeItem.getValue();
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

    /**
     * Tree table cell that renders export status, optional directory retry action,
     * and optional file-level failure reason.
     */
    private class ExportStatusTreeCell extends javafx.scene.control.TreeTableCell<QueueTreeItem, String> {
        private final Label statusLabel = new Label();
        private final Label reasonLabel = new Label();
        private final Button retryButton = new Button(I18n.get("common.retry"));
        private final Region statusSpacer = new Region();
        private final HBox statusWithButton = new HBox(10, statusLabel, statusSpacer, retryButton);
        private final VBox container = new VBox(1, statusWithButton, reasonLabel);

        private ExportStatusTreeCell(TreeTableColumn<QueueTreeItem, String> column) {
            getStyleClass().add(STYLE_LAST_CELL);
            statusLabel.setWrapText(false);
            reasonLabel.setWrapText(false);
            reasonLabel.setStyle(STYLE_REASON_LABEL);
            statusWithButton.setAlignment(Pos.CENTER_LEFT);
            statusWithButton.prefWidthProperty().bind(container.prefWidthProperty());
            container.setAlignment(Pos.CENTER_LEFT);
            container.setMaxHeight(40);
            container.prefWidthProperty().bind(column.widthProperty().subtract(24));
            statusLabel.maxWidthProperty().bind(container.prefWidthProperty());
            reasonLabel.maxWidthProperty().bind(container.prefWidthProperty());
            HBox.setHgrow(statusSpacer, Priority.ALWAYS);
            retryButton.getStyleClass().add("btn-action");
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
            if (rowItem != null && rowItem.isExportDir() && rowItem.getRetryAction() != null) {
                log.debug("Retry button clicked for export dir: {}", rowItem.getName());
                rowItem.getRetryAction().run();
            }
        }

        private QueueTreeItem currentRowItem() {
            if (getTableRow() == null) {
                return null;
            }
            TreeItem<QueueTreeItem> treeItem = getTableRow().getTreeItem();
            return treeItem != null ? treeItem.getValue() : null;
        }

        private void updateRetryButton(QueueTreeItem rowItem) {
            boolean showRetry = rowItem != null
                    && rowItem.isExportDir()
                    && rowItem.getStatus() == ItemStatus.COMPLETED_WITH_ERRORS
                    && rowItem.getRetryAction() != null;
            retryButton.setManaged(showRetry);
            retryButton.setVisible(showRetry);
        }

        private void updateReasonLabel(QueueTreeItem rowItem) {
            // Dir nodes group files; they don't carry their own error message.
            if (rowItem != null && !rowItem.isExportDir() && !rowItem.isDevice()
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
     * Tree item wrapper for device/file display in sync/export tabs.
     */
    public static class QueueTreeItem {
        private final DeviceQueueItem device;
        private final FileQueueItem file;
        private final ExportDirectoryQueueItem exportDirectory;

        // Root constructor
        public QueueTreeItem() {
            this.device = null;
            this.file = null;
            this.exportDirectory = null;
        }

        // Device constructor
        public QueueTreeItem(DeviceQueueItem device) {
            this.device = device;
            this.file = null;
            this.exportDirectory = null;
        }

        // File constructor
        public QueueTreeItem(FileQueueItem file) {
            this.device = null;
            this.file = file;
            this.exportDirectory = null;
        }

        // Export directory group node constructor
        public QueueTreeItem(ExportDirectoryQueueItem exportDirectory) {
            this.device = null;
            this.file = null;
            this.exportDirectory = exportDirectory;
        }

        public String getName() {
            if (device != null) {
                return device.getDeviceName();
            }
            if (file != null) {
                return file.getFileName();
            }
            if (exportDirectory != null) {
                return exportDirectory.getDisplayName();
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
            if (exportDirectory != null) {
                return exportDirectory.getStatus();
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

        public boolean isExportDir() {
            return exportDirectory != null;
        }

        public java.nio.file.Path getExportDir() {
            return exportDirectory != null ? exportDirectory.getExportDir() : null;
        }

        public Runnable getRetryAction() {
            return exportDirectory != null ? exportDirectory.getRetryAction() : null;
        }

        public int getTotal() {
            if (device != null) {
                return device.getTotal();
            }
            if (exportDirectory != null) {
                return exportDirectory.getTotal();
            }
            return 0;
        }

        public int getPassed() {
            if (device != null) {
                return device.getPassed();
            }
            if (exportDirectory != null) {
                return exportDirectory.getPassed();
            }
            return 0;
        }

        public int getFailed() {
            if (device != null) {
                return device.getFailed();
            }
            if (exportDirectory != null) {
                return exportDirectory.getFailed();
            }
            return 0;
        }

        public String getHardwareId() {
            if (device != null) {
                return device.getHardwareId();
            }
            return null;
        }
    }
}
