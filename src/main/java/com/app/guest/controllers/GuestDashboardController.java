package com.app.guest.controllers;

import com.app.auth.login.controllers.LoginController;
import com.app.common.definitions.ViewPaths;
import com.app.common.dtos.DeviceSummary;
import com.app.common.helpers.DeviceStatusStyleHelper;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.ViewLoader;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.services.DeviceListState;
import com.app.common.services.DeviceMiniStatus;
import javafx.collections.ListChangeListener;
import java.util.List;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

@Component
public class GuestDashboardController extends BaseLayoutController {

    @FXML
    private GridPane deviceGrid;
    private final DeviceMiniStatus deviceMiniStatus;
    private final DeviceListState deviceListState;

    public GuestDashboardController(ViewLoader viewLoader,
                                    DeviceMiniStatus deviceMiniStatus,
                                    DeviceListState deviceListState) {
        super(viewLoader);
        this.deviceMiniStatus = deviceMiniStatus;
        this.deviceListState = deviceListState;
    }

    private void applyStatusStyle(DeviceSummary summary, Circle dot, Label sub) {
        DeviceStatusStyleHelper.applyStatusStyle(summary, dot, sub);
    }

    @Override
    protected StackPane getContentArea() {
        return null;
    }

    @Override
    protected Button getButtonForModule(String fxml) {
        return null;
    }

    @FXML
    public void initialize() {
        deviceListState.loadSavedDevicesIfNeeded();
        deviceListState.getDeviceItems().addListener((ListChangeListener<? super DeviceSummary>) change -> refresh());
        deviceMiniStatus.setOnProgressChanged(this::refresh);
        refresh();
    }

    private static final int COLUMNS = 3;

    // Re-resolve sync state only for devices represented by persisted records.
    public void refresh() {
        if (deviceGrid == null) {
            return;
        }

        deviceGrid.getChildren().clear();

        List<DeviceSummary> items = deviceListState.getDeviceItems();
        for (int i = 0; i < items.size(); i++) {
            DeviceSummary summary = items.get(i);
            summary.setSyncProgress(resolveSyncProgress(summary));
            VBox card = createDeviceCard(summary);
            GridPane.setFillWidth(card, true);
            deviceGrid.add(card, i % COLUMNS, i / COLUMNS);
        }
    }

    private VBox createDeviceCard(DeviceSummary summary) {
        Circle dot = new Circle(8);

        Label nameLabel = new Label(summary.getDeviceName());
        nameLabel.getStyleClass().add("device-cell-name");

        Label cameraIdLabel = new Label(summary.getCameraId());
        cameraIdLabel.getStyleClass().add("device-cell-camera-id");

        Label statusLabel = new Label();
        applyStatusStyle(summary, dot, statusLabel);

        VBox infoBox = new VBox(4, nameLabel, cameraIdLabel, statusLabel);
        infoBox.getStyleClass().add("device-info-box");

        HBox header = new HBox(8, dot, infoBox);
        header.getStyleClass().add("device-card-header");

        VBox card = new VBox(12, header);
        card.getStyleClass().add("device-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setOnMouseClicked(event -> {
            DialogHelper.Dialog<LoginController> dialog = DialogHelper.createDialog(
                    ViewPaths.LOGIN,
                    "BDMA"
            );
            Stage stage = dialog.stage();
            stage.setResizable(false);
            stage.setWidth(480);
            stage.setHeight(420);
            stage.showAndWait();
        });

        return card;
    }

    private DeviceMiniStatus.SyncProgress resolveSyncProgress(DeviceSummary summary) {
        if (summary.getCameraId() == null) {
            return DeviceMiniStatus.SyncProgress.idle();
        }
        return deviceMiniStatus.getProgress(summary.getCameraId());
    }
}