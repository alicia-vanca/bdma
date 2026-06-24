package com.app.admin.layout.controllers;

import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.common.modules.datarestore.events.FileRestoredEvent;
import com.app.common.definitions.AppConstants;
import com.app.common.dtos.FileFilter;
import com.app.common.dtos.FileListFilterState;
import com.app.common.dtos.FileView;
import com.app.common.events.UserAutoCreatedEvent;
import com.app.common.helpers.DialogHelper;
import com.app.common.modules.databackup.events.FileBackupCompletedEvent;
import com.app.common.modules.dataexport.services.DataExportService;
import com.app.common.modules.datasync.events.FileSyncCompletedEvent;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.media.services.MediaViewerService;
import com.app.common.modules.queuemanager.controllers.QueueDialogController;
import com.app.common.modules.queuemanager.dtos.QueueProgressSummary;
import com.app.common.modules.queuemanager.enums.QueueType;
import com.app.common.modules.queuemanager.events.QueueProgressChangedEvent;
import com.app.common.modules.queuemanager.events.QueueStatusChangedEvent;
import com.app.common.modules.session.Session;
import com.app.common.services.FileService;
import com.app.common.services.UserService;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;
import lombok.Setter;

@Component
public class FileListController {

    private static final Logger log = LoggerFactory.getLogger(FileListController.class);

    private static final DateTimeFormatter DATE_PICKER_FORMATTER = DateTimeFormatter
            .ofPattern(AppConstants.DATE_PICKER_FORMAT);
    private static final DateTimeFormatter DATE_DISPLAY_FORMATTER = DateTimeFormatter
            .ofPattern(AppConstants.DATE_DISPLAY_FORMAT);
    private static final Duration PROGRESS_BAR_COMPLETION_GRACE = Duration.seconds(5);
    private static final Duration PROGRESS_BAR_FADE_DURATION = Duration.millis(180);
    private static final double PROGRESS_BAR_IDLE_OPACITY = 0.62;
    private static final double PROGRESS_BAR_ACTIVE_OPACITY = 1.0;
    private static final String STYLE_IDLE = "idle";
    private static final String STYLE_SYNCING = "syncing";
    private static final String STYLE_EXPORTING = "exporting";
    private static final String STYLE_COMPLETE = "complete";

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
    private TableColumn<FileView, Boolean> colSelect;
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
    private ComboBox<Integer> cbPageSize;
    @FXML
    private Button btnExportSelected;
    @FXML
    private Label lblSelectedCount;
    @FXML
    private Button btnDropSelected;
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
    @FXML
    private Button btnOpenQueueDialog;
    @FXML
    private HBox progressBarHeader;
    @FXML
    private Label lblProgressBarTitle;
    @FXML
    private Region progressBarSpacer;
    @FXML
    private Label lblProgressBarPercent;
    @FXML
    private ProgressBar progressBar;

    private final FileService fileService;
    private final UserService userService;
    private final Session session;
    private final FolderManagerService folderManagerService;
    private final FileListFilterState filterState;
    private final DataExportService dataExportService;
    private final MediaViewerService mediaViewerService;

    private String activeCameraId;
    private boolean initializing = true;
    private List<FileView> filteredFiles = new ArrayList<>();
    private int pageSize = AppConstants.DEFAULT_PAGE_SIZE;
    private int currentPageIndex = 0;
    @Setter
    private Runnable onClearFilter;
    private Stage queueDialogStage;

    private final Map<String, VerificationStatus> fileVerificationCache = new ConcurrentHashMap<>();
    private final Map<String, FileView> cachedFileViews = new ConcurrentHashMap<>();
    private final Map<String, SimpleBooleanProperty> selectionStateByKey = new ConcurrentHashMap<>();
    private final CheckBox selectAllCheckBox = new CheckBox();
    private final PauseTransition progressBarCompletionGrace = new PauseTransition(PROGRESS_BAR_COMPLETION_GRACE);
    private final AtomicReference<QueueProgressSummary> latestSyncProgress = new AtomicReference<>();
    private final AtomicReference<QueueProgressSummary> latestExportProgress = new AtomicReference<>();
    private FadeTransition progressBarFade;
    private QueueProgressSummary visibleProgress;
    private boolean visibleProgressComplete;
    private double progressBarTargetOpacity = Double.NaN;
    private boolean refreshingSelectAllState;
    private boolean applyingPageSelection;
    private String selectionAnchorKey;

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

