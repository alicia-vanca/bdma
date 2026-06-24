package com.app.common.modules.externalmediadecrypt.services;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.modules.crypto.services.AesCtrCryptoService;
import com.app.common.modules.externalmediadecrypt.workers.ExternalMediaDecryptWorker;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.session.Session;
import com.app.common.services.AppConfigService;
import com.app.common.services.UserSettingService;
import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ExternalMediaDecryptService {

    private static final Logger log = LoggerFactory.getLogger(ExternalMediaDecryptService.class);

    private final AesCtrCryptoService aesCtrCryptoService;
    private final ApplicationEventPublisher eventPublisher;
    private final Session session;
    private final AppConfigService appConfigService;
    private final UserSettingService userSettingService;
    private final String password;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Task<List<DecryptionResult>>> currentTask = new AtomicReference<>();
    private final SimpleStringProperty currentFileName = new SimpleStringProperty("");
    private final SimpleStringProperty currentFileCount = new SimpleStringProperty("");
    private final SimpleStringProperty currentOutputFolderPath = new SimpleStringProperty("");
    private final AtomicReference<CountDownLatch> currentTaskDone = new AtomicReference<>();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    public ExternalMediaDecryptService(
            AesCtrCryptoService aesCtrCryptoService,
            ApplicationEventPublisher eventPublisher,
            Session session,
            AppConfigService appConfigService,
            UserSettingService userSettingService,
            @Value("${bodycam.crypto.password:${BODYCAM_CRYPTO_PASSWORD:}}") String password) {
        this.aesCtrCryptoService = aesCtrCryptoService;
        this.eventPublisher = eventPublisher;
        this.session = session;
        this.appConfigService = appConfigService;
        this.userSettingService = userSettingService;
        this.password = password;
    }

    public boolean isShutdownRequested() {
        return shutdownRequested.get();
    }

    /**
     * Starts one decrypt task and keeps it available for controllers recreated by
     * tab navigation.
     *
     * @param inputFilePaths   selected input files.
     * @param outputFolderPath target output folder.
     * @return active task, or null when shutdown blocks new work.
     */
    public Task<List<DecryptionResult>> startDecrypt(List<Path> inputFilePaths, Path outputFolderPath) {
        log.info("Decrypt service start requested. inputCount={}, outputFolder={}, running={}, shutdownRequested={}",
                inputFilePaths == null ? 0 : inputFilePaths.size(), outputFolderPath, running.get(),
                shutdownRequested.get());
        Task<List<DecryptionResult>> task = createDecryptTask(inputFilePaths, outputFolderPath);
        if (!beginTask(task)) {
            Task<List<DecryptionResult>> currentDecryptTask = getCurrentDecryptTask();
            log.warn("Decrypt service rejected new task. currentTaskId={}, running={}, shutdownRequested={}",
                    currentDecryptTask == null ? null : System.identityHashCode(currentDecryptTask), running.get(),
                    shutdownRequested.get());
            return currentDecryptTask;
        }

        Thread thread = new Thread(task, "external-media-decrypt");
        thread.setDaemon(true);
        Platform.runLater(thread::start);
        return task;
    }

    /**
     * Reserves the single decrypt worker slot and exposes its task for controllers
     * recreated by tab navigation.
     *
     * @param task JavaFX task that owns current decrypt run.
     * @return true when no decrypt run is active and the task was accepted.
     */
    private boolean beginTask(Task<List<DecryptionResult>> task) {
        if (task == null) {
            log.warn("Decrypt begin blocked: task is null.");
            return false;
        }
        if (shutdownRequested.get()) {
            log.warn("Decrypt begin blocked: application is shutting down.");
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("Decrypt begin blocked: another task is marked running. currentTaskId={}",
                    currentTask.get() == null ? null : System.identityHashCode(currentTask.get()));
            return false;
        }
        currentTask.set(task);
        currentTaskDone.set(new CountDownLatch(1));
        log.debug("Decrypt task reserved. taskId={}", System.identityHashCode(task));
        return true;
    }

    /**
     * Releases the worker slot when the accepted task reaches a terminal state.
     *
     * @param task task that is finishing.
     */
    private void endTask(Task<List<DecryptionResult>> task) {
        releaseTaskSlot(task, "finished");
    }

    /**
     * Requests cooperative cancellation so the worker can close file handles and
     * delete temp files before the summary is shown.
     *
     * @param task task selected by the current controller.
     */
    public void cancelDecrypt(Task<List<DecryptionResult>> task) {
        if (task instanceof ExternalMediaDecryptWorker worker) {
            worker.requestCancellation();
            log.info("Decrypt cancellation requested. taskId={}", System.identityHashCode(task));
        }
    }

    private void releaseTaskSlot(Task<List<DecryptionResult>> task, String reason) {
        if (task == null) {
            return;
        }
        boolean clearedCurrentTask = currentTask.compareAndSet(task, null);
        if (clearedCurrentTask) {
            setCurrentFileName("");
            setCurrentFileCount("");
            setCurrentOutputFolderPath("");
            running.set(false);
            CountDownLatch taskDone = currentTaskDone.getAndSet(null);
            if (taskDone != null) {
                taskDone.countDown();
            }
        }
        log.info("Decrypt task released. taskId={}, reason={}, clearedCurrentTask={}, running={}",
                System.identityHashCode(task), reason, clearedCurrentTask, running.get());
    }

    public Task<List<DecryptionResult>> getCurrentDecryptTask() {
        return currentTask.get();
    }

    public ReadOnlyStringProperty currentFileNameProperty() {
        return currentFileName;
    }

    public ReadOnlyStringProperty currentFileCountProperty() {
        return currentFileCount;
    }

    public ReadOnlyStringProperty currentOutputFolderPathProperty() {
        return currentOutputFolderPath;
    }

    public void setCurrentFileName(String fileName) {
        String safeFileName = fileName == null ? "" : fileName;
        if (Platform.isFxApplicationThread()) {
            currentFileName.set(safeFileName);
        } else {
            Platform.runLater(() -> currentFileName.set(safeFileName));
        }
    }

    public void setCurrentFileCount(String fileCount) {
        String safeFileCount = fileCount == null ? "" : fileCount;
        if (Platform.isFxApplicationThread()) {
            currentFileCount.set(safeFileCount);
        } else {
            Platform.runLater(() -> currentFileCount.set(safeFileCount));
        }
    }

    public void setCurrentOutputFolderPath(String outputFolderPath) {
        String safeOutputFolderPath = outputFolderPath == null ? "" : outputFolderPath;
        if (Platform.isFxApplicationThread()) {
            currentOutputFolderPath.set(safeOutputFolderPath);
        } else {
            Platform.runLater(() -> currentOutputFolderPath.set(safeOutputFolderPath));
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private Task<List<DecryptionResult>> createDecryptTask(List<Path> inputFilePaths, Path outputFolderPath) {
        ExternalMediaDecryptWorker.ProgressStateUpdater progressStateUpdater = new ExternalMediaDecryptWorker.ProgressStateUpdater(
                this::setCurrentFileName,
                this::setCurrentFileCount,
                path -> setCurrentOutputFolderPath(path == null ? "" : path.toString()));
        ExternalMediaDecryptWorker.WorkerLifecycleHooks lifecycleHooks = new ExternalMediaDecryptWorker.WorkerLifecycleHooks(
                this::isShutdownRequested,
                this::endTask);
        return new ExternalMediaDecryptWorker(
                aesCtrCryptoService,
                eventPublisher,
                password,
                inputFilePaths,
                outputFolderPath,
                progressStateUpdater,
                lifecycleHooks);
    }

    @EventListener
    public void onStorageRestored(StorageRestoredEvent event) {
        if (event == null || event.getTarget() != FolderType.DECRYPT) {
            return;
        }
        Task<List<DecryptionResult>> task = currentTask.get();
        if (task instanceof ExternalMediaDecryptWorker worker) {
            worker.notifyStorageRestored(resolveConfiguredOutputFolderPath());
        }
    }

    private Path resolveConfiguredOutputFolderPath() {
        String configuredPath = session.isGuest()
                ? appConfigService.getConfigValue(AppConstants.DECRYPT_OUTPUT_DIR)
                : userSettingService.getConfigValue(session.getCurrentUserId(), AppConstants.DECRYPT_OUTPUT_DIR);
        return configuredPath == null || configuredPath.isBlank() ? null : Path.of(configuredPath);
    }

    @EventListener
    public void onStorageRecoveryDeferred(StorageRecoveryDeferredEvent event) {
        if (event == null || event.getTarget() != FolderType.DECRYPT) {
            return;
        }
        Task<List<DecryptionResult>> task = currentTask.get();
        if (task instanceof ExternalMediaDecryptWorker worker) {
            worker.notifyStorageRecoveryDeferred();
        }
    }

    /**
     * Requests the active decrypt task to stop at its next cancellation check and
     * waits for its own cleanup path.
     */
    @PreDestroy
    public void gracefulShutdown() {
        shutdownRequested.set(true);
        Task<List<DecryptionResult>> task = currentTask.get();
        CountDownLatch taskDone = currentTaskDone.get();
        if (task != null && task.isRunning()) {
            task.cancel();
        }
        if (taskDone != null) {
            try {
                taskDone.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public record DecryptionResult(String fileName, Path inputFilePath, boolean success, String errorMessage) {
    }
}
