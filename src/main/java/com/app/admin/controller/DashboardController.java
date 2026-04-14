package com.app.admin.controller;

import com.app.common.ui.BaseLayoutController;
import com.app.common.ui.ViewLoader;
import com.app.device.model.DeviceSummary;
import com.app.device.service.DeviceValidationService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Component
public class DashboardController extends BaseLayoutController {

    @FXML
    private ListView<DeviceSummary> deviceListView;

    private final DeviceValidationService deviceValidationService;
    private Consumer<DeviceSummary> onValidate;
    private Consumer<DeviceSummary> onSync;

    public DashboardController(ViewLoader viewLoader,
                               DeviceValidationService deviceValidationService) {
        super(viewLoader);
        this.deviceValidationService = deviceValidationService;
    }

    public void setOnValidate(Consumer<DeviceSummary> callback) {
        this.onValidate = callback;
        updateCellFactory();
    }

    public void setOnSync(Consumer<DeviceSummary> callback) {
        this.onSync = callback;
        updateCellFactory();
    }

    private void updateCellFactory() {
        deviceListView.setCellFactory(lv -> {
            DeviceListCell cell = new DeviceListCell(onValidate);
            if (onSync != null) {
                cell.setOnSync(onSync);
            }
            return cell;
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
        refresh();
    }

    public void refresh() {
        List<DeviceSummary> summaries = deviceValidationService.listDeviceSummaries();
        deviceListView.setItems(FXCollections.observableArrayList(summaries));
    }
}