    public FileListController(FileService fileService, UserService userService,
            Session session,
            FolderManagerService folderManagerService,
            FileListFilterState filterState,
            DataExportService dataExportService,
            MediaViewerService mediaViewerService) {
        this.fileService = fileService;
        this.userService = userService;
        this.session = session;
        this.folderManagerService = folderManagerService;
        this.filterState = filterState;
        this.dataExportService = dataExportService;
        this.mediaViewerService = mediaViewerService;
    }

    @FXML
    public void initialize() {
        setupColumns();
        setupRowDoubleClick();
        setupDatePickers();
        setupSelectionHeader();
        setupProgressBarButton();

        boolean isAdmin = session.isAdmin();
        userFilterCombo.setVisible(isAdmin);
        userFilterCombo.setManaged(isAdmin);

        if (isAdmin) {
            loadUsers();
        }
        loadTypes();
        setupPageSizeComboBox();
        setupPageNumberInput();
        restoreFilterState();
        initializing = true;

        setupAutoFilter();

        initializing = false;
        this.activeCameraId = filterState.get().getCameraId();
        refresh(buildFilter());
    }

    private void restoreFilterState() {
        FileFilter f = filterState.get();
        if (f.getDateFrom() != null) {
            dateFromPicker.setValue(f.getDateFrom());
        }
        if (f.getDateTo() != null) {
            dateToPicker.setValue(f.getDateTo());
        }
        if (f.getUserId() != null) {
            userFilterCombo.getItems().stream()
                    .filter(o -> Objects.equals(o.id(), f.getUserId()))
                    .findFirst()
                    .ifPresent(userFilterCombo.getSelectionModel()::select);
        }
        if (f.getType() != null) {
            typeFilterCombo.getItems().stream()
                    .filter(o -> Objects.equals(o.key(), f.getType()))
                    .findFirst()
                    .ifPresent(typeFilterCombo.getSelectionModel()::select);
        }
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

        dateFromPicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            dateToPicker.setDayCellFactory(newVal == null ? null : picker -> new DateCell() {
                @Override
                public void updateItem(LocalDate date, boolean empty) {
                    super.updateItem(date, empty);
                    setDisable(date.isBefore(newVal));
                }
            });
            if (newVal != null && dateToPicker.getValue() != null
                    && dateToPicker.getValue().isBefore(newVal)) {
                dateToPicker.setValue(null);
            }
        });

        dateToPicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            dateFromPicker.setDayCellFactory(newVal == null ? null : picker -> new DateCell() {
                @Override
                public void updateItem(LocalDate date, boolean empty) {
                    super.updateItem(date, empty);
                    setDisable(date.isAfter(newVal));
                }
            });
            if (newVal != null && dateFromPicker.getValue() != null
                    && dateFromPicker.getValue().isAfter(newVal)) {
                dateFromPicker.setValue(null);
            }
        });
    }

    private void setupColumns() {
        colSelect.setCellValueFactory(cellData -> getSelectionProperty(cellData.getValue()));
        colSelect.setCellFactory(column -> createSelectionCell());
        colSelect.setEditable(true);
        colSelect.setSortable(false);
        fileTable.setEditable(true);

        colName.setCellValueFactory(c -> new SimpleStringProperty(formatFileName(c.getValue())));
        colName.setSortable(false);
        colDevice.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().deviceName()));
        colDevice.setSortable(false);
        colUser.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().username()));
        colUser.setSortable(false);
        colSize.setCellValueFactory(c -> new SimpleStringProperty(formatSize(c.getValue().fileSize())));
        colSize.setSortable(false);
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(formatStatus(c.getValue().status())));
        colStatus.setSortable(false);
        colType.setCellValueFactory(c -> new SimpleStringProperty(formatType(c.getValue().type())));
        colType.setSortable(false);
        colDate.setCellValueFactory(c -> new SimpleStringProperty(formatDate(c.getValue().createDate())));
    }

    private TableCell<FileView, Boolean> createSelectionCell() {
        return new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();

            {
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setStyle("-fx-alignment: CENTER;");
                checkBox.setFocusTraversable(false);

                checkBox.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    if (applyingPageSelection || event.getButton() != MouseButton.PRIMARY || isEmpty()) {
                        event.consume();
                        return;
                    }

                    FileView fileView = getTableRow() == null ? null : getTableRow().getItem();
                    if (fileView == null) {
                        event.consume();
                        return;
                    }

                    // Keep the checkbox visual state controlled by the selection model.
                    // JavaFX reuses table cells while scrolling, so allowing the CheckBox
                    // to toggle itself can leave recycled cells visually out of sync.
                    handleSelectionClick(fileView, event.isShiftDown());
                    event.consume();
                });
            }

            @Override
            protected void updateItem(Boolean selected, boolean empty) {
                super.updateItem(selected, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    checkBox.setSelected(false);
                    setGraphic(null);
                    return;
                }

                FileView fileView = getTableRow().getItem();
                checkBox.setSelected(getSelectionProperty(fileView).get());
                setGraphic(checkBox);
            }
        };
    }

    private void handleSelectionClick(FileView fileView, boolean shiftDown) {
        String targetKey = selectionKey(fileView);
        if (shiftDown) {
            applyShiftSelection(targetKey);
            return;
        }

        boolean shouldSelect = isFileUnselected(targetKey);
        setFileSelected(targetKey, fileView, shouldSelect);
        selectionAnchorKey = targetKey;
        refreshSelectionUi();
    }

    /**
     * Selects a contiguous range on the current page using the last clicked
     * checkbox
     * as an anchor.
     */
    private void applyShiftSelection(String targetKey) {
        List<FileView> pageItems = fileTable.getItems();
        if (pageItems == null || pageItems.isEmpty()) {
            return;
        }

        int targetIndex = findPageIndexBySelectionKey(targetKey);
        int anchorIndex = selectionAnchorKey == null ? -1 : findPageIndexBySelectionKey(selectionAnchorKey);
        if (targetIndex < 0) {
            return;
        }
        if (anchorIndex < 0) {
            anchorIndex = targetIndex;
        }

        boolean shouldSelect = isFileUnselected(targetKey);
        int from = Math.min(anchorIndex, targetIndex);
        int to = Math.max(anchorIndex, targetIndex);
        for (int i = from; i <= to; i++) {
            FileView pageFileView = pageItems.get(i);
            setFileSelected(selectionKey(pageFileView), pageFileView, shouldSelect);
        }
        selectionAnchorKey = targetKey;
        refreshSelectionUi();
    }

    private int findPageIndexBySelectionKey(String key) {
        List<FileView> pageItems = fileTable.getItems();
        for (int i = 0; i < pageItems.size(); i++) {
            if (Objects.equals(selectionKey(pageItems.get(i)), key)) {
                return i;
            }
        }
        return -1;
    }

    private void setFileSelected(String key, FileView fileView, boolean selected) {
        cachedFileViews.put(key, fileView);
        getSelectionProperty(fileView).set(selected);
    }

    private boolean isFileUnselected(String key) {
        SimpleBooleanProperty property = selectionStateByKey.get(key);
        return property == null || !property.get();
    }

    private int getSelectedCount() {
        int count = 0;
        for (SimpleBooleanProperty property : selectionStateByKey.values()) {
            if (property.get()) {
                count++;
            }
        }
        return count;
    }

    private List<String> getSelectedKeys() {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, SimpleBooleanProperty> entry : selectionStateByKey.entrySet()) {
            if (entry.getValue().get()) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    private void refreshSelectionUi() {
        updateSelectionSummary();
        updateSelectAllHeaderState();
        fileTable.refresh();
    }

    private void setupSelectionHeader() {
        selectAllCheckBox.setFocusTraversable(false);
        selectAllCheckBox.setOnAction(e -> onSelectAllChanged());
        colSelect.setGraphic(selectAllCheckBox);
        updateSelectionSummary();
    }

    private SimpleBooleanProperty getSelectionProperty(FileView fileView) {
        String key = selectionKey(fileView);
        return selectionStateByKey.computeIfAbsent(key, k -> {
            SimpleBooleanProperty property = new SimpleBooleanProperty(false);
            property.addListener((obs, wasSelected, isSelected) -> {
                updateSelectionSummary();
                updateSelectAllHeaderState();
            });
            return property;
        });
    }

    private void onSelectAllChanged() {
        if (refreshingSelectAllState) {
            return;
        }

        // Select-all is a bulk action, so the next shift-click should start a new
        // range.
        selectionAnchorKey = null;

        boolean shouldSelect = selectAllCheckBox.isSelected();
        applySelectionToCurrentPage(shouldSelect);

        refreshSelectionUi();
    }

    /**
     * Applies the header checkbox state to every row in the current logical page,
     * including rows outside the visible viewport.
     *
     * @param selected target selected state for all rows on the current page
     */
    private void applySelectionToCurrentPage(boolean selected) {
        applyingPageSelection = true;
        try {
            for (FileView fileView : List.copyOf(fileTable.getItems())) {
                setFileSelected(selectionKey(fileView), fileView, selected);
            }
        } finally {
            applyingPageSelection = false;
        }
    }

    private void updateSelectAllHeaderState() {
        refreshingSelectAllState = true;
        try {
            List<FileView> pageItems = fileTable.getItems();
            if (pageItems == null || pageItems.isEmpty()) {
                selectAllCheckBox.setSelected(false);
                return;
            }

            // Header checkbox reflects selection state of the current page only.
            boolean allSelected = true;
            for (FileView fileView : pageItems) {
                if (isFileUnselected(selectionKey(fileView))) {
                    allSelected = false;
                    break;
                }
            }
            selectAllCheckBox.setSelected(allSelected);
        } finally {
            refreshingSelectAllState = false;
        }
    }

    private void updateSelectionSummary() {
        int selectedCount = getSelectedCount();
        lblSelectedCount.setText(I18n.get("file.selected.count.dynamic", selectedCount));
        boolean hasSelection = selectedCount > 0;
        lblSelectedCount.setManaged(hasSelection);
        lblSelectedCount.setVisible(hasSelection);
        btnDropSelected.setManaged(hasSelection);
        btnDropSelected.setVisible(hasSelection);
        btnDropSelected.setDisable(selectedCount == 0);
        btnExportSelected.setDisable(selectedCount == 0);
    }

    @FXML
    private void onDropSelected() {
        clearSelectionState();
        fileTable.refresh();
    }

    /**
     * Clear selected state without triggering table refresh.
     * Used by logout cleanup to avoid cell re-evaluation during scene teardown.
     */
    private void clearSelectionState() {
        // Create a copy to avoid concurrent modifications while listeners update sets.
        Set<String> keys = new HashSet<>(selectionStateByKey.keySet());
        for (String key : keys) {
            SimpleBooleanProperty property = selectionStateByKey.get(key);
            if (property != null) {
                property.set(false);
            }
        }
        selectionAnchorKey = null;
        updateSelectionSummary();
        updateSelectAllHeaderState();
    }

    @FXML
    private void onExportSelected() {
        List<FileView> selected = new ArrayList<>();
        for (String key : getSelectedKeys()) {
            FileView fileView = cachedFileViews.get(key);
            if (fileView != null) {
                selected.add(fileView);
            }
        }

        if (selected.isEmpty()) {
            return;
        }

        dataExportService.exportSelectedFiles(selected);
    }

    private void setupProgressBarButton() {
        progressBarCompletionGrace.setOnFinished(event -> finishProgressGrace());
        makeProgressGraphicClickThrough();
        renderPreferredProgress();
    }

    /**
     * Lets the button receive clicks anywhere in the compact progress graphic,
     * including the embedded progress bar skin nodes.
     */
    private void makeProgressGraphicClickThrough() {
        Node graphic = btnOpenQueueDialog.getGraphic();
        if (graphic != null) {
            graphic.setMouseTransparent(true);
        }
    }

    @EventListener
    public void onQueueProgressChanged(QueueProgressChangedEvent event) {
        Platform.runLater(() -> {
            rememberProgress(event.summary());
            renderPreferredProgress();
        });
    }

    @EventListener
    public void onQueueStatusChanged(QueueStatusChangedEvent event) {
        Platform.runLater(() -> {
            if (isProgressBarViewReady()) {
                resetRemovedSyncProgress(event);
            }
        });
    }

    private void resetRemovedSyncProgress(QueueStatusChangedEvent event) {
        if (event.getQueueType() != QueueType.SYNC
                || !event.isFullRefreshRequired()
                || visibleProgress == null
                || visibleProgressComplete
                || visibleProgress.kind() != QueueProgressSummary.Kind.SYNC) {
            return;
        }

        // Preflight disconnect removes active sync work without a successful result.
        latestSyncProgress.set(null);
        visibleProgress = null;
        progressBarCompletionGrace.stop();
        renderPreferredProgress();
    }

    private void rememberProgress(QueueProgressSummary progress) {
        if (progress == null) {
            return;
        }
        if (progress.complete() && !isProgressBarViewReady()) {
            clearRememberedProgress(progress);
            return;
        }

        if (progress.kind() == QueueProgressSummary.Kind.SYNC) {
            latestSyncProgress.set(progress);
        } else if (progress.kind() == QueueProgressSummary.Kind.EXPORT) {
            latestExportProgress.set(progress);
        } else {
            log.warn("Unhandled progress kind: {}", progress.kind());
        }
    }

    private void renderPreferredProgress() {
        if (!isProgressBarViewReady()) {
            return;
        }

        if (progressBarCompletionGrace.getStatus() == Animation.Status.RUNNING && visibleProgress != null) {
            applyProgress(visibleProgress, true);
            return;
        }

        QueueProgressSummary preferredProgress = choosePreferredProgress();
        if (preferredProgress == null) {
            visibleProgress = null;
            visibleProgressComplete = false;
            applyIdleProgress();
            return;
        }

        boolean complete = preferredProgress.complete();
        visibleProgress = preferredProgress;
        visibleProgressComplete = complete;
        applyProgress(preferredProgress, complete);

        if (complete) {
            clearRememberedProgress(preferredProgress);
            progressBarCompletionGrace.playFromStart();
        }
    }

    private QueueProgressSummary choosePreferredProgress() {
        QueueProgressSummary syncProgress = latestSyncProgress.get();
        if (syncProgress != null) {
            return syncProgress;
        }
        return latestExportProgress.get();
    }

    /**
     * Drops completed compact progress from local candidates immediately. The
     * visible snapshot stays alive for the grace window only, so page refresh or
     * language re-render cannot replay finished progress from remembered state.
     */
    private void clearRememberedProgress(QueueProgressSummary progress) {
        if (progress.kind() == QueueProgressSummary.Kind.SYNC) {
            latestSyncProgress.set(null);
        } else if (progress.kind() == QueueProgressSummary.Kind.EXPORT) {
            latestExportProgress.set(null);
        }
    }

    private void finishProgressGrace() {
        visibleProgress = null;
        visibleProgressComplete = false;
        renderPreferredProgress();
    }

    private void applyIdleProgress() {
        lblProgressBarTitle.setText(I18n.get("progress.bar.idle"));
        lblProgressBarPercent.setText("");
        lblProgressBarPercent.setManaged(false);
        lblProgressBarPercent.setVisible(false);
        progressBarSpacer.setManaged(false);
        progressBarSpacer.setVisible(false);
        progressBarHeader.setAlignment(Pos.CENTER);
        progressBar.setProgress(0);

        btnOpenQueueDialog.getStyleClass().removeAll(STYLE_IDLE, STYLE_SYNCING, STYLE_EXPORTING, STYLE_COMPLETE);
        btnOpenQueueDialog.getStyleClass().add(STYLE_IDLE);
        animateProgressBarOpacity(PROGRESS_BAR_IDLE_OPACITY);
    }

    private void applyProgress(QueueProgressSummary progress, boolean complete) {
        if (!isProgressBarViewReady()) {
            return;
        }

        lblProgressBarTitle.setText(resolveProgressTitle(progress, complete));
        lblProgressBarPercent.setText(progress.progress() + "%");
        lblProgressBarPercent.setManaged(true);
        lblProgressBarPercent.setVisible(true);
        progressBarSpacer.setManaged(true);
        progressBarSpacer.setVisible(true);
        progressBarHeader.setAlignment(Pos.CENTER_LEFT);
        progressBar.setProgress(progress.progress() / 100.0);

        btnOpenQueueDialog.getStyleClass().removeAll(STYLE_IDLE, STYLE_SYNCING, STYLE_EXPORTING, STYLE_COMPLETE);
        if (complete) {
            btnOpenQueueDialog.getStyleClass().add(STYLE_COMPLETE);
        } else {
            btnOpenQueueDialog.getStyleClass().add(progress.kind() == QueueProgressSummary.Kind.SYNC
                    ? STYLE_SYNCING
                    : STYLE_EXPORTING);
        }
        animateProgressBarOpacity(PROGRESS_BAR_ACTIVE_OPACITY);
    }

    private String resolveProgressTitle(QueueProgressSummary progress, boolean complete) {
        if (progress.kind() == QueueProgressSummary.Kind.SYNC) {
            return complete
                    ? MessageFormat.format(I18n.get("progress.bar.sync.complete"), progress.name())
                    : MessageFormat.format(I18n.get("progress.bar.syncing"), progress.name());
        }
        return complete
                ? I18n.get("progress.bar.export.complete")
                : MessageFormat.format(I18n.get("progress.bar.exporting"), progress.total());
    }

    /**
     * Smooths progress bar state changes so idle and active states do not snap
     * between opacity values. Page reloads inject a new button into the reused
     * controller, so the node's current opacity must also match the target.
     */
    private void animateProgressBarOpacity(double targetOpacity) {
        if (Double.compare(progressBarTargetOpacity, targetOpacity) == 0
                && Double.compare(btnOpenQueueDialog.getOpacity(), targetOpacity) == 0) {
            return;
        }

        progressBarTargetOpacity = targetOpacity;
        if (progressBarFade != null) {
            progressBarFade.stop();
        }

        progressBarFade = new FadeTransition(PROGRESS_BAR_FADE_DURATION, btnOpenQueueDialog);
        progressBarFade.setFromValue(btnOpenQueueDialog.getOpacity());
        progressBarFade.setToValue(targetOpacity);
        progressBarFade.play();
    }

    /**
     * Queue events can arrive while this Spring controller exists but before its
     * FXML fields are injected, for example during nested login dialogs.
     */
    private boolean isProgressBarViewReady() {
        return btnOpenQueueDialog != null
                && progressBarHeader != null
                && lblProgressBarTitle != null
                && progressBarSpacer != null
                && lblProgressBarPercent != null
                && progressBar != null;
    }

    @FXML
    private void onOpenQueueDialog() {
        // Keep a single shared queue window while this view is active.
        if (queueDialogStage != null && queueDialogStage.isShowing()) {
            queueDialogStage.toFront();
            queueDialogStage.requestFocus();
            return;
        }

        DialogHelper.Dialog<QueueDialogController> dialog = DialogHelper.createDialog(
                "/fxml/common/queue/queue-dialog.fxml",
                I18n.get("queue.dialog.title"),
                Modality.NONE);

        queueDialogStage = dialog.stage();
        queueDialogStage.setOnShown(event -> {
            if (dialog.controller() != null) {
                dialog.controller().refreshAllTabs();
            }
        });

        queueDialogStage.setOnHidden(event -> queueDialogStage = null);
        queueDialogStage.show();
    }

    // Close queue dialog if open to avoid dangling windows during
    // logout/navigation.
    public void closeQueueDialog() {
        if (queueDialogStage != null && queueDialogStage.isShowing()) {
            queueDialogStage.close();
        }
    }

    // Format file name with verification status (non-blocking)
    private String formatFileName(FileView fileView) {
        String fileName = fileView.name();
        String displayName = shortenFileName(fileName);
        String syncedPath = fileView.syncedPath();

        if (syncedPath == null || syncedPath.isEmpty()) {
            return "(MISSING) " + displayName;
        }

        // Render the stable shortened name while verification runs; only status
        // prefixes are added later so frequent table refreshes do not alternate
        // between full and shortened names.
        VerificationStatus status = fileVerificationCache.computeIfAbsent(syncedPath, path -> {
            startAsyncVerification(path, fileView.fileSize());
            return VerificationStatus.CHECKING;
        });

        return switch (status) {
            case CHECKING, EXISTS -> displayName;
            case MISSING -> "(MISSING) " + displayName;
            case ERROR -> "(ERROR) " + displayName;
        };
    }

    // Start async verification for a file path
    private void startAsyncVerification(String syncedPath, long expectedSize) {
        verificationExecutor.submit(() -> {
            try {
                PathResolutionResult result = folderManagerService.findAbsolutePathFromNonDriveLetterPath(syncedPath,
                        expectedSize);

                VerificationStatus newStatus;
                if (result.isNotFound()) {
                    newStatus = VerificationStatus.MISSING;
                } else if (result.isError()) {
                    newStatus = VerificationStatus.ERROR;
                } else {
                    newStatus = VerificationStatus.EXISTS;
                }

                fileVerificationCache.put(syncedPath, newStatus);

                // Update UI only while this Spring controller has an active FXML view.
                Platform.runLater(this::refreshFileTableIfReady);
            } catch (Exception e) {
                log.error("File verification failed: {}", syncedPath, e);
                fileVerificationCache.put(syncedPath, VerificationStatus.ERROR);
                Platform.runLater(this::refreshFileTableIfReady);
            }
        });
    }

    private void refreshFileTableIfReady() {
        if (isViewReady()) {
            fileTable.refresh();
        }
    }

    private boolean isViewReady() {
        return dateFromPicker != null
                && dateToPicker != null
                && userFilterCombo != null
                && typeFilterCombo != null
                && fileTable != null;
    }

    public void filterByDevice(String cameraId) {
        this.activeCameraId = cameraId;
        filterState.get().setCameraId(cameraId);
        refresh(buildFilter());
    }

    public String getActiveCameraId() {
        return activeCameraId;
    }

    public void clearDeviceFilter() {
        this.activeCameraId = null;
        filterState.get().setCameraId(null);
        refresh(buildFilter());
    }

    @FXML
    public void clearFilter() {
        filterState.clear();
        dateFromPicker.setValue(null);
        dateToPicker.setValue(null);
        userFilterCombo.getSelectionModel().selectFirst();
        typeFilterCombo.getSelectionModel().selectFirst();
        activeCameraId = null;
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
        cacheLoadedFiles(files);
        filteredFiles = new ArrayList<>(files);
        currentPageIndex = 0;
        setupPagination();
    }

    private void cacheLoadedFiles(List<FileView> files) {
        for (FileView fileView : files) {
            cachedFileViews.put(selectionKey(fileView), fileView);
        }
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
        updateSelectionSummary();
        updateSelectAllHeaderState();
        fileTable.refresh();
    }

    private void updateTablePage() {
        int from = currentPageIndex * pageSize;
        int to = Math.min(from + pageSize, filteredFiles.size());
        fileTable.setItems(FXCollections.observableArrayList(
                from < to ? filteredFiles.subList(from, to) : List.of()));
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

    private void buildPageButtons(int pageCount) {
        pageButtonsBox.getChildren().clear();

        List<Integer> pages = getPageRange(pageCount);
        for (int page : pages) {
            Button btn = new Button(String.valueOf(page + 1));
            btn.getStyleClass().add("btn-secondary");
            btn.getStyleClass().add("btn-pagination");
            if (page == currentPageIndex) {
                btn.getStyleClass().add("btn-page-active");
            }
            btn.setOnAction(e -> {
                currentPageIndex = page;
                setupPagination();
            });
            pageButtonsBox.getChildren().add(btn);
        }
    }

    private List<Integer> getPageRange(int pageCount) {
        if (pageCount <= 7) {
            List<Integer> pages = new ArrayList<>();
            for (int i = 0; i < pageCount; i++)
                pages.add(i);
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
        for (int i = start; i <= end; i++)
            pages.add(i);
        return pages;
    }

    private void setupPageNumberInput() {
        txtPageNumber.setOnAction(e -> jumpToPage());
        txtPageNumber.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            boolean focused = isFocused;
            if (!focused) {
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
        } catch (NumberFormatException ignored) {
            txtPageNumber.setText(String.valueOf(currentPageIndex + 1));
        }
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

        filter.setCameraId(activeCameraId);
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

    public void reloadUserFilter() {
        if (!session.isAdmin())
            return;
        Platform.runLater(() -> {
            if (isViewReady()) {
                loadUsers();
            }
        });
    }

    // Auto-refresh file list when any filter changes, skip during initial setup to
    // avoid redundant queries
    private void setupAutoFilter() {
        dateFromPicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) {
                return;
            }
            filterState.get().setDateFrom(newVal);
            refresh(buildFilter());
        });

        dateToPicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) {
                return;
            }
            filterState.get().setDateTo(newVal);
            refresh(buildFilter());
        });

        userFilterCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) {
                return;
            }
            filterState.get().setUserId(newVal != null ? newVal.id() : null);
            refresh(buildFilter());
        });

        typeFilterCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (initializing) {
                return;
            }
            filterState.get().setType(newVal != null ? newVal.key() : null);
            refresh(buildFilter());
        });
    }

    private void loadTypes() {
        var options = new ArrayList<TypeOption>();
        options.add(new TypeOption(null, I18n.get("filter.allTypes")));

        AppConstants.MEDIA_TYPES.stream()
                .map(type -> new TypeOption(type, I18n.get("file.type." + type)))
                .sorted((left, right) -> left.label().compareToIgnoreCase(right.label()))
                .forEach(options::add);

        typeFilterCombo.setItems(FXCollections.observableArrayList(options));
        typeFilterCombo.getSelectionModel().selectFirst();
    }

    @EventListener
    public void onUserAutoCreated(UserAutoCreatedEvent event) {
        reloadUserFilter();
    }

    @EventListener
    public void onFileSyncCompleted(FileSyncCompletedEvent event) {
        refreshAfterFileSync(event.getSyncedPath());
    }

    public void refreshAfterFileSync(String syncedPath) {
        if (syncedPath != null && !syncedPath.isEmpty()) {
            fileVerificationCache.put(syncedPath, VerificationStatus.EXISTS);
        }
        Platform.runLater(this::refreshCurrentFilterIfReady);
    }

    @EventListener(FileBackupCompletedEvent.class)
    public void onFileBackupCompleted() {
        Platform.runLater(this::refreshCurrentFilterIfReady);
    }

    @EventListener
    public void onFileRestored(FileRestoredEvent event) {
        if (event.syncedPath() != null && !event.syncedPath().isBlank()) {
            fileVerificationCache.remove(event.syncedPath());
        }
        Platform.runLater(this::refreshCurrentFilterIfReady);
    }

    private void refreshCurrentFilterIfReady() {
        if (isViewReady()) {
            refresh(buildFilter());
        }
    }

    private String selectionKey(FileView fileView) {
        if (fileView.fileId() != null) {
            return "id:" + fileView.fileId();
        }
        if (fileView.syncedPath() != null && !fileView.syncedPath().isBlank()) {
            return "path:" + fileView.syncedPath();
        }
        if (fileView.name() != null && fileView.createDate() != null) {
            return "name-date:" + fileView.name() + ":" + fileView.createDate();
        }
        if (fileView.name() != null) {
            return "name:" + fileView.name();
        }
        return "unknown:" + System.identityHashCode(fileView);
    }

    /**
     * Clear all selected file state when leaving dashboard view.
     */
    public void resetSelectionState() {
        clearSelectionState();
    }

    /**
     * Clears view-scoped state during logout while keeping this reusable Spring
     * controller able to verify files after the next login.
     */
    public void cleanup() {
        closeQueueDialog();
        filterState.clear();
        resetSelectionState();
        cachedFileViews.clear();
        fileVerificationCache.clear();
    }

    private String shortenFileName(String fileName) {
        if (fileName == null)
            return "";
        int dotIndex = fileName.lastIndexOf('.');
        String ext = dotIndex >= 0 ? fileName.substring(dotIndex) : "";
        String name = dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
        String[] parts = name.split("_");

        if (parts.length < 5) {
            return fileName;
        }
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 3; i < parts.length; i++) {
            sb.append('_').append(parts[i]);
        }
        sb.append(ext);
        return sb.toString();
    }

    private void setupRowDoubleClick() {
        fileTable.setRowFactory(tv -> {
            TableRow<FileView> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    FileView clicked = row.getItem();
                    if (!mediaViewerService.isViewable(clicked.type()))
                        return;

                    Stage owner = (Stage) fileTable.getScene().getWindow();
                    mediaViewerService.open(filteredFiles, clicked, owner);
                }
            });
            return row;
        });
    }

}