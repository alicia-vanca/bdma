package com.app.common.modules.queuemanager.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.common.events.LanguageChangedEvent;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.queuemanager.events.QueueStatusChangedEvent;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * Parent controller for the queue dialog. Each queue domain owns its tab
 * content
 * through a child controller.
 */
@Component
public class QueueDialogController {

    private static final Logger log = LoggerFactory.getLogger(QueueDialogController.class);

    @FXML
    private TabPane tabPane;
    @FXML
    private Tab tabSync;
    @FXML
    private Tab tabBackup;
    @FXML
    private Tab tabExport;
    @FXML
    private SyncQueueController syncQueueController;
    @FXML
    private BackupQueueController backupQueueController;
    @FXML
    private ExportQueueController exportQueueController;

    @FXML
    public void initialize() {
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

    @EventListener
    public void onQueueStatusChanged(QueueStatusChangedEvent event) {
        Platform.runLater(() -> refreshChangedQueueRow(event));
    }

    /**
     * Refreshes only the queue tab affected by the event so high-frequency progress
     * updates do not repaint unrelated queues.
     */
    private void refreshChangedQueueRow(QueueStatusChangedEvent event) {
        if (!isInitialized()) {
            return;
        }

        switch (event.getQueueType()) {
            case SYNC -> refreshSyncQueueRow(event);
            case BACKUP -> refreshBackupQueueRow(event);
            case EXPORT -> refreshExportQueueRow(event);
        }
    }

    private void refreshSyncQueueRow(QueueStatusChangedEvent event) {
        if (event.isFullRefreshRequired()) {
            syncQueueController.refresh();
            return;
        }
        syncQueueController.refreshRow(event.getRowId(), event.getRootRowId());
    }

    private void refreshBackupQueueRow(QueueStatusChangedEvent event) {
        if (event.isFullRefreshRequired()) {
            backupQueueController.refresh();
            return;
        }
        backupQueueController.refreshRow(event.getRowId());
    }

    private void refreshExportQueueRow(QueueStatusChangedEvent event) {
        if (event.isFullRefreshRequired()) {
            exportQueueController.refresh();
            return;
        }
        exportQueueController.refreshRow(event.getRowId(), event.getRootRowId());
    }

    /**
     * Called by DialogHelper when language changes and this dialog needs to repaint
     * text that was generated programmatically.
     */
    @SuppressWarnings("unused") // Used in DialogHelper.java
    public void onLanguageChanged(LanguageChangedEvent event) {
        if (log.isDebugEnabled()) {
            log.debug("Queue dialog language changed: {}", event.getLanguageTag());
        }
        Platform.runLater(() -> {
            if (!isInitialized()) {
                return;
            }

            refreshStaticTexts();
            refreshAllTabs();
        });
    }

    public void refreshAllTabs() {
        if (!isInitialized()) {
            return;
        }
        syncQueueController.refresh();
        backupQueueController.refresh();
        exportQueueController.refresh();
    }

    /**
     * Re-apply i18n text for static controls because this dialog can stay open
     * while language changes at runtime.
     */
    private void refreshStaticTexts() {
        tabSync.setText(I18n.get("queue.tab.sync"));
        tabBackup.setText(I18n.get("queue.tab.backup"));
        tabExport.setText(I18n.get("queue.tab.export"));
        syncQueueController.refreshStaticTexts();
        backupQueueController.refreshStaticTexts();
        exportQueueController.refreshStaticTexts();
    }

    private boolean isInitialized() {
        return syncQueueController != null && syncQueueController.isInitialized()
                && backupQueueController != null && backupQueueController.isInitialized()
                && exportQueueController != null && exportQueueController.isInitialized();
    }
}
