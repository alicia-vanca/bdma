package com.app.common.modules.externalmediadecrypt.controllers;

import com.app.common.definitions.AppConstants;
import com.app.common.events.FailureSummaryRequestedEvent;
import com.app.common.helpers.AlertHelper;
import com.app.common.modules.externalmediadecrypt.services.ExternalMediaDecryptService;
import com.app.common.modules.externalmediadecrypt.services.ExternalMediaDecryptService.DecryptionResult;
import com.app.common.modules.externalmediadecrypt.states.ExternalMediaDecryptState;
import com.app.common.modules.externalmediadecrypt.workers.ExternalMediaDecryptWorker;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.services.AppConfigService;
import com.app.common.services.UserSettingService;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ExternalMediaDecryptController {
    private static final Logger log = LoggerFactory.getLogger(ExternalMediaDecryptController.class);
    private static final String MP4_PATTERN = "*.mp4";
    private static final String MP4_SUFFIX = ".mp4";
    private final List<File> inputFiles = new ArrayList<>();
    private final Session session;
    private Task<List<DecryptionResult>> decryptionTask;
    private final UserSettingService userSettingService;
    private final AppConfigService appConfigService;
    private static final double PREF_HEIGHT = 34.0;
    private static final double LIST_ITEM_HEIGHT = 34.0;
    private static final double MAX_LIST_VIEW_HEIGHT = 300.0;
    private static final double PADDING = 16.0;
    private static final double BORDER = 2;
    private final ExternalMediaDecryptState state;
    private String inputFolderPath;
    private final ExternalMediaDecryptService externalMediaDecryptService;
    private final ApplicationEventPublisher eventPublisher;
    @FXML
    private StackPane progressCardHost;
    @FXML
    private Label progressOutputFolderLabel;
    @FXML
    private Label progressFileNameLabel;
    @FXML
    private Label progressCountLabel;
    @FXML
    private ListView<String> inputFileListView;
    @FXML
    private Button chooseInputFilesButton;
    @FXML
    private Button clearInputFilesButton;
    @FXML
    private VBox progressContainer;
    @FXML
    private TextField outputFolderField;
    @FXML
    private Button chooseOutputFolderButton;
    @FXML
    private Button decryptButton;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label progressPercentLabel;

    @FXML
    public void onChooseInputFiles() {
        chooseInputFiles();
    }

    @FXML
    public void onChooseOutputFolder() {
        chooseOutputFolder();
    }

    @FXML
    public void initialize() {
        inputFileListView.setPrefHeight(PREF_HEIGHT);
        configureProgressCard();
        configureInputFileList();
        restoreConfiguredFolders();
        restoreSelectedFiles(state);
        updateDecryptButtonText();
        restoreRunningTaskIfAny();
    }

    public ExternalMediaDecryptController(Session session, UserSettingService userSettingService,
            AppConfigService appConfigService, ExternalMediaDecryptState state,
            ExternalMediaDecryptService externalMediaDecryptService,
            ApplicationEventPublisher eventPublisher) {
        this.session = session;
        this.userSettingService = userSettingService;
        this.appConfigService = appConfigService;
        this.state = state;
        this.externalMediaDecryptService = externalMediaDecryptService;
        this.eventPublisher = eventPublisher;
    }

    private void chooseOutputFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("externalMediaDecrypt.chooseDirectory.title"));
        String currentOutputFolderPath = outputFolderField.getText();
        if (currentOutputFolderPath != null && !currentOutputFolderPath.isBlank()) {
            File currentOutputFolder = new File(currentOutputFolderPath);
            if (currentOutputFolder.exists()) {
                chooser.setInitialDirectory(currentOutputFolder);
            }
        }
        Stage stage = (Stage) outputFolderField.getScene().getWindow();
        File selectedFolder = chooser.showDialog(stage);
        if (selectedFolder != null) {
            String selectedOutputFolderPath = selectedFolder.getAbsolutePath();
            outputFolderField.setText(selectedOutputFolderPath);
            saveDecryptFolderPath(AppConstants.DECRYPT_OUTPUT_DIR, selectedOutputFolderPath);
            saveState();
        }
    }

    @FXML
    private void chooseInputFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("externalMediaDecrypt.chooseFile.title"));

        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        I18n.get("externalMediaDecrypt.chooser.encryptedMp4Files"),
                        MP4_PATTERN));
        String initialInputFolderPath = inputFolderPath;
        if (initialInputFolderPath != null && !initialInputFolderPath.isBlank()) {
            File initialInputFolder = new File(initialInputFolderPath);
            if (initialInputFolder.exists() && initialInputFolder.isDirectory()) {
                chooser.setInitialDirectory(initialInputFolder);
            }
        }
        Stage stage = (Stage) inputFileListView.getScene().getWindow();

        List<File> selectedInputFiles = chooser.showOpenMultipleDialog(stage);

        if (selectedInputFiles == null || selectedInputFiles.isEmpty()) {
            return;
        }

        for (File selectedInputFile : selectedInputFiles) {

            boolean alreadySelected = inputFiles.stream()
                    .anyMatch(file -> file.getAbsolutePath().equals(selectedInputFile.getAbsolutePath()));

            if (!alreadySelected) {
                inputFiles.add(selectedInputFile);
                inputFileListView.getItems().add(selectedInputFile.getAbsolutePath());
                updateClearButtonVisibility();
                updateDecryptButtonText();
                saveState();
            }
        }
        if (!selectedInputFiles.isEmpty()) {
            File parentFolder = selectedInputFiles.getFirst().getParentFile();
            String parentPath = parentFolder.getAbsolutePath();
            inputFolderPath = parentPath;
            saveDecryptFolderPath(AppConstants.DECRYPT_INPUT_DIR, parentPath);
        }

        updateListViewHeight();
    }

    private void saveDecryptFolderPath(String configKey, String folderPath) {
        if (session.isGuest()) {
            appConfigService.saveConfigValue(
                    configKey,
                    folderPath);
        } else {
            userSettingService.saveConfigValue(
                    session.getCurrentUserId(),
                    configKey,
                    folderPath);
        }
    }

    private void configureProgressCard() {
        progressContainer.maxWidthProperty().bind(progressCardHost.widthProperty().multiply(0.8));
        progressOutputFolderLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        progressFileNameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        progressCountLabel.setAlignment(Pos.CENTER_RIGHT);
        progressPercentLabel.setAlignment(Pos.CENTER_RIGHT);
    }

    private void configureInputFileList() {
        inputFileListView.setCellFactory(listView -> new ListCell<>() {

            private final Label fileNameLabel = new Label();
            private final Button removeButton = new Button();
            private final Tooltip tooltip = new Tooltip();

            private final HBox container = new HBox(15);

            {
                fileNameLabel.setMaxWidth(Double.MAX_VALUE);
                fileNameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

                HBox.setHgrow(fileNameLabel, Priority.ALWAYS);

                removeButton.setFocusTraversable(false);
                removeButton.setGraphic(createCloseIcon());
                removeButton.setAccessibleText(I18n.get("common.remove"));
                removeButton.getStyleClass().add("delete-button");

                container.setAlignment(Pos.CENTER_LEFT);
                container.getChildren().addAll(removeButton, fileNameLabel);

                removeButton.setOnAction(e -> {
                    String item = getItem();
                    if (item != null) {
                        getListView().getItems().remove(item);
                        inputFiles.removeIf(file -> file.getAbsolutePath().equals(item));
                        updateClearButtonVisibility();
                        updateDecryptButtonText();
                        updateListViewHeight();
                        saveState();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    fileNameLabel.setTooltip(null);
                } else {
                    fileNameLabel.setText(item);

                    tooltip.setText(item);
                    fileNameLabel.setTooltip(tooltip);

                    setText(null);
                    setGraphic(container);
                }
            }
        });
    }

    private StackPane createCloseIcon() {
        Region forwardLine = new Region();
        forwardLine.getStyleClass().add("close-line");
        forwardLine.setRotate(45);

        Region backwardLine = new Region();
        backwardLine.getStyleClass().add("close-line");
        backwardLine.setRotate(-45);

        StackPane closeIcon = new StackPane(forwardLine, backwardLine);
        closeIcon.getStyleClass().add("close-icon");
        closeIcon.setMouseTransparent(true);
        return closeIcon;
    }

    @FXML
    public void onDecrypt() {
        log.info("Decrypt button clicked. selectedFiles={}, serviceRunning={}", inputFiles.size(),
                externalMediaDecryptService.isRunning());
        if (externalMediaDecryptService.isRunning()) {
            log.warn("Decrypt start skipped because another decrypt task is already running.");
            restoreRunningTaskIfAny();
            return;
        }
        if (inputFiles.isEmpty()) {
            log.warn("Decrypt start blocked: no input files selected.");
            showAlert("externalMediaDecrypt.validate.emptyInput.title",
                    "externalMediaDecrypt.validate.emptyInput.header",
                    "externalMediaDecrypt.validate.emptyInput.message");
            return;
        }
        if (!hasValidInputFiles()) {
            log.warn("Decrypt start blocked: one or more input files are invalid.");
            showAlert("externalMediaDecrypt.validate.inputFile.title", "externalMediaDecrypt.validate.inputFile.header",
                    "externalMediaDecrypt.validate.inputFile.message");
            return;
        }
        if (!hasValidOutputFolder()) {
            log.warn("Decrypt start blocked: output folder is invalid. outputFolder={}", outputFolderField.getText());
            showAlert("externalMediaDecrypt.validate.outputFile.title",
                    "externalMediaDecrypt.validate.outputFile.header",
                    "externalMediaDecrypt.validate.outputFile.message");
            restoreConfiguredFolders();
            return;
        }
        progressContainer.setManaged(true);
        progressContainer.setVisible(true);
        String outputFolderPathText = outputFolderField.getText();
        Path outputFolderPath = Path.of(outputFolderPathText);
        List<Path> inputFilePaths = inputFiles.stream()
                .map(File::toPath)
                .toList();
        startDecrypt(inputFilePaths, outputFolderPath);
    }

    @FXML
    public void onClearInputFiles() {
        clearSelectedFiles();
    }

    private void clearSelectedFiles() {
        inputFiles.clear();

        inputFileListView.getItems().clear();

        inputFileListView.setPrefHeight(PREF_HEIGHT);
        updateClearButtonVisibility();
        updateDecryptButtonText();
        saveState();
    }

    private void updateClearButtonVisibility() {
        boolean hasFiles = !inputFiles.isEmpty();

        clearInputFilesButton.setVisible(hasFiles);
        clearInputFilesButton.setManaged(hasFiles);
    }

    private void updateDecryptButtonText() {
        int selectedFileCount = inputFiles.size();
        if (selectedFileCount == 0) {
            decryptButton.setText(I18n.get("externalMediaDecrypt.button"));
            return;
        }
        decryptButton.setText(I18n.get("externalMediaDecrypt.button.withCount", selectedFileCount));
    }

    @FXML
    public void onCancelDecrypt() {
        if (decryptionTask != null && decryptionTask.isRunning()) {
            confirmCancelDecrypt("externalMediaDecrypt.cancel.title", "externalMediaDecrypt.cancel.header",
                    "externalMediaDecrypt.cancel.message");
        }
    }

    private void showAlert(String titleKey, String headerKey, String messageKey, Object... args) {
        String message = I18n.get(messageKey, args);

        long lineCount = message.split("\\R").length;

        Alert alert = AlertHelper.createInformation(
                I18n.get(titleKey),
                I18n.get(headerKey),
                null);

        if (lineCount > 12) {

            Label label = new Label(message);
            label.setWrapText(true);
            label.getStyleClass().add("content"); // giữ style AlertHelper

            ScrollPane scrollPane = new ScrollPane(label);
            scrollPane.setFitToWidth(true);
            scrollPane.setPrefViewportHeight(200);

            // giữ style gốc của AlertHelper
            scrollPane.getStyleClass().add("content");

            alert.getDialogPane().setContent(scrollPane);

        } else {
            alert.setContentText(message);
        }

        alert.showAndWait();
    }

    private boolean hasValidInputFiles() {
        for (String inputFilePath : inputFiles.stream().map(File::getAbsolutePath).toList()) {
            if (inputFilePath == null || inputFilePath.isBlank()) {
                return false;
            }
            File inputFile = new File(inputFilePath);
            if (!inputFile.exists() || !isMp4File(inputFile)) {
                return false;
            }
        }
        log.info("Validation passed for input files.");
        return true;
    }

    private boolean isMp4File(File file) {
        String fileName = file.getName().toLowerCase(Locale.ROOT);
        return fileName.endsWith(MP4_SUFFIX);
    }

    private boolean hasValidOutputFolder() {
        String outputFolderPath = outputFolderField.getText();

        if (outputFolderPath == null || outputFolderPath.isBlank()) {
            return false;
        }

        File outputFolder = new File(outputFolderPath);
        return outputFolder.exists() && outputFolder.isDirectory();
    }

    private void restoreConfiguredFolders() {
        String outputFolderPath = resolveConfiguredFolderPath(AppConstants.DECRYPT_OUTPUT_DIR);
        outputFolderField.setText(outputFolderPath);
        inputFolderPath = resolveConfiguredFolderPath(AppConstants.DECRYPT_INPUT_DIR);
    }

    private String resolveConfiguredFolderPath(String configKey) {
        String folderPath = "";
        try {
            if (session.isGuest()) {
                folderPath = appConfigService.getConfigValue(configKey);

            } else {
                folderPath = userSettingService.getConfigValue(session.getCurrentUserId(), configKey);
            }

            String downloadsPath = Path.of(System.getProperty("user.home"), "Downloads").toString();
            if (folderPath == null || folderPath.isBlank()) {
                folderPath = downloadsPath;
            }
            Path folder = Paths.get(folderPath);
            Files.createDirectories(folder);
        } catch (IOException e) {
            log.error("Failed to create configured folder {}", folderPath, e);
        }
        return folderPath;
    }

    private void startDecrypt(List<Path> inputFilePaths, Path outputFolderPath) {
        decryptionTask = externalMediaDecryptService.startDecrypt(inputFilePaths, outputFolderPath);
        if (decryptionTask == null) {
            log.warn("Decrypt task was not created. Restoring any running task.");
            restoreRunningTaskIfAny();
            return;
        }
        decryptionTask.setOnSucceeded(event -> {
            log.info("Decrypt task succeeded. taskId={}, resultCount={}", System.identityHashCode(decryptionTask),
                    decryptionTask.getValue() == null ? 0 : decryptionTask.getValue().size());
            hideProgress();
            if (!externalMediaDecryptService.isShutdownRequested()) {
                showSummary(decryptionTask.getValue());
            }
        });
        decryptionTask.setOnFailed(event -> {
            hideProgress();
            Throwable ex = decryptionTask.getException();
            log.error("Batch decryption encountered a critical error: {}", ex.getMessage(), ex);
            showAlert(
                    "externalMediaDecrypt.error.critical.title",
                    "externalMediaDecrypt.error.critical.header",
                    "externalMediaDecrypt.error.critical.message",
                    ex.getMessage());
        });
        decryptionTask.setOnCancelled(event -> {
            log.info("Decrypt task cancelled. taskId={}, resultCount={}", System.identityHashCode(decryptionTask),
                    getCancelledResults(decryptionTask).size());
            hideProgress();
            if (!externalMediaDecryptService.isShutdownRequested()) {
                showSummary(getCancelledResults(decryptionTask));
            }
        });
        bindProgress(decryptionTask);
    }

    private List<DecryptionResult> getCancelledResults(Task<List<DecryptionResult>> task) {
        if (task instanceof ExternalMediaDecryptWorker worker) {
            return worker.getResultsSnapshot();
        }
        return List.of();
    }

    private void bindProgress(Task<?> task) {
        progressBar.progressProperty().unbind();
        progressBar.visibleProperty().unbind();

        progressPercentLabel.textProperty().unbind();
        progressPercentLabel.visibleProperty().unbind();

        progressOutputFolderLabel.textProperty().unbind();
        progressFileNameLabel.textProperty().unbind();
        progressCountLabel.textProperty().unbind();
        progressContainer.visibleProperty().unbind();
        progressContainer.managedProperty().unbind();
        chooseInputFilesButton.disableProperty().unbind();
        clearInputFilesButton.disableProperty().unbind();
        outputFolderField.disableProperty().unbind();
        chooseOutputFolderButton.disableProperty().unbind();
        decryptButton.disableProperty().unbind();

        progressBar.progressProperty().bind(task.progressProperty());
        progressBar.visibleProperty().bind(task.runningProperty());

        progressPercentLabel.textProperty().bind(task.messageProperty());
        progressPercentLabel.visibleProperty().bind(task.runningProperty());

        progressOutputFolderLabel.textProperty().bind(Bindings.createStringBinding(
                () -> I18n.get("externalMediaDecrypt.progress.output",
                        externalMediaDecryptService.currentOutputFolderPathProperty().get()),
                externalMediaDecryptService.currentOutputFolderPathProperty()));
        progressFileNameLabel.textProperty().bind(externalMediaDecryptService.currentFileNameProperty());
        progressCountLabel.textProperty().bind(externalMediaDecryptService.currentFileCountProperty());
        progressContainer.visibleProperty().bind(task.runningProperty());
        progressContainer.managedProperty().bind(task.runningProperty());
        chooseInputFilesButton.disableProperty().bind(task.runningProperty());
        clearInputFilesButton.disableProperty().bind(task.runningProperty());
        outputFolderField.disableProperty().bind(task.runningProperty());
        chooseOutputFolderButton.disableProperty().bind(task.runningProperty());
        decryptButton.disableProperty().bind(task.runningProperty());
    }

    private void restoreRunningTaskIfAny() {
        Task<List<DecryptionResult>> runningTask = externalMediaDecryptService.getCurrentDecryptTask();
        if (runningTask == null || !runningTask.isRunning()) {
            return;
        }
        decryptionTask = runningTask;
        bindProgress(runningTask);
    }

    private void showSummary(List<DecryptionResult> finalResults) {

        int successCount = 0;
        int failedCount = 0;
        List<DecryptionResult> failedResults = new ArrayList<>();
        List<FailureSummaryRequestedEvent.FailureSummaryRow> rows = new ArrayList<>();
        for (DecryptionResult res : finalResults) {

            if (res.success()) {
                successCount++;
            } else {
                failedCount++;
                failedResults.add(res);
                rows.add(new FailureSummaryRequestedEvent.FailureSummaryRow(res.fileName(), res.errorMessage()));
            }
        }

        if (failedCount == 0) {

            showAlert(
                    "externalMediaDecrypt.success.title",
                    "externalMediaDecrypt.success.header",
                    "externalMediaDecrypt.success.message",
                    successCount);

        } else if (!externalMediaDecryptService.isShutdownRequested()) {
            String summaryContent = I18n.get("externalMediaDecrypt.summary.content",
                    String.valueOf(successCount + failedCount), String.valueOf(successCount),
                    String.valueOf(failedCount));
            eventPublisher.publishEvent(new FailureSummaryRequestedEvent(
                    I18n.get("externalMediaDecrypt.summary.title"),
                    I18n.get("externalMediaDecrypt.summary.header", String.valueOf(failedCount)),
                    summaryContent,
                    I18n.get("externalMediaDecrypt.filename"),
                    I18n.get("externalMediaDecrypt.reason"),
                    rows,
                    () -> retryFailedDecrypt(failedResults)));

        }
    }

    private void retryFailedDecrypt(List<DecryptionResult> failedResults) {
        if (externalMediaDecryptService.isRunning()) {
            restoreRunningTaskIfAny();
            return;
        }
        if (!hasValidOutputFolder()) {
            showAlert("externalMediaDecrypt.validate.outputFile.title",
                    "externalMediaDecrypt.validate.outputFile.header",
                    "externalMediaDecrypt.validate.outputFile.message");
            restoreConfiguredFolders();
            return;
        }
        List<Path> failedInputFilePaths = failedResults.stream()
                .map(DecryptionResult::inputFilePath)
                .filter(Files::exists)
                .toList();
        if (failedInputFilePaths.isEmpty()) {
            showAlert("externalMediaDecrypt.validate.inputFile.title", "externalMediaDecrypt.validate.inputFile.header",
                    "externalMediaDecrypt.validate.inputFile.message");
            return;
        }
        progressContainer.setManaged(true);
        progressContainer.setVisible(true);
        startDecrypt(failedInputFilePaths, Path.of(outputFolderField.getText()));
    }

    private void hideProgress() {
        progressContainer.visibleProperty().unbind();
        progressContainer.managedProperty().unbind();
        progressOutputFolderLabel.textProperty().unbind();
        progressFileNameLabel.textProperty().unbind();
        progressCountLabel.textProperty().unbind();
        chooseInputFilesButton.disableProperty().unbind();
        clearInputFilesButton.disableProperty().unbind();
        outputFolderField.disableProperty().unbind();
        chooseOutputFolderButton.disableProperty().unbind();
        decryptButton.disableProperty().unbind();
        progressContainer.setVisible(false);
        progressContainer.setManaged(false);
        chooseInputFilesButton.setDisable(false);
        clearInputFilesButton.setDisable(false);
        outputFolderField.setDisable(false);
        chooseOutputFolderButton.setDisable(false);
        decryptButton.setDisable(false);
        progressOutputFolderLabel.setText("");
        progressFileNameLabel.setText("");
        progressCountLabel.setText("");
        externalMediaDecryptService.setCurrentFileName("");
        externalMediaDecryptService.setCurrentFileCount("");
        externalMediaDecryptService.setCurrentOutputFolderPath("");
    }

    private void updateListViewHeight() {
        double newHeight = Math.min(
                inputFileListView.getItems().size() * LIST_ITEM_HEIGHT + PADDING + BORDER,
                MAX_LIST_VIEW_HEIGHT);

        inputFileListView.setPrefHeight(newHeight);
    }

    private void confirmCancelDecrypt(String titleKey, String headerKey, String messageKey) {
        Alert alert = AlertHelper.createConfirmation(
                I18n.get(titleKey),
                I18n.get(headerKey),
                I18n.get(messageKey));

        ButtonType confirmButton = new ButtonType(I18n.get("app.close.ok"), ButtonBar.ButtonData.OK_DONE);

        ButtonType cancelButton = new ButtonType(I18n.get("app.close.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

        AlertHelper.setButtons(alert, confirmButton, cancelButton);
        alert.setOnShown(event -> {
            // Preventing JavaFX from separating cancel-type buttons into a different group.
            var buttonBarNode = alert.getDialogPane().lookup(".button-bar");
            if (buttonBarNode instanceof ButtonBar buttonBar) {
                buttonBar.setButtonOrder(ButtonBar.BUTTON_ORDER_NONE);
            }
        });
        alert.showAndWait().ifPresent(result -> {
            if (result == confirmButton) {
                externalMediaDecryptService.cancelDecrypt(decryptionTask);
            }
        });
    }

    private void saveState() {
        if (state == null)
            return;

        state.setSelectedFiles(new ArrayList<>(inputFiles));
    }

    private void restoreSelectedFiles(ExternalMediaDecryptState sourceState) {
        if (sourceState == null)
            return;

        inputFiles.clear();
        inputFiles.addAll(sourceState.getSelectedFiles());

        inputFileListView.getItems().setAll(
                inputFiles.stream()
                        .map(File::getAbsolutePath)
                        .toList());

        updateClearButtonVisibility();
        updateListViewHeight();
        updateDecryptButtonText();
    }

}
