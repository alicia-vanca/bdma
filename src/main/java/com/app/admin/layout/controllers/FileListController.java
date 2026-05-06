package com.app.admin.layout.controllers;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.app.common.definitions.AppConstants;
import com.app.common.dtos.FileFilter;
import com.app.common.dtos.FileView;
import com.app.common.helpers.AlertHelper;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.exceptions.FileNotFoundOnAnyDriveException;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.services.AppConfigService;
import com.app.common.services.FileService;
import com.app.common.services.UserService;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.app.common.modules.foldermanager.exceptions.FileNotFoundOnAnyDriveException;
import javafx.application.Platform;
import javafx.concurrent.Task;

@Component
@Scope("prototype")
public class FileListController {

    private static final Logger log = LoggerFactory.getLogger(FileListController.class);

    private static final DateTimeFormatter DATE_PICKER_FORMATTER = DateTimeFormatter
            .ofPattern(AppConstants.DATE_PICKER_FORMAT);
    private static final DateTimeFormatter DATE_DISPLAY_FORMATTER = DateTimeFormatter
            .ofPattern(AppConstants.DATE_DISPLAY_FORMAT);

    private final AppConfigService appConfigService;

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

    private CheckBox chkSelectAll;
    @FXML
    private TableColumn<FileView, Boolean> colSelect;
    @FXML
    private Button btnDownload;
    @FXML
    private Label lblSelectedCount;

    private final ObservableSet<FileView> selectedItems = FXCollections.observableSet(new HashSet<>());

    private final FileService fileService;
    private final UserService userService;
    private final Session session;
    private final FolderManagerService folderManagerService;

    private String activeHardwareId;
    private boolean initializing = true;
    private List<FileView> filteredFiles = new ArrayList<>();
    private int pageSize = AppConstants.DEFAULT_PAGE_SIZE;
    private int currentPageIndex = 0;
    @Setter
    private Runnable onClearFilter;

    // Async file verification with concurrent thread pool
    private final Map<String, VerificationStatus> fileVerificationCache = new ConcurrentHashMap<>();
    private final ExecutorService verificationExecutor = Executors.newFixedThreadPool(
            Math.max(8, Runtime.getRuntime().availableProcessors() * 2),
            r -> {
                Thread t = new Thread(r, "FileVerification");
                t.setDaemon(true);
                return t;
            });

    private enum VerificationStatus {
        CHECKING, EXISTS, MISSING, ERROR
    }

    public FileListController(AppConfigService appConfigService, FileService fileService, UserService userService,
            Session session,
            FolderManagerService folderManagerService) {
        this.appConfigService = appConfigService;
        this.fileService = fileService;
        this.userService = userService;
        this.session = session;
        this.folderManagerService = folderManagerService;
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

        setupCheckboxColumn();
        setupSelectionTracking();
    }

