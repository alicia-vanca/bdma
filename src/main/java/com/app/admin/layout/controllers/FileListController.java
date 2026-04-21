package com.app.admin.layout.controllers;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.app.common.dtos.FileFilter;
import com.app.common.dtos.FileView;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.services.FileService;
import com.app.common.services.UserService;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.util.StringConverter;

@Component
@Scope("prototype")
public class FileListController {

    private static final List<String> TYPES = List.of("audio", "image", "video", "IMP", "SOS");
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final List<Integer> PAGE_SIZE_THRESHOLDS = List.of(10, 25, 50, 100);
    private static final DateTimeFormatter DATE_PICKER_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    @FXML
    private DatePicker dateFromPicker;
    @FXML
    private DatePicker dateToPicker;
    @FXML
    private ComboBox<UserOption> userFilterCombo;
    @FXML
    private ComboBox<TypeOption> typeFilterCombo;
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
    private int pageSize = DEFAULT_PAGE_SIZE;
    private int currentPageIndex = 0;
    private Runnable onClearFilter;

    public FileListController(FileService fileService, UserService userService, Session session) {
        this.fileService = fileService;
        this.userService = userService;
        this.session = session;
    }

    @FXML
    public void initialize() {
        setupColumns();
        setupDatePickers();

        boolean isAdmin = session.isAdmin();
        userFilterCombo.setVisible(isAdmin);
        userFilterCombo.setManaged(isAdmin);

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

    private void setupDatePickers() {
        StringConverter<LocalDate> converter = new StringConverter<LocalDate>() {
            @Override
            public String toString(LocalDate date) {
                return date != null ? date.format(DATE_PICKER_FORMATTER) : "";
            }

            @Override
            public LocalDate fromString(String string) {
                return (string != null && !string.isEmpty())
                        ? LocalDate.parse(string, DATE_PICKER_FORMATTER)
                        : null;
            }
        };

        dateFromPicker.setConverter(converter);
        dateToPicker.setConverter(converter);
    }

    private void setupColumns() {
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        colDevice.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().deviceName()));
        colUser.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().username()));
        colSize.setCellValueFactory(c -> new SimpleStringProperty(formatSize(c.getValue().fileSize())));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(formatStatus(c.getValue().status())));
        colType.setCellValueFactory(c -> new SimpleStringProperty(formatType(c.getValue().type())));
        colDate.setCellValueFactory(c -> new SimpleStringProperty(formatDate(c.getValue().createDate())));
    }

    public void filterByDevice(String hardwareId) {
        this.activeHardwareId = hardwareId;
        refresh(buildFilter());
    }

    public void setOnClearFilter(Runnable callback) {
        this.onClearFilter = callback;
    }

    @FXML
    public void clearFilter() {
        dateFromPicker.setValue(null);
        dateToPicker.setValue(null);
        userFilterCombo.getSelectionModel().selectFirst();
        activeHardwareId = null;
        if (onClearFilter != null) {
            onClearFilter.run();
        }
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

    // Rebuild file table with current page slice after filter or page size changes
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
        lblPageInfo.setText(I18n.get("common.page") + " " + (currentPageIndex + 1) + " / " + pageCount);
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

        UserOption userOption = userFilterCombo.getValue();
        if (userOption != null && userOption.id() != null) {
            filter.setUserId(userOption.id());
        }

        TypeOption typeOption = typeFilterCombo.getValue();
        if (typeOption != null && typeOption.key() != null) {
            filter.setType(typeOption.key());
        }

        return filter;
    }

    private String formatSize(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private String formatDate(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return "";
        }
        try {
            // Parse from database format: yyyy-MM-dd HH:mm:ss
            DateTimeFormatter dbFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            var dateTime = java.time.LocalDateTime.parse(dateTimeStr, dbFormatter);
            return dateTime.format(DATE_DISPLAY_FORMATTER);
        } catch (Exception e) {
            return dateTimeStr;
        }
    }

    // Translate file status using i18n keys
    private String formatStatus(String status) {
        if (status == null || status.isEmpty()) {
            return "";
        }
        String key = "file.status." + status;
        return I18n.get(key);
    }

    // Translate file type using i18n keys
    private String formatType(String type) {
        if (type == null || type.isEmpty()) {
            return "";
        }
        String key = "file.type." + type;
        return I18n.get(key);
    }

    record UserOption(Long id, String label) {
        @NotNull
        @Override
        public String toString() {
            return label;
        }
    }

    record TypeOption(String key, String label) {
        @NotNull
        @Override
        public String toString() {
            return label;
        }
    }

    private void loadUsers() {
        var users = userService.findUsersOnly();
        var options = new ArrayList<UserOption>();
        options.add(new UserOption(null, I18n.get("filter.allUsers")));

        users.stream()
                .sorted((u1, u2) -> u1.getUsername().compareToIgnoreCase(u2.getUsername()))
                .forEach(u -> options.add(new UserOption(u.getId(), u.getUsername())));

        userFilterCombo.setItems(FXCollections.observableArrayList(options));
        userFilterCombo.getSelectionModel().selectFirst();
    }

    // Auto-refresh file list when any filter changes, skip during initial setup to
    // avoid redundant queries
    private void setupAutoFilter() {
        dateFromPicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) {
                return;
            }
            refresh(buildFilter());
        });

        dateToPicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) {
                return;
            }
            refresh(buildFilter());
        });

        userFilterCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) {
                return;
            }
            refresh(buildFilter());
        });

        typeFilterCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) {
                return;
            }
            refresh(buildFilter());
        });
    }

    private void loadTypes() {
        var options = new ArrayList<TypeOption>();
        options.add(new TypeOption(null, I18n.get("filter.allTypes")));

        TYPES.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(type -> options.add(new TypeOption(type, I18n.get("file.type." + type))));

        typeFilterCombo.setItems(FXCollections.observableArrayList(options));
        typeFilterCombo.getSelectionModel().selectFirst();
    }

    public void onSyncCompleted() {
        Platform.runLater(() -> refresh(buildFilter()));
    }

    public void onBackupCompleted() {
        Platform.runLater(() -> refresh(buildFilter()));
    }
}
