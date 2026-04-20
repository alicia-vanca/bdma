package com.app.admin.layout.controllers;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.app.common.definitions.ViewPaths;
import com.app.common.dtos.DeviceEvent;
import com.app.common.dtos.DeviceSummary;
import com.app.common.dtos.DeviceValidationResult;
import com.app.common.helpers.ViewLoader;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.repositories.ValidatedDeviceRepository;
import com.app.common.services.DeviceTracker;
import com.app.common.services.SyncProgressTracker;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.input.MouseButton;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

@Component
@Scope("prototype")
public class DashboardController extends BaseLayoutController {

    private static final String KEY_DEVICE_CONNECTED = "dashboard.device.connected";

    @FXML
    private ListView<DeviceSummary> deviceListView;
    @FXML
    private StackPane fileListContainer;

    private final DeviceTracker deviceTracker;
    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final SyncProgressTracker syncProgressTracker;
    private final Session session;
    private final ObservableList<DeviceSummary> deviceItems = FXCollections.observableArrayList();

    private FileListController fileListController;
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
            SyncProgressTracker syncProgressTracker,
            Session session) {
        super(viewLoader);
        this.deviceTracker = deviceTracker;
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.syncProgressTracker = syncProgressTracker;
        this.session = session;
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
                    setGraphic(null);
                    return;
                }

                setGraphic(buildRow(summary));
                setupClickHandler(this, summary);
            }
        });
    }

    private HBox buildRow(DeviceSummary summary) {
        Circle dot = new Circle(5);

        Label name = new Label(summary.getDisplayName());
        name.getStyleClass().add("device-cell-name");

        TextField nameField = createHiddenTextField(summary.getDisplayName());

        Label sub = new Label();
        applyStatusStyle(summary, dot, sub);

        boolean isAdmin = session.isAdmin();
        SVGPath icon = createEditIcon(isAdmin);

        if (isAdmin) {
            setupEditBehavior(summary, name, nameField, icon);
        }

        StackPane nameBox = new StackPane(name, nameField);
        nameBox.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(2, nameBox, sub);

        return buildLayout(dot, text, icon, isAdmin);
    }

    private TextField createHiddenTextField(String text) {
        TextField tf = new TextField(text);
        tf.setVisible(false);
        tf.setManaged(false);
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
                summary.setDisplayName(newName);

                validatedDeviceRepository
                        .findByHardwareId(summary.getHardwareId())
                        .map(ValidatedDevice::getId)
                        .ifPresent(id ->
                                validatedDeviceRepository.updateDeviceName(id, newName)
                        );

                name.setText(newName);

                if (fileListController != null) {
                    fileListController.onSyncCompleted();
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
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = isAdmin
                ? new HBox(8, dot, text, spacer, icon)
                : new HBox(8, dot, text);

        row.setAlignment(Pos.CENTER_LEFT);
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

        SyncProgressTracker.SyncProgress progress = summary.getSyncProgress();
        SyncProgressTracker.SyncStatus status = progress.status();

        sub.setText(resolveConnectedText(status, progress));
        sub.getStyleClass().add(resolveConnectedStyle(status));
    }

    // Display sync progress for connected devices: queued, syncing with counts, or
    // idle
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
            if (event.getButton() != MouseButton.PRIMARY) return;
            fileListController.filterByDevice(summary.getHardwareId());

            if (event.getClickCount() == 2) {
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

        deviceListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && fileListController != null) {
                fileListController.filterByDevice(newVal.getHardwareId());
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

    // Register listener to receive real-time device connection events from ADB
    // tracker
    private void registerDeviceTrackerListener() {
        if (deviceEventListener != null) {
            return;
        }

        deviceEventListener = event -> Platform.runLater(() -> handleTrackerEvent(event));
        deviceTracker.addListener(deviceEventListener);
    }

    // Update device status based on ADB event: add new devices or mark existing
    // ones as connected/disconnected
    private void handleTrackerEvent(DeviceEvent event) {
        if (event.type() == DeviceEvent.EventType.CONNECTED) {
            handleConnected(event);
        } else if (event.type() == DeviceEvent.EventType.DISCONNECTED) {
            handleDisconnected(event.serial());
        }
        deviceListView.refresh();
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
        String displayName = result.getMatchedModelName() != null
                ? result.getMatchedModelName()
                : result.getHardwareId();
        deviceItems.add(new DeviceSummary(
                result.getHardwareId(),
                displayName,
                DeviceSummary.Status.UNVALIDATED,
                SyncProgressTracker.SyncProgress.idle(),
                result));
        sortDeviceItems();
    }

    // Handle device disconnection: remove unvalidated devices or mark saved devices
    // as offline
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

    // Build DeviceSummary from persisted device: mark CONNECTED if currently
    // plugged in, otherwise OFFLINE
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
            return;
        }

        fileListContainer.getChildren().setAll(result.node());
        fileListController = (FileListController) result.controller();
        fileListController.setOnClearFilter(this::clearDeviceSelection);
    }

    private void clearDeviceSelection() {
        deviceListView.getSelectionModel().clearSelection();
    }

    public void onSyncCompleted() {
        if (fileListController != null) {
            fileListController.onSyncCompleted();
        }
    }
}
