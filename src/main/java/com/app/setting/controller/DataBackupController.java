package com.app.setting.controller;

import com.app.common.i18n.I18n;
import com.app.setting.service.DataBackupService;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DataBackupController {

    private static final Logger log = LoggerFactory.getLogger(DataBackupController.class);

    @FXML
    private CheckBox chkAutoDelete;
    @FXML
    private Label lblStatus;

    private final DataBackupService dataBackupService;

    public DataBackupController(DataBackupService dataBackupService) {
        this.dataBackupService = dataBackupService;
    }

    @FXML
    public void initialize() {
        boolean autoDelete = dataBackupService.getAutoDelete();
        chkAutoDelete.setSelected(autoDelete);
        updateStatus(autoDelete);
    }

    @FXML
    public void onToggleAutoDelete() {
        boolean autoDelete = chkAutoDelete.isSelected();
        try {
            dataBackupService.setAutoDelete(autoDelete);
            updateStatus(autoDelete);
            log.info("BodyCam autoDelete toggled: {}", autoDelete);
        } catch (Exception e) {
            log.error("Failed to save BodyCam autoDelete setting", e);
            chkAutoDelete.setSelected(!autoDelete); // rollback UI
            lblStatus.setText(I18n.get("setting.databackup.status.error"));
            lblStatus.setStyle("-fx-text-fill: red;");
        }
    }

    private void updateStatus(boolean autoDelete) {
        if (autoDelete) {
            lblStatus.setText(I18n.get("setting.databackup.status.on"));
            lblStatus.setStyle("-fx-text-fill: green;");
        } else {
            lblStatus.setText(I18n.get("setting.databackup.status.off"));
            lblStatus.setStyle("-fx-text-fill: orange;");
        }
    }
}