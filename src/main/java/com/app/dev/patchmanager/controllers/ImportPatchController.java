package com.app.dev.patchmanager.controllers;

import java.io.File;
import java.nio.file.Files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.common.exceptions.AppException;
import com.app.common.helpers.AlertHelper;
import com.app.common.helpers.NoticeStackRenderer;
import com.app.common.models.PatchApplyLog;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.services.UserSettingService;
import com.app.dev.patchmanager.services.PatchApplyLogService;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

@Component
public class ImportPatchController {

    private static final Logger log = LoggerFactory.getLogger(ImportPatchController.class);
    private static final String ENCRYPTED_SQL_EXTENSION = ".sql.enc";

    private final PatchApplyLogService patchApplyLogService;
    private final UserSettingService userSettingService;
    private final Session session;

    @FXML
    private StackPane root;
    @FXML
    private Button btnImportPatch;
    @FXML
    private TableColumn<PatchApplyLog, String> colPatchAppliedAt;
    @FXML
    private TableColumn<PatchApplyLog, String> colPatchFile;
    @FXML
    private TableColumn<PatchApplyLog, String> colPatchId;
    @FXML
    private TableView<PatchApplyLog> tableAppliedPatches;
    @FXML
    private VBox noticeContainer;

    private NoticeStackRenderer noticeRenderer;

    public ImportPatchController(
            PatchApplyLogService patchApplyLogService,
            UserSettingService userSettingService,
            Session session) {
        this.patchApplyLogService = patchApplyLogService;
        this.userSettingService = userSettingService;
        this.session = session;
    }

    @FXML
    public void initialize() {
        noticeRenderer = new NoticeStackRenderer(noticeContainer);
        btnImportPatch.setText(I18n.get("setting.patch.btn.apply"));
        colPatchId.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getPatchId().toString()));
        colPatchFile.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getFileName()));
        colPatchAppliedAt.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getAppliedAt()));
        refreshAppliedPatchTable();
    }

    @FXML
    public void onImportPatch() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("setting.patch.chooser.apply.title"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.get("setting.patch.chooser.encrypted.files"), "*" + ENCRYPTED_SQL_EXTENSION));
        applyLastChooserDirectory(chooser);
        File selected = chooser.showOpenDialog(getStage());
        if (selected == null) {
            return;
        }

        saveChooserDirectory(selected);
        try {
            patchApplyLogService.apply(selected.toPath());
            Files.deleteIfExists(selected.toPath());
            refreshAppliedPatchTable();
            showPatchApplyResultDialog(
                    I18n.get("setting.patch.apply.dialog.success.header"),
                    I18n.get("setting.patch.apply.dialog.success.content"));
        } catch (AppException e) {
            log.warn("Patch apply rejected: {}", e.getMessage(), e);
            showPatchApplyResultDialog(I18n.get("setting.patch.apply.dialog.failure.header"),
                    toPatchApplyFailureMessage(e));
        } catch (Exception e) {
            log.error("Failed to apply patch file", e);
            showPatchApplyResultDialog(I18n.get("setting.patch.apply.dialog.failure.header"),
                    I18n.get("setting.patch.apply.dialog.failure.generic"));
        }
    }

    private void refreshAppliedPatchTable() {
        tableAppliedPatches.setItems(FXCollections.observableArrayList(patchApplyLogService.listAppliedPatches()));
    }

    private void applyLastChooserDirectory(FileChooser chooser) {
        File lastDir = new File(userSettingService.getLastOpenPath(session.getCurrentUserId()));
        if (lastDir.exists() && lastDir.isDirectory()) {
            chooser.setInitialDirectory(lastDir);
        }
    }

    private void saveChooserDirectory(File selected) {
        File parent = selected.getParentFile();
        if (parent != null && parent.exists() && parent.isDirectory()) {
            userSettingService.saveLastOpenPath(session.getCurrentUserId(), parent.getAbsolutePath());
        }
    }

    private String toPatchApplyFailureMessage(AppException e) {
        String message = e.getMessage();
        if (message != null && message.contains("has already been applied")) {
            return I18n.get("setting.patch.apply.dialog.failure.duplicate");
        }
        return I18n.get("setting.patch.apply.dialog.failure.incompatible");
    }

    private void showPatchApplyResultDialog(String header, String content) {
        AlertHelper.createInformation(I18n.get("setting.patch.apply.dialog.title"), header, content).showAndWait();
    }

    private Stage getStage() {
        return (Stage) root.getScene().getWindow();
    }

    @SuppressWarnings("unused")
    private void showNotice(String message, boolean success) {
        if (success) {
            noticeRenderer.showSuccess(message);
        } else {
            noticeRenderer.showError(message);
        }
    }
}