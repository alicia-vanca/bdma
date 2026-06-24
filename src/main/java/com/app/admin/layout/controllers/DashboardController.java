package com.app.admin.layout.controllers;

import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.app.common.definitions.ViewPaths;
import com.app.common.helpers.ViewLoader;
import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.modules.device.dtos.DeviceSummary;
import com.app.common.modules.device.helpers.DeviceCardStyleHelper;
import com.app.common.modules.device.services.DeviceListState;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
    private HBox root;
    private final DeviceListState deviceListState;

    private FileListController fileListController;
    private Consumer<DeviceSummary> onRequestValidate;
    @Setter
    private Consumer<DeviceSummary> onRequestSync;

    public DashboardController(ViewLoader viewLoader,
            DeviceListState deviceListState) {
        super(viewLoader);
        this.deviceListState = deviceListState;
    }

    public void setOnRequestValidate(Consumer<DeviceSummary> callback) {
        this.onRequestValidate = callback;
        updateCellFactory();
    }

    @SuppressWarnings("unused")
    private void updateCellFactory() {
        deviceListView.setCellFactory(listView -> new ListCell<>() {
            private DeviceSummary currentSummary = null;

            {
                addEventFilter(MouseEvent.MOUSE_PRESSED,
                        event -> handleDeviceMousePressed(this, getItem(), event));
            }

            @Override
            protected void updateItem(DeviceSummary summary, boolean empty) {
                super.updateItem(summary, empty);

                if (empty || summary == null) {
                    setGraphic(null);
                    currentSummary = null;
                    return;
                }

                if (summary == currentSummary) {
                    return;
                }

                currentSummary = summary;
                setGraphic(buildRow(summary));
            }
        });
    }

    private VBox buildRow(DeviceSummary summary) {
        return DeviceCardStyleHelper.buildAdminDeviceCard(summary);
    }

    @SuppressWarnings("unused")
    private <T> ChangeListener<T> onValueChanged(Consumer<T> action) {
        return (observable, oldValue, newValue) -> action.accept(newValue);
    }

    private void handleDeviceMousePressed(ListCell<DeviceSummary> cell, DeviceSummary summary, MouseEvent event) {
        if (summary == null || event.getButton() != MouseButton.PRIMARY || fileListController == null) {
            return;
        }

        if (cell.isSelected()) {
            fileListController.clearDeviceFilter();
            deviceListView.getSelectionModel().clearSelection();
            event.consume();
        } else {
            fileListController.filterByDevice(summary.getCameraId());
            deviceListView.getSelectionModel().select(cell.getIndex());
            event.consume();
        }

        if (event.getClickCount() == 2) {
            fileListController.filterByDevice(summary.getCameraId());
            handleDeviceDoublePress(summary, event.getClickCount());
            event.consume();
        }
    }

    private void handleDeviceDoublePress(DeviceSummary summary, int clickCount) {
        if (clickCount != 2) {
            return;
        }
        Platform.runLater(() -> {
            if (summary.isUnvalidated() && onRequestValidate != null) {
                onRequestValidate.accept(summary);
            } else if (!summary.isUnvalidated() && onRequestSync != null) {
                onRequestSync.accept(summary);
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
                root.widthProperty().multiply(0.2));
        devicePanel.maxWidthProperty().bind(
                root.widthProperty().multiply(0.2));
        updateCellFactory();
        deviceListView.setItems(new FilteredList<>(deviceListState.getDeviceFxItems(), DeviceSummary::isActive));
        deviceListState.loadSavedDevicesIfNeeded();
        deviceListView.refresh();
        loadFileList();
        restoreSelectionFromActiveFilter();

        deviceListView.getItems().addListener(
                (ListChangeListener<DeviceSummary>) change -> restoreSelectionFromActiveFilter());

        deviceListView.getSelectionModel().selectedItemProperty().addListener(onValueChanged(selectedDevice -> {
            if (selectedDevice != null && fileListController != null) {
                fileListController.filterByDevice(selectedDevice.getCameraId());
            }
        }));
    }

    /**
     * Clears nested file-list state when the dashboard is detached during logout.
     */
    public void cleanup() {
        if (fileListController != null) {
            fileListController.cleanup();
        }
    }

    public void refresh() {
        deviceListView.refresh();
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

    private void restoreSelectionFromActiveFilter() {
        if (fileListController == null || fileListController.getActiveCameraId() == null) {
            return;
        }

        deviceListView.getItems().stream()
                .filter(device -> fileListController.getActiveCameraId().equals(device.getCameraId()))
                .findFirst()
                .ifPresent(deviceListView.getSelectionModel()::select);
    }

    private void clearDeviceSelection() {
        deviceListView.getSelectionModel().clearSelection();
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
        deviceListState.mergeSavedDevices();
        refresh();
    }
}
