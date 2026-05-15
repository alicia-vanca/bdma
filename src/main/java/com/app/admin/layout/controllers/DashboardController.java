package com.app.admin.layout.controllers;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.ViewPaths;
import com.app.common.dtos.DeviceSummary;
import com.app.common.dtos.DeviceValidationResult;
import com.app.common.events.DeviceEvent;
import com.app.common.helpers.ViewLoader;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.repositories.ValidatedDeviceRepository;
import com.app.common.services.DeviceMiniStatus;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import lombok.Setter;

@Component
public class DashboardController extends BaseLayoutController {

    @FXML
    private ListView<DeviceSummary> deviceListView;
    @FXML
    private StackPane fileListContainer;
    @FXML
    private VBox devicePanel;
    @FXML
    private VBox fileListPane;
    @FXML
    private HBox root;
    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final DeviceMiniStatus deviceMiniStatus;
    private final Session session;
    private final ObservableList<DeviceSummary> deviceItems = FXCollections.observableArrayList();

    private FileListController fileListController;
    private boolean deviceStateInitialized;
    private Consumer<DeviceSummary> onRequestValidate;
    @Setter
    private Consumer<DeviceSummary> onRequestSync;
    private static final Comparator<DeviceSummary> DEVICE_NAME_COMPARATOR = Comparator.comparing(
            DashboardController::sortName,
            String.CASE_INSENSITIVE_ORDER)
            .thenComparing(summary -> summary.getHardwareId() == null ? "" : summary.getHardwareId(),
                    String.CASE_INSENSITIVE_ORDER);

    public DashboardController(ViewLoader viewLoader,
            ValidatedDeviceRepository validatedDeviceRepository,
            DeviceMiniStatus deviceMiniStatus,
            Session session) {
        super(viewLoader);
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.deviceMiniStatus = deviceMiniStatus;
        this.session = session;
    }

    public void setOnRequestValidate(Consumer<DeviceSummary> callback) {
        this.onRequestValidate = callback;
        updateCellFactory();
    }

