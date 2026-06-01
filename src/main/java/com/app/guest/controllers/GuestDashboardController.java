package com.app.guest.controllers;

import com.app.admin.layout.controllers.FileListController;
import com.app.auth.login.controllers.LoginController;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.ViewPaths;
import com.app.common.dtos.DeviceSummary;
import com.app.common.dtos.DeviceValidationResult;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.ViewLoader;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.modules.i18n.I18n;
import com.app.common.services.DeviceListState;
import com.app.common.services.DeviceMiniStatus;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import lombok.Setter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class GuestDashboardController extends BaseLayoutController {

    @FXML
    private VBox devicePanel;
    @FXML
    private FlowPane deviceFlowPane;
    private final DeviceMiniStatus deviceMiniStatus;
    private final ApplicationEventPublisher publisher;
    private final DeviceListState deviceListState;
    private final ObservableList<DeviceSummary> deviceItems;

    private FileListController fileListController;

    public GuestDashboardController(ViewLoader viewLoader,
                                    DeviceMiniStatus deviceMiniStatus,
                                    ApplicationEventPublisher publisher,
                                    DeviceListState deviceListState) {
        super(viewLoader);
        this.deviceMiniStatus = deviceMiniStatus;
        this.publisher = publisher;
        this.deviceListState = deviceListState;
        this.deviceItems = deviceListState.getDeviceItems();
    }

    // Apply visual styling based on device status: connected (green), offline
    // (gray), or unvalidated (yellow)
    private void applyStatusStyle(DeviceSummary summary, Circle dot, Label sub) {
        switch (summary.getStatus()) {
            case CONNECTED -> applyConnectedStyle(summary, dot, sub);
            case OFFLINE -> applyOfflineStyle(dot, sub);
            case UNVALIDATED -> applyUnvalidatedStyle(dot, sub);
        }
    }

    private void applyConnectedStyle(DeviceSummary summary, Circle dot, Label sub) {
        dot.getStyleClass().add("dot-connected");

        DeviceMiniStatus.SyncProgress progress = summary.getSyncProgress();
        DeviceMiniStatus.SyncStatus status = progress.status();

        sub.setText(resolveConnectedText(status, progress));
        sub.getStyleClass().add(resolveConnectedStyle(status));
    }

    // Display sync progress for connected devices: queued, syncing with counts, or
    // idle
    private String resolveConnectedText(DeviceMiniStatus.SyncStatus status,
                                        DeviceMiniStatus.SyncProgress progress) {

        return switch (status) {
            case QUEUED -> I18n.get("dashboard.device.queued");
            case SYNCING -> I18n.get("dashboard.device.syncing") + " " + formatProgress(progress);
            case COMPLETED -> I18n.get(AppConstants.KEY_DEVICE_SYNCED) + " " + formatProgress(progress);
            default -> I18n.get(AppConstants.KEY_DEVICE_CONNECTED);
        };
    }

    private String resolveConnectedStyle(DeviceMiniStatus.SyncStatus status) {
        return switch (status) {
            case QUEUED -> "device-cell-queued";
            case SYNCING -> "device-cell-syncing";
            default -> "device-cell-connected";
        };
    }

    private void applyOfflineStyle(Circle dot, Label sub) {
        dot.getStyleClass().add("dot-offline");
        sub.setText(I18n.get("dashboard.device.offline"));
        sub.getStyleClass().add("device-cell-offline");
    }

    private void applyUnvalidatedStyle(Circle dot, Label sub) {
        dot.getStyleClass().add("dot-unvalidated");
        sub.setText(I18n.get("dashboard.device.unvalidated"));
        sub.getStyleClass().add("device-cell-unvalidated");
    }

    private String formatProgress(DeviceMiniStatus.SyncProgress progress) {
        return "(" + progress.total() + " : " + progress.passed() + " ✓  " + progress.failed() + " ✗)";
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
        deviceItems.addListener((ListChangeListener<? super DeviceSummary>) change -> refresh());
        deviceMiniStatus.setOnProgressChanged(this::refresh);
        refresh();
    }

    // Re-resolve sync state only for devices represented by persisted records.
    public void refresh() {
        if (deviceFlowPane == null) {
            return;
        }

        deviceFlowPane.getChildren().clear();

        for (DeviceSummary summary : deviceItems) {
            summary.setSyncProgress(resolveSyncProgress(summary));
            deviceFlowPane.getChildren().add(createDeviceCard(summary));
        }
    }

    private VBox createDeviceCard(DeviceSummary summary) {
        Circle dot = new Circle(8);

        Label nameLabel = new Label(summary.getDeviceName());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16;");

        Label cameraIdLabel = new Label(summary.getCameraId());
        cameraIdLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 16;");

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 16;");
        applyStatusStyle(summary, dot, statusLabel);

        VBox infoBox = new VBox(4, nameLabel, cameraIdLabel, statusLabel);
        infoBox.setStyle("-fx-padding: 0 8 0 8;");

        HBox header = new HBox(8, dot, infoBox);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(12, header);
        card.setStyle(
                "-fx-border-color: #ddd; " +
                        "-fx-border-radius: 5; " +
                        "-fx-padding: 12; " +
                        "-fx-background-color: #f9f9f9;"
        );
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        card.setPrefWidth(screen.getWidth() / 3.5);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setOnMouseClicked(event -> {
            DialogHelper.Dialog<LoginController> dialog = DialogHelper.createDialog(
                    ViewPaths.LOGIN,
                    "BDMA"
            );
            Stage stage = dialog.stage();
            stage.setResizable(false);
            stage.setWidth(480);
            stage.setHeight(360);
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

    public void markDeviceSaved(DeviceValidationResult result, String savedDeviceName) {
        deviceListState.markDeviceSaved(result, savedDeviceName);
        refresh();
    }

    public void onFileSyncCompleted(String syncedPath) {
        if (fileListController != null) {
            fileListController.onFileSyncCompleted(syncedPath);
        }
    }

    public void onFileBackupCompleted() {
        if (fileListController != null) {
            fileListController.onFileBackupCompleted();
        }
        refresh();
    }

    public void mergeSavedDevices() {
        deviceListState.mergeSavedDevices();
        refresh();
    }

    public void onUserAutoCreated() {
        if (fileListController != null) {
            fileListController.reloadUserFilter();
        }
    }
}

