package com.app.admin.layout.controllers;

import com.app.common.dtos.FileFilter;
import com.app.common.dtos.FileView;
import com.app.common.modules.session.Session;
import com.app.common.services.FileService;
import com.app.common.services.UserService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
@Scope("prototype")
public class FileListController {

    private static final List<String> TYPES = List.of("audio", "image", "video", "IMP", "SOS");

    @FXML
    private DatePicker dateFromPicker;
    @FXML
    private DatePicker dateToPicker;
    @FXML
    private ComboBox<UserOption> userFilterCombo;
    @FXML
    private ComboBox<String> typeFilterCombo;
    @FXML
    private TableView<FileView> fileTable;
    @FXML
    private TableColumn<FileView, String> colName;
    @FXML
    private TableColumn<FileView, String> colDevice;
    @FXML
    private TableColumn<FileView, String> colUser;
    @FXML
    private TableColumn<FileView, String> colSize;
    @FXML
    private TableColumn<FileView, String> colStatus;
    @FXML
    private TableColumn<FileView, String> colType;
    @FXML
    private TableColumn<FileView, String> colDate;

    @FXML
    private Button btnPrev;
    @FXML
    private Button btnNext;
    @FXML
    private Label lblPageInfo;
    @FXML
    private ComboBox<Integer> cbPageSize;

    private final FileService fileService;
    private final UserService userService;
    private final Session session;

    private String activeHardwareId;

    private boolean initializing = true;

    private List<FileView> filteredFiles = new ArrayList<>();

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final List<Integer> PAGE_SIZE_THRESHOLDS = List.of(10, 25, 50, 100);
    private int pageSize = DEFAULT_PAGE_SIZE;
    private int currentPageIndex = 0;

    public FileListController(FileService fileService, UserService userService, Session session) {
        this.fileService = fileService;
        this.userService = userService;
        this.session = session;
    }

    @FXML
    public void initialize() {
        setupColumns();

        boolean isAdmin = session.isAdmin();
        userFilterCombo.setVisible(isAdmin);
        userFilterCombo.setManaged(isAdmin);

        LocalDate today = LocalDate.now();
        dateFromPicker.setValue(today);
        dateToPicker.setValue(today);

        if (isAdmin) {
            loadUsers();
        }
        loadTypes();
        setupPageSizeComboBox();
        initializing = true;

        setupAutoFilter();

        initializing = false;
        refresh(buildFilter());
    }

    private void setupColumns() {
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        colDevice.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().deviceName()));
        colUser.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().username()));
        colSize.setCellValueFactory(c -> new SimpleStringProperty(formatSize(c.getValue().fileSize())));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        colType.setCellValueFactory(c-> new SimpleStringProperty(c.getValue().type()));
        colDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().createDate()));
    }

    public void filterByDevice(String hardwareId) {
        this.activeHardwareId = hardwareId;
        refresh(buildFilter());
    }

    @FXML
    public void clearFilter() {
        dateFromPicker.setValue(null);
        dateToPicker.setValue(null);
        userFilterCombo.setValue(null);
        activeHardwareId = null;
        refresh(null);
    }

    public void refresh(FileFilter filter) {
        if (filter == null) {
            filter = new FileFilter();
        }

        List<FileView> files = fileService.query(filter);
        filteredFiles = new ArrayList<>(files);
        currentPageIndex = 0;
        setupPagination();
    }

    private void setupPageSizeComboBox() {
        cbPageSize.getItems().setAll(PAGE_SIZE_THRESHOLDS);
        cbPageSize.setValue(DEFAULT_PAGE_SIZE);
        cbPageSize.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null || Objects.equals(newValue, pageSize)) {
                return;
            }
            pageSize = newValue;
            currentPageIndex = 0;
            setupPagination();
        });
    }

    private void setupPagination() {
        int pageCount = getPageCount();
        if (currentPageIndex >= pageCount) {
            currentPageIndex = pageCount - 1;
        }
        updateTablePage();
        updatePagerControls(pageCount);
        fileTable.refresh();
    }

    private void updateTablePage() {
        int from = currentPageIndex * pageSize;
        int to = Math.min(from + pageSize, filteredFiles.size());
        fileTable.setItems(FXCollections.observableArrayList(
                from < to ? filteredFiles.subList(from, to) : List.of()));
    }

    private void updatePagerControls(int pageCount) {
        btnPrev.setDisable(currentPageIndex <= 0);
        btnNext.setDisable(currentPageIndex >= pageCount - 1);
        lblPageInfo.setText("Trang " + (currentPageIndex + 1) + " / " + pageCount);
    }

    private int getPageCount() {
        return Math.max((int) Math.ceil((double) filteredFiles.size() / pageSize), 1);
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

    private FileFilter buildFilter() {
        FileFilter filter = new FileFilter();

        filter.setHardwareId(activeHardwareId);
        filter.setDateFrom(dateFromPicker.getValue());
        filter.setDateTo(dateToPicker.getValue());

        if (userFilterCombo.getValue() != null
                && userFilterCombo.getValue().id() != null) {
            filter.setUserId(userFilterCombo.getValue().id());
        }

        String type = typeFilterCombo.getValue();
        if (type != null && !"All Types".equalsIgnoreCase(type)) {
            filter.setType(type);
        }

        return filter;
    }

    private String formatSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    record UserOption(Long id, String label) {
        @NotNull
        @Override
        public String toString() {
            return label;
        }
    }

    private void loadUsers() {
        var users = userService.findUsersOnly();
        var options = new ArrayList<UserOption>();
        options.add(new UserOption(null, "All Users"));
        for (var u : users) {
            options.add(new UserOption(u.getId(), u.getUsername()));
        }

        userFilterCombo.setItems(FXCollections.observableArrayList(options));
        userFilterCombo.getSelectionModel().selectFirst();
    }

    private void setupAutoFilter() {

        dateFromPicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) return;
            refresh(buildFilter());
        });

        dateToPicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) return;
            refresh(buildFilter());
        });

        userFilterCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) return;
            refresh(buildFilter());
        });

        typeFilterCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) return;
            refresh(buildFilter());
        });
    }

    private void loadTypes() {
        var options = new ArrayList<String>();
        options.add("All Types");
        options.addAll(TYPES);

        typeFilterCombo.setItems(FXCollections.observableArrayList(options));
        typeFilterCombo.getSelectionModel().selectFirst();
    }

    public void onSyncCompleted() {
        Platform.runLater(() -> refresh(buildFilter()));
    }
}