    private void updateCellFactory() {
        deviceListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(DeviceSummary summary, boolean empty) {
                super.updateItem(summary, empty);

                if (empty || summary == null) {
                    setGraphic(null);
                    return;
                }

                setGraphic(buildRow(summary));
                setupClickHandler(this, summary);
            }
        });
    }

    private HBox buildRow(DeviceSummary summary) {
        Circle dot = new Circle(8);

        Label name = new Label(summary.getDeviceName());
        name.getStyleClass().add("device-cell-name");

        TextField nameField = createHiddenTextField(summary.getDeviceName());

        Label sub = new Label();
        applyStatusStyle(summary, dot, sub);

        boolean isAdmin = session.isAdmin();
        SVGPath icon = createEditIcon(isAdmin);

        if (isAdmin) {
            setupEditBehavior(summary, name, nameField, icon);
        }

        StackPane nameBox = new StackPane(name, nameField);
        nameBox.setAlignment(Pos.CENTER_LEFT);
        nameBox.setMaxWidth(110);
        VBox text = new VBox(2, nameBox, sub);
        HBox.setHgrow(text, Priority.NEVER);

        return buildLayout(dot, text, icon, isAdmin);
    }

    private TextField createHiddenTextField(String text) {
        TextField tf = new TextField(text);
        tf.setVisible(false);
        tf.setManaged(false);
        tf.setMaxWidth(110);
        tf.setPrefWidth(110);
        return tf;
    }

    private SVGPath createEditIcon(boolean isAdmin) {
        SVGPath icon = new SVGPath();
        icon.setContent("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25z");
        icon.getStyleClass().add("edit-icon-svg");
        icon.setPickOnBounds(true);
        icon.setVisible(isAdmin);
        icon.setManaged(isAdmin);
        return icon;
    }

    private void setupEditBehavior(DeviceSummary summary,
            Label name,
            TextField nameField,
            SVGPath icon) {

        icon.setOnMousePressed(Event::consume);

        icon.setOnMouseClicked(e -> {
            e.consume();
            enterEditMode(name, nameField);
        });

        Runnable commitEdit = createCommitAction(summary, name, nameField);

        nameField.setOnAction(e -> commitEdit.run());

        nameField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (Boolean.FALSE.equals(newVal)) {
                commitEdit.run();
            }
        });
    }

    private void enterEditMode(Label name, TextField nameField) {
        nameField.setText(name.getText());

        name.setVisible(false);
        name.setManaged(false);

        nameField.setVisible(true);
        nameField.setManaged(true);
        nameField.requestFocus();
        nameField.selectAll();
    }

    private Runnable createCommitAction(DeviceSummary summary,
            Label name,
            TextField nameField) {

        return () -> {
            String newName = nameField.getText();

            if (newName != null && !newName.isBlank()) {
                summary.setDeviceName(newName);

                validatedDeviceRepository
                        .findByCameraId(summary.getCameraId())
                        .map(ValidatedDevice::getId)
                        .ifPresent(id -> validatedDeviceRepository.updateDeviceName(id, newName));

                name.setText(newName);

                if (fileListController != null) {
                    fileListController.onFileSyncCompleted(null);
                }
            }

            exitEditMode(name, nameField);
        };
    }

    private void exitEditMode(Label name, TextField nameField) {
        name.setVisible(true);
        name.setManaged(true);

        nameField.setVisible(false);
        nameField.setManaged(false);
    }

    private HBox buildLayout(Circle dot, VBox text, SVGPath icon, boolean isAdmin) {
        HBox row;
        if (isAdmin) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox.setHgrow(text, Priority.NEVER);
            row = new HBox(8, dot, text, spacer, icon);
        } else {
            row = new HBox(8, dot, text);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
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

    private void setupClickHandler(ListCell<DeviceSummary> cell, DeviceSummary summary) {
        cell.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY)
                return;
            fileListController.filterByDevice(summary.getCameraId());

            if (event.getClickCount() == 2) {
                if (summary.isUnvalidated() && onRequestValidate != null) {
                    onRequestValidate.accept(summary);
                } else if (!summary.isUnvalidated() && onRequestSync != null) {
                    onRequestSync.accept(summary);
                }
            }
        });
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
        devicePanel.prefWidthProperty().bind(
                root.widthProperty().multiply(0.15));
        devicePanel.maxWidthProperty().bind(
                root.widthProperty().multiply(0.15));
        updateCellFactory();
        deviceListView.setItems(deviceItems);
        if (!deviceStateInitialized) {
            loadSavedDevices();
            deviceStateInitialized = true;
        }
        refresh();
        loadFileList();

        deviceListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && fileListController != null) {
                fileListController.filterByDevice(newVal.getCameraId());
            }
        });
    }

    // Close queue dialog if open
    public void closeQueueDialog() {
        if (fileListController != null) {
            fileListController.closeQueueDialog();
        }
    }

    // Clear runtime device state only when the user session ends so language or
    // tab reloads can reuse the same in-memory list.
    public void resetState() {
        deviceItems.clear();
        deviceStateInitialized = false;

        // Cleanup file list controller resources to prevent thread leaks
        if (fileListController != null) {
            fileListController.cleanup();
        }
    }

    // Handle device connection/disconnection events from DeviceTracker

    @EventListener
    public void handleTrackerEvent(DeviceEvent event) {
        // Ignore events before UI initialization
        if (deviceListView == null) {
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
            deviceListView.refresh();
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
        deviceListView.refresh();
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
        deviceListView.refresh();
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
        if (summary.getHardwareId() == null) {
            return DeviceMiniStatus.SyncProgress.idle();
        }
        return deviceMiniStatus.getProgress(summary.getHardwareId());
    }

    // Re-resolve sync state only for devices represented by persisted records.
    public void refresh() {
        for (DeviceSummary summary : deviceItems) {
            if (isPersistedDeviceState(summary)) {
                summary.setSyncProgress(resolveSyncProgress(summary));
            }
        }
        deviceListView.refresh();
    }

    private boolean isPersistedDeviceState(DeviceSummary summary) {
        return summary.getStatus() == DeviceSummary.Status.CONNECTED
                || summary.getStatus() == DeviceSummary.Status.OFFLINE;
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
        deviceListView.refresh();
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

    private void loadFileList() {
        var result = viewLoader.loadView(ViewPaths.FILE_LIST_PANEL);
        if (result == null) {
            return;
        }

        fileListContainer.getChildren().setAll(result.node());
        fileListController = (FileListController) result.controller();
        fileListController.setOnClearFilter(this::clearDeviceSelection);
    }

    private void clearDeviceSelection() {
        deviceListView.getSelectionModel().clearSelection();
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

    /**
     * Clear selected files when user leaves the dashboard view.
     */
    public void resetFileSelectionState() {
        if (fileListController != null) {
            fileListController.resetSelectionState();
        }
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
