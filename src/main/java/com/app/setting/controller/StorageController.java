package com.app.setting.controller;

import com.app.common.enums.FolderType;
import com.app.common.i18n.I18n;
import com.app.common.ui.BaseLayoutController;
import com.app.common.ui.LayoutAware;
import com.app.setting.service.StorageService;
import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class StorageController implements LayoutAware {

    private static final Logger log = LoggerFactory.getLogger(StorageController.class);

    @Override
    public void setLayoutController(BaseLayoutController layoutController) {
        // This controller does not interact with the layout — no implementation needed.
    }

    @FXML
    private TextField txtSavePath;
    @FXML
    private TextField txtBackupPath;
    @FXML
    private Label lblSaveStatus;
    @FXML
    private Label lblBackupStatus;

    private final StorageService storageService;

    private final PauseTransition hideSaveStatus = new PauseTransition(Duration.seconds(3));
    private final PauseTransition hideBackupStatus = new PauseTransition(Duration.seconds(3));

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    @FXML
    public void initialize() {
        hideSaveStatus.setOnFinished(e -> lblSaveStatus.setText(""));
        hideBackupStatus.setOnFinished(e -> lblBackupStatus.setText(""));

        storageService.getActiveFolderPath(FolderType.SAVE)
                .ifPresent(txtSavePath::setText);
        storageService.getActiveFolderPath(FolderType.BACKUP)
                .ifPresent(txtBackupPath::setText);
    }

    // ── Save Folder ───────────────────────────────────────────────────────────

    @FXML
    public void onSelectSaveFolder(ActionEvent event) {
        selectFolder(txtSavePath, FolderType.SAVE);
    }

    @FXML
    public void onSaveSaveFolder(ActionEvent event) {
        persistFolder(txtSavePath, FolderType.SAVE, lblSaveStatus, hideSaveStatus);
    }

    // ── Backup Folder ─────────────────────────────────────────────────────────

    @FXML
    public void onSelectBackupFolder(ActionEvent event) {
        selectFolder(txtBackupPath, FolderType.BACKUP);
    }

    @FXML
    public void onSaveBackupFolder(ActionEvent event) {
        persistFolder(txtBackupPath, FolderType.BACKUP, lblBackupStatus, hideBackupStatus);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void selectFolder(TextField txtField, FolderType type) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("setting.storage.chooser.title", type.toString()));

        String current = txtField.getText();
        if (current != null && !current.isBlank()) {
            File currentDir = new File(current);
            if (currentDir.exists()) chooser.setInitialDirectory(currentDir);
        }

        Stage stage = (Stage) txtField.getScene().getWindow();
        File selected = chooser.showDialog(stage);
        if (selected != null) txtField.setText(selected.getAbsolutePath());
    }

    private void persistFolder(TextField txtField, FolderType type,
                               Label lblStatus, PauseTransition hideTransition) {
        String path = txtField.getText();
        if (path == null || path.isBlank()) {
            setStatus(lblStatus, hideTransition, "setting.storage.warn.empty", "orange");
            return;
        }
        try {
            storageService.saveFolder(path, type);
            log.info("Saved folder [{}]: {}", type.name(), path);
            setStatus(lblStatus, hideTransition, "setting.storage.success", "green");
        } catch (Exception e) {
            log.error("Failed to save folder [{}]: {}", type.name(), path, e);
            setStatus(lblStatus, hideTransition, "setting.storage.error", "red");
        }
    }

    private void setStatus(Label label, PauseTransition hideTransition,
                           String messageKey, String color) {
        label.setText(I18n.get(messageKey));
        label.setStyle("-fx-text-fill: " + color + ";");
        hideTransition.playFromStart();
    }
}