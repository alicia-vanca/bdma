package com.app.admin.devicemanagement.controllers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.app.admin.devicemanagement.dtos.DeviceManagementRow;
import com.app.admin.devicemanagement.services.DeviceManagementService;
import com.app.admin.layout.controllers.AdminLayoutController;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.DeviceStatus;
import com.app.common.modules.device.dtos.DeviceSummary;
import com.app.common.helpers.AlertHelper;
import com.app.common.modules.i18n.I18n;
import com.app.common.services.AppNoticeService;
import com.app.common.modules.device.services.DeviceListState;
import com.app.common.utils.DateTimeUtil;

import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

@Component
@Lazy
public class DeviceManagementController {

    private static final Logger log = LoggerFactory.getLogger(DeviceManagementController.class);
    private static final String FILTER_ALL_STATUS_KEY = "filter.allStatus";

    @FXML
    @SuppressWarnings("unused")
    private StackPane root;
    @FXML
    private TableView<DeviceManagementRow> table;
    @FXML
    private TableColumn<DeviceManagementRow, Number> colSTT;
    @FXML
    private TableColumn<DeviceManagementRow, String> colDeviceName;
    @FXML
    private TableColumn<DeviceManagementRow, String> colCameraId;
    @FXML
    private TableColumn<DeviceManagementRow, String> colStatus;
    @FXML
    private TableColumn<DeviceManagementRow, String> colLastSync;
    @FXML
    private TableColumn<DeviceManagementRow, Void> colAction;
    @FXML
    private TextField txtSearch;
    @FXML
    private ComboBox<String> cbStatus;
    @FXML
    private Button btnPrev;
    @FXML
    private Button btnNext;
    @FXML
    private ComboBox<Integer> cbPageSize;
    @FXML
    private TextField txtPageNumber;
    @FXML
    private Label lblPageTotal;
    @FXML
    private HBox pageButtonsBox;
    @FXML
    private Button btnFirst;
    @FXML
    private Button btnLast;

    private final DeviceManagementService deviceManagementService;
    private final DeviceListState deviceListState;
    private final AppNoticeService appNoticeService;
    private final AdminLayoutController adminLayoutController;
    private final ListChangeListener<DeviceSummary> deviceStateListener = change -> refreshFromDeviceStateChange();

    private List<DeviceManagementRow> allDevices = new ArrayList<>();
    private List<DeviceManagementRow> filteredDevices = new ArrayList<>();
    private int pageSize = AppConstants.DEFAULT_PAGE_SIZE;
    private int currentPageIndex;
    private final AtomicBoolean deviceStateListenerRegistered = new AtomicBoolean();

    public DeviceManagementController(DeviceManagementService deviceManagementService,
            DeviceListState deviceListState,
            AppNoticeService appNoticeService,
            AdminLayoutController adminLayoutController) {
        this.deviceManagementService = deviceManagementService;
        this.deviceListState = deviceListState;
        this.appNoticeService = appNoticeService;
        this.adminLayoutController = adminLayoutController;
    }

    @FXML
    public void initialize() {
        setupStatusComboBox();
        setupFilterListeners();
        setupTableColumns();
        setupPageSizeComboBox();
        setupPageNumberInput();
        setupDeviceStateListener();
        addActionColumn();
        table.setPlaceholder(new Label(I18n.get("device.management.empty")));
        loadData();
    }

    @SuppressWarnings("unused")
    private void setupDeviceStateListener() {
        if (!deviceStateListenerRegistered.compareAndSet(false, true)) {
            return;
        }
        deviceListState.getDeviceFxItems().addListener(deviceStateListener);
    }

    /**
     * Reloads table rows after shared device state changes. DeviceListState emits
     * these changes after connect, disconnect, deactivate, and reactivate updates.
     */
    private void refreshFromDeviceStateChange() {
        if (isViewReady()) {
            loadDataKeepingPage();
        }
    }

    private void setupStatusComboBox() {
        cbStatus.getItems().setAll(
                I18n.get(FILTER_ALL_STATUS_KEY),
                I18n.get("device.status.connected"),
                I18n.get("device.status.offline"),
                I18n.get("device.status.unvalidated"),
                I18n.get("device.status.deactivated"));
        cbStatus.setValue(I18n.get(FILTER_ALL_STATUS_KEY));
    }

    @SuppressWarnings("unused")
    private void setupFilterListeners() {
        txtSearch.textProperty().addListener((obs, oldValue, newValue) -> onSearch());
        cbStatus.valueProperty().addListener((obs, oldValue, newValue) -> onSearch());
    }