    private void setupDatePickers() {
        StringConverter<LocalDate> converter = new StringConverter<>() {
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
        colName.setCellValueFactory(c -> new SimpleStringProperty(formatFileName(c.getValue())));
        colDevice.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().deviceName()));
        colUser.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().username()));
        colSize.setCellValueFactory(c -> new SimpleStringProperty(formatSize(c.getValue().fileSize())));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(formatStatus(c.getValue().status())));
        colType.setCellValueFactory(c -> new SimpleStringProperty(formatType(c.getValue().type())));
        colDate.setCellValueFactory(c -> new SimpleStringProperty(formatDate(c.getValue().createDate())));
    }

    // Format file name with verification status (non-blocking)
    private String formatFileName(FileView fileView) {
        String fileName = fileView.name();
        String syncedPath = fileView.syncedPath();

        if (syncedPath == null || syncedPath.isEmpty()) {
            return "(MISSING) " + fileName;
        }

        // Check cache first
        VerificationStatus status = fileVerificationCache.get(syncedPath);

        if (status == null) {
            // Not checked yet - mark as checking and start async verification
            fileVerificationCache.put(syncedPath, VerificationStatus.CHECKING);
            startAsyncVerification(syncedPath);
            return fileName; // Show without prefix while checking
        }

        // Return based on cached status
        return switch (status) {
            case CHECKING -> fileName;
            case MISSING -> "(MISSING) " + fileName;
            case ERROR -> "(ERROR) " + fileName;
            case EXISTS -> fileName;
        };
    }

    // Start async verification for a file path
    private void startAsyncVerification(String syncedPath) {
        verificationExecutor.submit(() -> {
            try {
                PathResolutionResult result = folderManagerService.findAbsolutePathFromNonDriveLetterPath(syncedPath);

                VerificationStatus newStatus;
                if (result.isNotFound()) {
                    newStatus = VerificationStatus.MISSING;
                } else if (result.isError()) {
                    newStatus = VerificationStatus.ERROR;
                } else {
                    newStatus = VerificationStatus.EXISTS;
                }

                fileVerificationCache.put(syncedPath, newStatus);

                // Update UI on JavaFX thread
                Platform.runLater(() -> fileTable.refresh());
            } catch (Exception e) {
                log.error("File verification failed: {}", syncedPath, e);
                fileVerificationCache.put(syncedPath, VerificationStatus.ERROR);
                Platform.runLater(() -> fileTable.refresh());
            }
        });
    }

    public void filterByDevice(String hardwareId) {
        this.activeHardwareId = hardwareId;
        refresh(buildFilter());
    }

    @FXML
    public void clearFilter() {
        dateFromPicker.setValue(null);
        dateToPicker.setValue(null);
        userFilterCombo.getSelectionModel().selectFirst();
        typeFilterCombo.getSelectionModel().selectFirst();
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
        cbPageSize.getItems().setAll(AppConstants.PAGE_SIZE_THRESHOLDS);
        cbPageSize.setValue(AppConstants.DEFAULT_PAGE_SIZE);
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

        AppConstants.MEDIA_TYPES.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(type -> options.add(new TypeOption(type, I18n.get("file.type." + type))));

        typeFilterCombo.setItems(FXCollections.observableArrayList(options));
        typeFilterCombo.getSelectionModel().selectFirst();
    }

    public void onFileSyncCompleted(String syncedPath) {
        if (syncedPath != null && !syncedPath.isEmpty()) {
            fileVerificationCache.put(syncedPath, VerificationStatus.EXISTS);
        }
        Platform.runLater(() -> refresh(buildFilter()));
    }

    public void onFileBackupCompleted() {
        Platform.runLater(() -> refresh(buildFilter()));
    }

    /**
     * Cleanup resources when controller is no longer needed.
     * Shuts down the verification executor to prevent thread leaks.
     * Called during logout to ensure proper resource cleanup.
     */
    public void cleanup() {
        verificationExecutor.shutdownNow();
        fileVerificationCache.clear();
        log.debug("FileListController cleanup: executor shutdown, cache cleared");
    }

    private void setupCheckboxColumn() {
        chkSelectAll = new CheckBox();

        HBox header = new HBox(chkSelectAll);
        header.setAlignment(javafx.geometry.Pos.CENTER);
        colSelect.setGraphic(header);
        colSelect.setText(null);
        colSelect.setMinWidth(40);
        colSelect.setMaxWidth(40);
        colSelect.setPrefWidth(40);
        colSelect.setResizable(false);

        chkSelectAll.setOnAction(e -> {
            if (chkSelectAll.isIndeterminate()) {
                // Chọn tất cả các trang
                selectedItems.addAll(filteredFiles);
                chkSelectAll.setIndeterminate(false);
                chkSelectAll.setSelected(true);
            } else if (chkSelectAll.isSelected()) {
                selectedItems.addAll(filteredFiles);
            } else {
                selectedItems.clear();
            }
            fileTable.refresh();
        });

        selectedItems.addListener((SetChangeListener<FileView>) change -> {
            int count = selectedItems.size();
            int total = filteredFiles.size(); // tổng tất cả trang
            int pageTotal = fileTable.getItems().size(); // chỉ trang hiện tại

            btnDownload.setDisable(count == 0);

            if (count == 0) {
                lblSelectedCount.setText("");
                chkSelectAll.setSelected(false);
                chkSelectAll.setIndeterminate(false);
            } else if (count == total) {
                lblSelectedCount.setText(I18n.get("export.file.select.all").replace("[x]", String.valueOf(total)));
                chkSelectAll.setIndeterminate(false);
                chkSelectAll.setSelected(true);
            } else {
                lblSelectedCount.setText(I18n.get("export.file.select.number").replace("[x]", String.valueOf(count)));
                boolean anyOnPage = fileTable.getItems().stream().anyMatch(selectedItems::contains);
                chkSelectAll.setIndeterminate(anyOnPage);
                chkSelectAll.setSelected(!anyOnPage);
            }
        });

        colSelect.setCellValueFactory(data ->
                new SimpleBooleanProperty(selectedItems.contains(data.getValue())));

        colSelect.setCellFactory(col -> new TableCell<>() {
            private final CheckBox cb = new CheckBox();

            {
                cb.setOnAction(e -> {
                    FileView item = getTableView().getItems().get(getIndex());
                    if (cb.isSelected()) selectedItems.add(item);
                    else selectedItems.remove(item);
                    lastSelectedIndex = getIndex();
                });
            }

            @Override
            protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || getIndex() < 0) {
                    setGraphic(null);
                    return;
                }
                cb.setSelected(selectedItems.contains(getTableView().getItems().get(getIndex())));
                setGraphic(cb);
            }
        });

        // Shift + Ctrl xử lý qua rowFactory
        fileTable.setRowFactory(tv -> {
            TableRow<FileView> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (row.isEmpty()) return;

                FileView item = row.getItem();
                int index = row.getIndex();

                if (e.isShiftDown() && lastSelectedIndex >= 0) {
                    // Shift+click: chọn range
                    int from = Math.min(lastSelectedIndex, index);
                    int to   = Math.max(lastSelectedIndex, index);
                    selectedItems.addAll(fileTable.getItems().subList(from, to + 1));
                    lastSelectedIndex = index;
                } else if (e.isControlDown()) {
                    // Ctrl+click: toggle
                    if (selectedItems.contains(item)) selectedItems.remove(item);
                    else selectedItems.add(item);
                    lastSelectedIndex = index;
                }
                // Click thường để checkbox tự xử lý qua cb.setOnAction

                fileTable.refresh();
            });
            return row;
        });
    }

    // Track index cuối cùng được chọn để hỗ trợ Shift+click
    private int lastSelectedIndex = -1;

    private void setupSelectionTracking() {
        selectedItems.addListener((SetChangeListener<FileView>) change -> {
            int count = selectedItems.size();
            lblSelectedCount.setText(I18n.get("export.file.select.number")
                    .replace("[x]", String.valueOf(count)));
            btnDownload.setDisable(count == 0);

            // Cập nhật trạng thái "Select All"
            chkSelectAll.setSelected(
                    !fileTable.getItems().isEmpty() &&
                            selectedItems.containsAll(fileTable.getItems()));
        });
    }

    @FXML
    private void onDownloadSelected(ActionEvent e) {
        List<FileView> toDownload = new ArrayList<>(selectedItems);
        if (toDownload.isEmpty())
            return;

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("setting.storage.chooser.title"));

        String defaultDir = appConfigService.getConfigValue(AppConstants.KEY_EXPORT_DIR);
        if (defaultDir != null && !defaultDir.isBlank()) {
            java.io.File defaultFolder = new java.io.File(defaultDir);
            if (defaultFolder.exists()) chooser.setInitialDirectory(defaultFolder);
        }

        Stage stage = (Stage) ((Node) e.getSource()).getScene().getWindow();
        java.io.File selectedFolder = chooser.showDialog(stage);
        if (selectedFolder == null) return;

        java.io.File destFolder = selectedFolder;
        if (!destFolder.exists()) destFolder.mkdirs();

        // Kiểm tra dung lượng
        long totalSize = toDownload.stream().mapToLong(FileView::fileSize).sum();
        long freeSpace = destFolder.getFreeSpace();
        if (totalSize > freeSpace) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(I18n.get("export.file.warning.title"));
            alert.setHeaderText(I18n.get("export.file.warning.header"));
            alert.setContentText(I18n.get("export.file.warning.content")
                    .replace("[x]", formatSize(totalSize))
                    .replace("[y]", formatSize(freeSpace)));
            alert.showAndWait();
            return;
        }

        btnDownload.setDisable(true);
        String downloadDir = destFolder.getAbsolutePath();

        Task<Void> copyTask = new Task<>() {
            int success = 0;
            int failed = 0;
            final List<String> failedFiles = new ArrayList<>();

            @Override
            protected Void call() {
                for (int i = 0; i < toDownload.size(); i++) {
                    FileView file = toDownload.get(i);
                    try {
                        long sourceSize = folderManagerService.resolveExistingFileSize(file.syncedPath());

                        if (!folderManagerService.hasSufficientSpace(destFolder, sourceSize)) {
                            failed++;
                            failedFiles.add(file.name() + I18n.get("export.file.warning.title"));
                            continue;
                        }

                        PathResolutionResult result = folderManagerService
                                .findAbsolutePathFromNonDriveLetterPath(file.syncedPath());

                        if (!result.isFound()) {
                            failed++;
                            failedFiles.add(file.name() + I18n.get("export.file.warning.notfound"));
                            continue;
                        }

                        java.io.File source = result.getPath().toFile();
                        java.io.File dest   = new java.io.File(destFolder, file.name());
                        dest = resolveConflict(dest);

                        java.nio.file.Files.copy(
                                source.toPath(),
                                dest.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        );
                        success++;

                    } catch (FileNotFoundOnAnyDriveException ex) {
                        failed++;
                        failedFiles.add(file.name() + I18n.get("export.file.warning.notfound"));
                        log.info("File not found on any drive: {}", file.syncedPath());

                    } catch (IOException ex) {
                        failed++;
                        failedFiles.add(file.name() + " (" + I18n.get("export.file.warning.error") + ex.getMessage() + ")");
                        log.error("Exception copying file {}: ", file.name(), ex);
                    }

                    updateProgress(i + 1, toDownload.size());
                }
                return null;
            }

            @Override
            protected void succeeded() {
                Platform.runLater(() -> {
                    btnDownload.setDisable(false);
                    showDownloadResult(success, failed, failedFiles, downloadDir);
                });
            }

            @Override
            protected void failed() {
                Platform.runLater(() -> {
                    btnDownload.setDisable(false);
                    log.error("Copy task failed: ", getException());
                });
            }
        };

        Thread thread = new Thread(copyTask);
        thread.setDaemon(true);
        thread.start();
    }

    private java.io.File resolveConflict(java.io.File file) {
        if (!file.exists())
            return file;

        String name = file.getName();
        String baseName = name.contains(".")
                ? name.substring(0, name.lastIndexOf('.'))
                : name;
        String ext = name.contains(".")
                ? name.substring(name.lastIndexOf('.'))
                : "";

        int count = 1;
        java.io.File newFile;
        do {
            newFile = new java.io.File(file.getParent(), baseName + "(" + count++ + ")" + ext);
        } while (newFile.exists());

        return newFile;
    }

    private void showDownloadResult(int success, int failed,
            List<String> failedFiles, String downloadDir) {
        Alert alert;
        if (failed == 0) {
            alert = AlertHelper.createInformation(I18n.get("notification.export.success.title"),
                    I18n.get("notification.export.status.success").replace("[x]", String.valueOf(success)),
                    downloadDir);
        } else {
            alert = AlertHelper.createWithScroll(Alert.AlertType.WARNING,
                    I18n.get("notification.export.failure.title"),
                    I18n.get("notification.export.status.failure")
                            .replace("[x]", String.valueOf(success))
                            .replace("[y]", String.valueOf(failed)),
                    I18n.get("notification.export.list.failure")
                            .replace("[x]", String.join("\n", failedFiles)));
        }
        alert.showAndWait();
    }

}
