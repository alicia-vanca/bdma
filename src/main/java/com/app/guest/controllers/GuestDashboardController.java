package com.app.guest.controllers;

import com.app.admin.layout.controllers.FileListController;
import com.app.auth.login.controllers.LoginController;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.ViewPaths;
import com.app.common.dtos.DeviceSummary;
import com.app.common.dtos.DeviceValidationResult;
import com.app.common.events.DeviceEvent;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.ViewLoader;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.modules.i18n.I18n;
import com.app.common.repositories.ValidatedDeviceRepository;
import com.app.common.services.DeviceMiniStatus;
import javafx.application.Platform;
import javafx.collections.FXCollections;
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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class GuestDashboardController extends BaseLayoutController {

    @FXML
    private VBox devicePanel;
    @FXML
    private FlowPane deviceFlowPane;
    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final DeviceMiniStatus deviceMiniStatus;
    private final ApplicationEventPublisher publisher;
    private final ObservableList<DeviceSummary> deviceItems = FXCollections.observableArrayList();

    private FileListController fileListController;
    private boolean deviceStateInitialized;
    private static final Comparator<DeviceSummary> DEVICE_NAME_COMPARATOR = Comparator.comparing(
                    GuestDashboardController::sortName,
                    String.CASE_INSENSITIVE_ORDER)
            .thenComparing(summary -> summary.getHardwareId() == null ? "" : summary.getHardwareId(),
                    String.CASE_INSENSITIVE_ORDER);

    public GuestDashboardController(ViewLoader viewLoader,
                                ValidatedDeviceRepository validatedDeviceRepository,
                                DeviceMiniStatus deviceMiniStatus,
                                ApplicationEventPublisher publisher) {
        super(viewLoader);
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.deviceMiniStatus = deviceMiniStatus;
        this.publisher = publisher;
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
//        devicePanel.setMaxWidth(Double.MAX_VALUE);
        if (!deviceStateInitialized) {
            loadSavedDevices();
            deviceStateInitialized = true;
        }
        refresh();
    }

    // Handle device connection/disconnection events from DeviceTracker

    @EventListener
    public void handleTrackerEvent(DeviceEvent event) {
        // Ignore events before UI initialization
        if (deviceFlowPane == null) {
            return;
        }

        Platform.runLater(() -> {
            if (event.type() == DeviceEvent.EventType.CONNECTED) {
                handleConnected(event);
            } else if (event.type() == DeviceEvent.EventType.DISCONNECTED) {
                handleDisconnectedEvent(event);
            } else if (event.type() == DeviceEvent.EventType.UNVALIDATED) {
                handleUnvalidated(event);
            }
            refresh();
        });
    }

    // Handle device disconnection events with proper logic for both validated and
    // unvalidated devices
    private void handleDisconnectedEvent(DeviceEvent event) {
        String cameraId;

        // Try to get cameraId from validation result first
        if (event.validationResult() != null) {
            cameraId = event.validationResult().getCameraId();
        } else {
            // For devices without validation result, find by hardwareId
            cameraId = findCameraIdByHardwareId(event.hardwareId());
        }

        if (cameraId != null) {
            handleDisconnected(cameraId);
        }
    }

    // Handle unvalidated device: add as transient device to the list
    private void handleUnvalidated(DeviceEvent event) {
        DeviceValidationResult result = event.validationResult();
        if (result == null) {
            return;
        }

        // Check if device already exists in the list to avoid duplicates
        Optional<DeviceSummary> existing = findByCameraId(result.getCameraId());
        if (existing.isPresent()) {
            return;
        }

        addTransientDevice(result);
    }

    // Handle device connection: update saved device status or add as unvalidated
    // transient device
    private void handleConnected(DeviceEvent event) {
        DeviceValidationResult result = event.validationResult();
        if (result == null || !result.isValid()) {
            refresh();
            return;
        }

        if (result.isAlreadySaved()) {
            updateSavedDeviceAsConnected(result);
        } else {
            addTransientDevice(result);
        }
    }

    private void updateSavedDeviceAsConnected(DeviceValidationResult result) {
        Optional<DeviceSummary> existing = findByCameraId(result.getCameraId());
        if (existing.isEmpty()) {
            refresh();
            return;
        }

        DeviceSummary summary = existing.get();
        summary.setHardwareId(result.getHardwareId());
        summary.setStatus(DeviceSummary.Status.CONNECTED);
        summary.setSyncProgress(resolveSyncProgress(summary));
        refresh();
    }

    private void addTransientDevice(DeviceValidationResult result) {
        deviceItems.add(new DeviceSummary(
                result.getHardwareId(),
                result.getCameraId(),
                result.getCameraId(),
                DeviceSummary.Status.UNVALIDATED,
                DeviceMiniStatus.SyncProgress.idle(),
                result));
        sortDeviceItems();
    }

    // Handle device disconnection: remove unvalidated devices or mark saved devices
    // as offline
    private void handleDisconnected(String cameraId) {
        Optional<DeviceSummary> existing = findByCameraId(cameraId);
        if (existing.isEmpty()) {
            return;
        }

        DeviceSummary summary = existing.get();
        if (summary.getStatus() == DeviceSummary.Status.UNVALIDATED) {
            deviceItems.remove(summary);

            sortDeviceItems();
            return;
        }

        summary.setStatus(DeviceSummary.Status.OFFLINE);
        summary.setSyncProgress(DeviceMiniStatus.SyncProgress.idle());
        refresh();
    }

    private Optional<DeviceSummary> findByCameraId(String cameraId) {
        return deviceItems.stream()
                .filter(summary -> cameraId != null && cameraId.equals(summary.getCameraId()))
                .findFirst();
    }

    private String findCameraIdByHardwareId(String hardwareId) {
        return deviceItems.stream()
                .filter(summary -> hardwareId != null && hardwareId.equals(summary.getHardwareId()))
                .map(DeviceSummary::getCameraId)
                .findFirst()
                .orElse(null);
    }

    private DeviceMiniStatus.SyncProgress resolveSyncProgress(DeviceSummary summary) {
        if (summary.getCameraId() == null) {
            return DeviceMiniStatus.SyncProgress.idle();
        }
        return deviceMiniStatus.getProgress(summary.getCameraId());
    }

    // Re-resolve sync state only for devices represented by persisted records.
    public void refresh() {
        deviceFlowPane.getChildren().clear();

        for(DeviceSummary summary : deviceItems) {
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

    private void loadSavedDevices() {
        deviceItems.setAll(validatedDeviceRepository.findAll().stream()
                .map(this::toSavedSummary)
                .toList());
        sortDeviceItems();
    }

    public void markDeviceSaved(DeviceValidationResult result, String savedDeviceName) {
        if (result == null) {
            return;
        }

        findByCameraId(result.getCameraId())
                .filter(summary -> summary.getStatus() == DeviceSummary.Status.UNVALIDATED)
                .ifPresent(summary -> {
                    summary.setDeviceName(savedDeviceName);
                    summary.setStatus(DeviceSummary.Status.CONNECTED);
                    summary.setSyncProgress(resolveSyncProgress(summary));
                    summary.setValidationResult(null);
                });
        refresh();
    }

    private void sortDeviceItems() {
        FXCollections.sort(deviceItems, DEVICE_NAME_COMPARATOR);
    }

    private static String sortName(DeviceSummary summary) {
        if (summary.getDeviceName() != null && !summary.getDeviceName().isBlank()) {
            return summary.getDeviceName();
        }
        if (summary.getHardwareId() != null) {
            return summary.getHardwareId();
        }
        return "";
    }

    // Build DeviceSummary from persisted device: status will be updated by
    // device tracker events when devices connect/disconnect
    private DeviceSummary toSavedSummary(ValidatedDevice device) {
        DeviceSummary summary = new DeviceSummary(
                device.getHardwareId(),
                device.getDeviceName(),
                device.getCameraId(),
                DeviceSummary.Status.OFFLINE,
                DeviceMiniStatus.SyncProgress.idle(),
                null);
        summary.setSyncProgress(resolveSyncProgress(summary));
        return summary;
    }

    /**
     * Refreshes the active file list after a sync writes a new file record.
     *
     * @param syncedPath stored synced path used to mark the file as available
     */
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
        List<ValidatedDevice> dbDevices = validatedDeviceRepository.findAll();

        Set<String> dbCameraIds = dbDevices.stream()
                .map(ValidatedDevice::getCameraId)
                .collect(Collectors.toSet());
        for (ValidatedDevice device : dbDevices) {
            boolean alreadyInList = deviceItems.stream()
                    .anyMatch(s -> device.getCameraId().equals(s.getCameraId()));
            if (!alreadyInList) {
                deviceItems.add(toSavedSummary(device));
            }
        }

        deviceItems.removeIf(summary -> summary.getStatus() == DeviceSummary.Status.OFFLINE
                && !dbCameraIds.contains(summary.getCameraId()));

        sortDeviceItems();
    }

    public void onUserAutoCreated() {
        if (fileListController != null) {
            fileListController.reloadUserFilter();
        }
    }
}

