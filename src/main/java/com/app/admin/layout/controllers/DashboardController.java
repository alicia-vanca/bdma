package com.app.admin.layout.controllers;

import com.app.common.definitions.ViewPaths;
import com.app.common.dtos.DeviceEvent;
import com.app.common.dtos.DeviceSummary;
import com.app.common.dtos.DeviceValidationResult;
import com.app.common.helpers.ViewLoader;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.modules.i18n.I18n;
import com.app.common.repositories.ValidatedDeviceRepository;
import com.app.common.services.DeviceTracker;
import com.app.common.services.SyncProgressTracker;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Consumer;

@Component
@Scope("prototype")
public class DashboardController extends BaseLayoutController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private static final String KEY_DEVICE_CONNECTED = "dashboard.device.connected";

    @FXML
    private ListView<DeviceSummary> deviceListView;
    @FXML
    private StackPane fileListContainer;

    private FileListController fileListController;

    private final DeviceTracker deviceTracker;
    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final SyncProgressTracker syncProgressTracker;
    private final ObservableList<DeviceSummary> deviceItems = FXCollections.observableArrayList();
    private boolean deviceStateInitialized;
    private Consumer<DeviceSummary> onRequestValidate;
    private Consumer<DeviceSummary> onRequestSync;
    private java.util.function.Consumer<DeviceEvent> deviceEventListener;
    private static final Comparator<DeviceSummary> DEVICE_NAME_COMPARATOR = Comparator.comparing(
            DashboardController::sortName,
            String.CASE_INSENSITIVE_ORDER)
            .thenComparing(summary -> summary.getHardwareId() == null ? "" : summary.getHardwareId(),
                    String.CASE_INSENSITIVE_ORDER);

    public DashboardController(ViewLoader viewLoader,
            DeviceTracker deviceTracker,
            ValidatedDeviceRepository validatedDeviceRepository,
            SyncProgressTracker syncProgressTracker) {
        super(viewLoader);
        this.deviceTracker = deviceTracker;
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.syncProgressTracker = syncProgressTracker;
    }

    public void setOnRequestValidate(Consumer<DeviceSummary> callback) {
        this.onRequestValidate = callback;
        updateCellFactory();
    }

    public void setOnRequestSync(Consumer<DeviceSummary> callback) {
        this.onRequestSync = callback;
        updateCellFactory();
    }

    public void stopTracking() {
        if (deviceEventListener != null) {
            deviceTracker.removeListener(deviceEventListener);
            deviceEventListener = null;
        }
    }

    private void updateCellFactory() {
        deviceListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(DeviceSummary summary, boolean empty) {
                super.updateItem(summary, empty);

                if (empty || summary == null) {
                    setText(null);
                    setGraphic(null);
                    setOnMouseClicked(null);
                    return;
                }

                Circle dot = new Circle(5);
                Label name = new Label(summary.getDisplayName());
                Label sub = new Label();

                applyStatusStyle(summary, dot, sub);

                name.getStyleClass().add("device-cell-name");

                VBox text = new VBox(2, name, sub);
                HBox row = new HBox(8, dot, text);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("device-cell-row");

                setGraphic(row);
                setText(null);
                DashboardController.this.setupClickHandler(this, summary);
            }
        });
    }

    private void applyStatusStyle(DeviceSummary summary, Circle dot, Label sub) {
        switch (summary.getStatus()) {
            case CONNECTED -> applyConnectedStyle(summary, dot, sub);
            case OFFLINE -> applyOfflineStyle(dot, sub);
            case UNVALIDATED -> applyUnvalidatedStyle(dot, sub);
        }
    }

    private void applyConnectedStyle(DeviceSummary summary, Circle dot, Label sub) {
        dot.getStyleClass().add("dot-connected");

        SyncProgressTracker.SyncProgress progress = summary.getSyncProgress();
        SyncProgressTracker.SyncStatus status = progress.status();

        sub.setText(resolveConnectedText(status, progress));
        sub.getStyleClass().add(resolveConnectedStyle(status));
    }

    private String resolveConnectedText(SyncProgressTracker.SyncStatus status,
            SyncProgressTracker.SyncProgress progress) {

        return switch (status) {
            case QUEUED -> I18n.get("dashboard.device.queued");
            case SYNCING -> I18n.get("dashboard.device.syncing") + " " + formatProgress(progress);
            case COMPLETED -> progress.total() > 0
                    ? I18n.get(KEY_DEVICE_CONNECTED) + " " + formatProgress(progress)
                    : I18n.get(KEY_DEVICE_CONNECTED);
            default -> I18n.get(KEY_DEVICE_CONNECTED);
        };
    }

    private String resolveConnectedStyle(SyncProgressTracker.SyncStatus status) {
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

    private String formatProgress(SyncProgressTracker.SyncProgress progress) {
        return "(" + progress.total() + " : " + progress.passed() + " ✓  " + progress.failed() + " ✗)";
    }

    private void setupClickHandler(ListCell<DeviceSummary> cell, DeviceSummary summary) {
        cell.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                if (summary.isUnvalidated() && onRequestValidate != null) {
                    onRequestValidate.accept(summary);
                } else if (summary.isConnected() && onRequestSync != null) {
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
        updateCellFactory();
        deviceListView.setItems(deviceItems);
        if (!deviceStateInitialized) {
            loadSavedDevices();
            registerDeviceTrackerListener();
            deviceStateInitialized = true;
        }
        refresh();
        loadFileList();

        deviceListView.setOnMouseClicked(event -> {
            DeviceSummary selected = deviceListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                filterFilesByDevice(selected.getHardwareId());
            }
        });
    }

    // Clear runtime device state only when the user session ends so language or
    // tab reloads can reuse the same in-memory list.
    public void resetState() {
        stopTracking();
        deviceItems.clear();
        deviceStateInitialized = false;
    }

    private void registerDeviceTrackerListener() {
        if (deviceEventListener != null) {
            return;
        }

        deviceEventListener = event -> Platform.runLater(() -> handleTrackerEvent(event));
        deviceTracker.addListener(deviceEventListener);
    }

    // Update device status based on adb event.
    private void handleTrackerEvent(DeviceEvent event) {
        if (event.type() == DeviceEvent.EventType.CONNECTED) {
            handleConnected(event);
        } else if (event.type() == DeviceEvent.EventType.DISCONNECTED) {
            handleDisconnected(event.serial());
        }
        deviceListView.refresh();
    }

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
        Optional<DeviceSummary> existing = findByHardwareId(result.getHardwareId());
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
                result.getAccountUserId(),
                DeviceSummary.Status.UNVALIDATED,
                SyncProgressTracker.SyncProgress.idle(),
                result));
        sortDeviceItems();
    }

    private void handleDisconnected(String serial) {
        Optional<DeviceSummary> existing = findBySerial(serial);
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
        summary.setSyncProgress(SyncProgressTracker.SyncProgress.idle());
        deviceListView.refresh();
    }

    private Optional<DeviceSummary> findBySerial(String serial) {
        return deviceItems.stream()
                .filter(summary -> serial.equals(summary.getHardwareId()))
                .findFirst();
    }

    private Optional<DeviceSummary> findByHardwareId(String hardwareId) {
        return deviceItems.stream()
                .filter(summary -> hardwareId != null && hardwareId.equals(summary.getHardwareId()))
                .findFirst();
    }

    private SyncProgressTracker.SyncProgress resolveSyncProgress(DeviceSummary summary) {
        if (summary.getHardwareId() == null) {
            return SyncProgressTracker.SyncProgress.idle();
        }
        return syncProgressTracker.getProgress(summary.getHardwareId());
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

        findBySerial(result.getSerial())
                .filter(summary -> summary.getStatus() == DeviceSummary.Status.UNVALIDATED)
                .ifPresent(summary -> {
                    summary.setDisplayName(savedDeviceName);
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
        if (summary.getDisplayName() != null && !summary.getDisplayName().isBlank()) {
            return summary.getDisplayName();
        }
        if (summary.getHardwareId() != null) {
            return summary.getHardwareId();
        }
        return "";
    }

    // Build a DeviceSummary from a persisted device record, marking it CONNECTED
    // if the tracker already has a live result for its hardware ID.
    private DeviceSummary toSavedSummary(ValidatedDevice device) {
        Optional<DeviceValidationResult> known = deviceTracker.getKnownResultByHardwareId(device.getHardwareId());
        DeviceSummary.Status status = known.isPresent() ? DeviceSummary.Status.CONNECTED : DeviceSummary.Status.OFFLINE;
        DeviceSummary summary = new DeviceSummary(
                device.getHardwareId(),
                device.getDeviceName(),
                status,
                SyncProgressTracker.SyncProgress.idle(),
                null);
        summary.setSyncProgress(resolveSyncProgress(summary));
        return summary;
    }


    private void loadFileList() {
        var result = viewLoader.loadView(ViewPaths.FILE_LIST_PANEL);

        if (result == null) {
            log.error("Failed to load file list panel");
            return;
        }

        fileListContainer.getChildren().setAll(result.node());
        fileListController = (FileListController) result.controller();
    }

    public void filterFilesByDevice(String hardwareId) {
        if (fileListController != null) {
            fileListController.filterByDevice(hardwareId);
        }
    }

    public void notifySyncCompleted() {
        if (fileListController != null) {
            fileListController.onSyncCompleted();
        }
    }
}
