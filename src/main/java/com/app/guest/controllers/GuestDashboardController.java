package com.app.guest.controllers;

import com.app.auth.login.controllers.LoginController;
import com.app.common.definitions.ViewPaths;
import com.app.common.definitions.enums.DeviceStatus;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.ViewLoader;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.modules.device.dtos.DeviceSummary;
import com.app.common.modules.device.helpers.DeviceCardStyleHelper;
import com.app.common.modules.device.services.DeviceListState;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Component
public class GuestDashboardController extends BaseLayoutController {

    private static final Logger log = LoggerFactory.getLogger(GuestDashboardController.class);

    @FXML
    private GridPane deviceGrid;
    private final DeviceListState deviceListState;
    @Setter
    private java.util.function.Consumer<DeviceSummary> onRequestSync;

    public GuestDashboardController(ViewLoader viewLoader,
            DeviceListState deviceListState) {
        super(viewLoader);
        this.deviceListState = deviceListState;
    }

    private void applyStatusStyle(DeviceSummary summary, Circle dot, Label sub) {
        DeviceCardStyleHelper.applyStatusStyle(summary, dot, sub);
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
        deviceListState.getDeviceFxItems().addListener((ListChangeListener<? super DeviceSummary>) change -> refresh());
        refresh();
    }

    private static final int COLUMNS = 3;

    public void refresh() {
        if (deviceGrid == null) {
            return;
        }

        deviceGrid.getChildren().clear();

        List<DeviceSummary> items = deviceListState.getDeviceFxItems();
        int visibleIndex = 0;
        for (DeviceSummary summary : items) {
            if (!summary.isActive()) {
                continue;
            }
            VBox card = createDeviceCard(summary);
            GridPane.setFillWidth(card, true);
            deviceGrid.add(card, visibleIndex % COLUMNS, visibleIndex / COLUMNS);
            visibleIndex++;
        }
    }

    private VBox createDeviceCard(DeviceSummary summary) {
        Circle dot = new Circle(10);

        Label nameLabel = new Label(summary.getDeviceName());
        nameLabel.getStyleClass().add("device-cell-name");

        Label cameraIdLabel = new Label(summary.getCameraId());
        cameraIdLabel.getStyleClass().add("device-cell-camera-id");

        Label statusLabel = new Label();
        applyStatusStyle(summary, dot, statusLabel);

        VBox nameBox = new VBox(2, nameLabel, cameraIdLabel);
        nameBox.getStyleClass().add("device-name-box");
        HBox.setHgrow(nameBox, Priority.ALWAYS);

        HBox topRow = new HBox(8, nameBox, statusLabel);
        topRow.getStyleClass().add("device-card-top-row");
        topRow.setAlignment(Pos.TOP_LEFT);

        VBox infoBox = new VBox(8, topRow);
        infoBox.getStyleClass().add("device-info-box");
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        HBox header = new HBox(10, dot, infoBox);
        header.getStyleClass().add("device-card-header");
        header.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(10, header);
        card.getStyleClass().add("device-card");
        if (summary.getStatus() == DeviceStatus.CONNECTED) {
            card.getStyleClass().add("connected");
        } else if (summary.getStatus() == DeviceStatus.UNVALIDATED) {
            card.getStyleClass().add("unvalidated");
        }
        card.setMaxWidth(Double.MAX_VALUE);

        // ── Spec row (battery | storage) ──
        GridPane specRow = DeviceCardStyleHelper.buildSpecificationRow(summary);
        card.getChildren().add(specRow);

        card.setOnMouseClicked(event -> handleDeviceCardClick(event, summary));

        return card;
    }

    private void handleDeviceCardClick(MouseEvent event, DeviceSummary summary) {
        if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) {
            return;
        }

        if (summary.isUnvalidated()) {
            openLoginDialog();
            return;
        }

        Consumer<DeviceSummary> requestSync = onRequestSync;
        if (requestSync != null) {
            requestSync.accept(summary);
        }
    }

    private void openLoginDialog() {
        try {
            DialogHelper.Dialog<LoginController> dialog = DialogHelper.createDialog(
                    ViewPaths.LOGIN,
                    "BDMA");
            Stage stage = dialog.stage();
            stage.setResizable(false);
            stage.setWidth(480);
            stage.setHeight(420);
            stage.showAndWait();
        } catch (Exception e) {
            log.error("Failed to open login dialog", e);
            new Alert(Alert.AlertType.ERROR, "Unable to open login dialog.").showAndWait();
        }
    }
}