    @SuppressWarnings("unused")
    private void setupTableColumns() {
        colSTT.setCellValueFactory(c -> new SimpleIntegerProperty(
                currentPageIndex * pageSize + table.getItems().indexOf(c.getValue()) + 1));
        colDeviceName.setCellValueFactory(c -> new SimpleStringProperty(nullToEmpty(c.getValue().getDeviceName())));
        colCameraId.setCellValueFactory(c -> new SimpleStringProperty(nullToEmpty(c.getValue().getCameraId())));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(localizeDeviceStatus(c.getValue())));
        colStatus.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String statusText, boolean empty) {
                super.updateItem(statusText, empty);
                getStyleClass().removeAll(
                        "device-cell-connected",
                        "device-cell-unvalidated",
                        "device-cell-deactivated");
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setText(null);
                    return;
                }

                DeviceManagementRow device = getTableView().getItems().get(getIndex());
                setText(statusText);
                String styleClass = statusStyleClass(device);
                if (!styleClass.isBlank()) {
                    getStyleClass().add(styleClass);
                }
            }
        });
        colLastSync.setCellValueFactory(c -> new SimpleStringProperty(
                DateTimeUtil.formatSqliteDateTimeForDisplay(c.getValue().getLastSyncAt(), "")));
    }

    @SuppressWarnings("unused")
    private void setupPageSizeComboBox() {
        cbPageSize.getItems().setAll(AppConstants.PAGE_SIZE_THRESHOLDS);
        cbPageSize.setValue(pageSize);
        cbPageSize.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null || Objects.equals(newValue, pageSize)) {
                return;
            }
            pageSize = newValue;
            currentPageIndex = 0;
            setupPagination();
        });
    }

    private void loadData() {
        loadDataAndApplyFilters(true);
    }

    private void loadDataKeepingPage() {
        loadDataAndApplyFilters(false);
    }

    private void loadDataAndApplyFilters(boolean resetPage) {
        allDevices = deviceManagementService.findAllForAdmin();
        applyFilters(resetPage);
    }

    @FXML
    private void onSearch() {
        applyFilters(true);
    }

    private void applyFilters(boolean resetPage) {
        String keyword = txtSearch.getText() == null ? "" : txtSearch.getText().toLowerCase().trim();
        String status = cbStatus.getValue();

        filteredDevices = allDevices.stream()
                .filter(device -> matchesKeyword(device, keyword))
                .filter(device -> matchesStatus(device, status))
                .toList();
        if (resetPage) {
            currentPageIndex = 0;
        }
        setupPagination();
    }

    @FXML
    private void onReset() {
        txtSearch.clear();
        cbStatus.setValue(I18n.get(FILTER_ALL_STATUS_KEY));
        filteredDevices = new ArrayList<>(allDevices);
        currentPageIndex = 0;
        setupPagination();
    }

    @FXML
    private void onRefresh() {
        loadData();
    }

    private boolean matchesKeyword(DeviceManagementRow device, String keyword) {
        if (keyword.isBlank()) {
            return true;
        }
        return contains(device.getDeviceName(), keyword)
                || contains(device.getCameraId(), keyword);
    }

    private boolean matchesStatus(DeviceManagementRow device, String statusFilter) {
        return statusFilter == null
                || statusFilter.equals(I18n.get(FILTER_ALL_STATUS_KEY))
                || statusFilter.equals(localizeDeviceStatus(device));
    }

    @SuppressWarnings("unused")
    private void addActionColumn() {
        colAction.setCellFactory(param -> new TableCell<>() {

            private final Button btnRename = new Button(I18n.get("device.action.rename"));
            private final Button btnSave = new Button(I18n.get("device.action.save"));
            private final Button btnToggleActive = new Button();
            private final HBox actions = new HBox(btnRename, btnSave, btnToggleActive);

            {
                btnRename.getStyleClass().add("btn-edit");
                btnSave.getStyleClass().add("btn-reactivate");
                actions.getStyleClass().add("action-cell-box");

                btnRename.setOnAction(e -> renameDevice(getTableView().getItems().get(getIndex())));
                btnSave.setOnAction(e -> saveTransientDevice(getTableView().getItems().get(getIndex())));
                btnToggleActive.setOnAction(e -> toggleActive(getTableView().getItems().get(getIndex())));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }

                DeviceManagementRow device = getTableView().getItems().get(getIndex());
                btnRename.setVisible(!device.isTransientDevice());
                btnRename.setManaged(!device.isTransientDevice());
                btnSave.setVisible(device.isTransientDevice());
                btnSave.setManaged(device.isTransientDevice());
                btnToggleActive.setVisible(!device.isTransientDevice());
                btnToggleActive.setManaged(!device.isTransientDevice());

                if (device.isActive()) {
                    btnToggleActive.setText(I18n.get("device.action.deactivate"));
                    btnToggleActive.getStyleClass().setAll("button", "btn-delete");
                } else {
                    btnToggleActive.setText(I18n.get("device.action.reactivate"));
                    btnToggleActive.getStyleClass().setAll("button", "btn-reactivate");
                }
                setGraphic(actions);
            }
        });
    }

    private void renameDevice(DeviceManagementRow device) {
        if (device == null || device.getId() == null) {
            return;
        }

        TextField input = new TextField(device.getDeviceName());
        input.getStyleClass().add("input");
        input.setPromptText(nullToEmpty(device.getCameraId()));
        VBox content = new VBox(input);
        content.setSpacing(10);

        Alert dialog = AlertHelper.createConfirmation(
                I18n.get("device.rename.title"),
                I18n.get("device.rename.prompt"),
                null);
        ButtonType saveButton = new ButtonType(I18n.get("common.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType(I18n.get("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertHelper.setButtons(dialog, saveButton, cancelButton);
        dialog.getDialogPane().setContent(content);
        Button saveControl = (Button) dialog.getDialogPane().lookupButton(saveButton);
        saveControl.disableProperty().bind(input.textProperty().map(value -> value == null || value.trim().isBlank()));
        Platform.runLater(input::requestFocus);

        if (dialog.showAndWait().filter(saveButton::equals).isPresent()) {
            renameDeviceConfirmed(device, input.getText().trim());
        }
    }

    private void renameDeviceConfirmed(DeviceManagementRow device, String name) {
        try {
            deviceManagementService.renameDevice(device.getId(), name);
            deviceListState.setDeviceName(device.getCameraId(), name);
            appNoticeService.showSuccess(I18n.get("device.rename.success"));
            loadData();
        } catch (Exception ex) {
            log.error("Failed to rename device {}", device.getCameraId(), ex);
            appNoticeService.showError(I18n.get("device.rename.error"));
        }
    }

    private void saveTransientDevice(DeviceManagementRow device) {
        if (device == null || device.getValidationResult() == null) {
            return;
        }

        adminLayoutController.showSaveDeviceConfirmation(device.getValidationResult());
        loadData();
    }

    private void toggleActive(DeviceManagementRow device) {
        if (device == null || device.getId() == null) {
            return;
        }

        Alert confirm = device.isActive()
                ? createDeactivateConfirmation(device)
                : createReactivateConfirmation(device);

        if (confirm.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            toggleDeviceActive(device);
        }
    }

    private Alert createDeactivateConfirmation(DeviceManagementRow device) {
        return AlertHelper.createConfirmation(
                I18n.get("common.confirm"),
                I18n.get("device.deactivate.confirm.header", device.getDeviceName()),
                I18n.get("device.deactivate.confirm.content"));
    }

    private Alert createReactivateConfirmation(DeviceManagementRow device) {
        var latestDeactivation = deviceManagementService.findLatestDeactivation(device.getId());
        String deactivatedAt = latestDeactivation
                .map(history -> DateTimeUtil.formatSqliteDateTimeForDisplay(
                        history.getChangedAt(),
                        I18n.get("common.unknown")))
                .filter(value -> !value.isBlank())
                .orElse(I18n.get("common.unknown"));
        String changedBy = latestDeactivation
                .flatMap(deviceManagementService::findOtherDeactivationAdmin)
                .map(username -> " " + I18n.get("device.deactivated.by", username))
                .orElse("");

        return AlertHelper.createConfirmation(
                I18n.get("common.confirm"),
                I18n.get("device.reactivate.confirm.header", device.getDeviceName()),
                I18n.get("device.reactivate.confirm.content", deactivatedAt) + changedBy);
    }

    private void toggleDeviceActive(DeviceManagementRow device) {
        try {
            if (device.isActive()) {
                deviceManagementService.deactivate(device.getId());
                deviceListState.setDeviceActive(device.getCameraId(), false);
                appNoticeService.showSuccess(I18n.get("device.deactivate.success"));
            } else {
                deviceManagementService.reactivate(device.getId());
                deviceListState.setDeviceActive(device.getCameraId(), true);
                appNoticeService.showSuccess(I18n.get("device.reactivate.success"));
                if (deviceListState.isConnected(device.getCameraId())) {
                    adminLayoutController.registerDeviceForSync(device.getCameraId());
                }
            }
            loadDataKeepingPage();
        } catch (Exception ex) {
            log.error("Failed to toggle device {}", device.getCameraId(), ex);
            appNoticeService.showError(I18n.get("device.toggle.error"));
        }
    }

    private boolean isViewReady() {
        return table != null && txtSearch != null && cbStatus != null;
    }

    private void setupPagination() {
        int pageCount = getPageCount();
        if (currentPageIndex >= pageCount) {
            currentPageIndex = pageCount - 1;
        }
        updateTablePage();
        updatePagerControls(pageCount);
        table.refresh();
    }

    private void updateTablePage() {
        int from = currentPageIndex * pageSize;
        int to = Math.min(from + pageSize, filteredDevices.size());
        table.setItems(FXCollections.observableArrayList(
                from < to ? filteredDevices.subList(from, to) : List.of()));
    }

    private void updatePagerControls(int pageCount) {
        btnFirst.setDisable(currentPageIndex <= 0);
        btnPrev.setDisable(currentPageIndex <= 0);
        btnNext.setDisable(currentPageIndex >= pageCount - 1);
        btnLast.setDisable(currentPageIndex >= pageCount - 1);
        lblPageTotal.setText("/ " + pageCount);
        txtPageNumber.setText(String.valueOf(currentPageIndex + 1));
        buildPageButtons(pageCount);
    }

    @FXML
    private void onFirstPage() {
        if (currentPageIndex > 0) {
            currentPageIndex = 0;
            setupPagination();
        }
    }

    @FXML
    private void onLastPage() {
        int last = getPageCount() - 1;
        if (currentPageIndex < last) {
            currentPageIndex = last;
            setupPagination();
        }
    }

    @FXML
    private void onPrevPage() {
        if (currentPageIndex > 0) {
            currentPageIndex--;
            setupPagination();
        }
    }

    @FXML
    private void onNextPage() {
        if (currentPageIndex < getPageCount() - 1) {
            currentPageIndex++;
            setupPagination();
        }
    }

    @SuppressWarnings("unused")
    private void buildPageButtons(int pageCount) {
        pageButtonsBox.getChildren().clear();
        for (int page : getPageRange(pageCount)) {
            Button btn = new Button(String.valueOf(page + 1));
            btn.getStyleClass().add("btn-secondary");
            btn.getStyleClass().add("btn-pagination");
            if (page == currentPageIndex) {
                btn.getStyleClass().add("btn-page-active");
            }
            btn.setOnAction(event -> {
                event.consume();
                currentPageIndex = page;
                setupPagination();
            });
            pageButtonsBox.getChildren().add(btn);
        }
    }

    private List<Integer> getPageRange(int pageCount) {
        if (pageCount <= 7) {
            List<Integer> pages = new ArrayList<>();
            for (int i = 0; i < pageCount; i++) {
                pages.add(i);
            }
            return pages;
        }

        int start = currentPageIndex - 3;
        int end = currentPageIndex + 3;
        if (start < 0) {
            start = 0;
            end = 6;
        }
        if (end >= pageCount) {
            end = pageCount - 1;
            start = end - 6;
        }

        List<Integer> pages = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            pages.add(i);
        }
        return pages;
    }

    @SuppressWarnings("unused")
    private void setupPageNumberInput() {
        txtPageNumber.setOnAction(event -> {
            event.consume();
            jumpToPage();
        });
        txtPageNumber.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (Boolean.TRUE.equals(wasFocused) && Boolean.FALSE.equals(isFocused)) {
                observable.getValue();
                jumpToPage();
            }
        });
    }

    private void jumpToPage() {
        try {
            int page = Integer.parseInt(txtPageNumber.getText().trim());
            int target = Math.clamp(page, 1, getPageCount()) - 1;
            if (target != currentPageIndex) {
                currentPageIndex = target;
                setupPagination();
            } else {
                txtPageNumber.setText(String.valueOf(currentPageIndex + 1));
            }
        } catch (NumberFormatException ex) {
            log.debug("Invalid page number input: {}", txtPageNumber.getText(), ex);
            txtPageNumber.setText(String.valueOf(currentPageIndex + 1));
        }
    }

    private int getPageCount() {
        return Math.max((int) Math.ceil((double) filteredDevices.size() / pageSize), 1);
    }

    private String localizeDeviceStatus(DeviceManagementRow device) {
        if (!device.isActive()) {
            return I18n.get("device.status.deactivated");
        }
        return localizeStatus(device.getStatus());
    }

    private String localizeStatus(DeviceStatus status) {
        return switch (status) {
            case CONNECTED -> I18n.get("device.status.connected");
            case OFFLINE -> I18n.get("device.status.offline");
            case UNVALIDATED -> I18n.get("device.status.unvalidated");
        };
    }

    private String statusStyleClass(DeviceManagementRow device) {
        if (!device.isActive()) {
            return "device-cell-deactivated";
        }
        return switch (device.getStatus()) {
            case CONNECTED -> "device-cell-connected";
            case UNVALIDATED -> "device-cell-unvalidated";
            case OFFLINE -> "";
        };
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
