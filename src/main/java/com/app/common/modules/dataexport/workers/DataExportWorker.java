package com.app.common.modules.dataexport.workers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.common.modules.datarestore.services.RestoreService;
import com.app.common.modules.datarestore.services.RestoreService.BackupSyncResult;
import com.app.common.modules.datarestore.services.RestoreService.RestoreFailureReason;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.dtos.FileView;
import com.app.common.exceptions.DiskFullException;
import com.app.common.exceptions.DriveUnavailableException;
import com.app.common.events.FailureSummaryRequestedEvent;
import com.app.common.events.FailureSummaryRequestedEvent.FailureSummaryRow;
import com.app.common.modules.dataexport.services.DataExportService;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.queuemanager.enums.ItemStatus;
import com.app.common.modules.queuemanager.services.QueueManagerService;
import com.app.common.modules.session.Session;
import com.app.common.services.AppNoticeService;

import javafx.application.Platform;

/**
 * Single-threaded worker that copies queued export files to the destination
 * directory and handles storage errors. {@link DataExportService} is the public
 * entry point; this class owns the executor, all queue state, and storage
 * recovery signaling.
 */
@Component
public class DataExportWorker {

    private static final Logger log = LoggerFactory.getLogger(DataExportWorker.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    private static final String ERROR_SOURCE_NOT_FOUND = "file.export.error.source_not_found";
    private static final String ERROR_MISSING_SYNCED_PATH = "file.export.error.missing_synced_path";
    private static final String ERROR_IO_EXCEPTION = "file.export.error.io_exception";
    private static final String ERROR_UNKNOWN = "file.export.error.unknown";
    private static final String ERROR_EXPORT_DRIVE_UNAVAILABLE = "file.export.error.drive_unavailable";
    private static final String ERROR_EXPORT_DISK_FULL = "file.export.error.disk_full";

    private static final String EXPORT_SUMMARY_TITLE = "file.export.summary.title";
    private static final String EXPORT_SUMMARY_HEADER = "file.export.summary.header";
    private static final String EXPORT_SUMMARY_CONTENT = "file.export.summary.content";
    private static final String EXPORT_SUMMARY_COLUMN_FILENAME = "file.export.summary.column.filename";
    private static final String EXPORT_SUMMARY_COLUMN_REASON = "file.export.summary.column.reason";

    private static final String LOG_EXPORT_FAILED = "Export failed for {}";

    private final FolderManagerService folderManagerService;
    private final QueueManagerService queueManagerService;
    private final AdminSettingsDialogService adminSettingsDialogService;
    private final RestoreService restoreService;
    private final AppNoticeService appNoticeService;
    private final ApplicationEventPublisher eventPublisher;
    private final Session session;

    private final AtomicReference<ExecutorService> executorRef = new AtomicReference<>(newExecutor());

    // Guards recovery state; notified by Spring event listeners, waited on by the
    // worker thread.
    private final Object recoveryLock = new Object();
    private volatile boolean exportRecoveryDeferred = false;
    private volatile boolean cancellationRequested;

    // Tracks full export paths currently QUEUED or PROCESSING to prevent duplicate
    // submissions to the same destination file.
    private final Set<String> activeExportPaths = ConcurrentHashMap.newKeySet();

    // Tracks export requests that reached a terminal state in the current
    // lifecycle.
    // This prevents a repeated export click from re-queuing completed or failed
    // rows before the completion notice closes the lifecycle.
    private final Set<String> processedExportRequests = ConcurrentHashMap.newKeySet();

    // Tracks .tmp paths currently being written so they can be deleted on cancel.
    private final Set<Path> activeTmpPaths = ConcurrentHashMap.newKeySet();

    // Directory-first queue: each directory queue owns its pending file queue. The
    // worker drains one directory fully before moving to the next directory.
    private final Object queueLock = new Object();
    private final List<ExportDirectoryQueue> directoryQueues = new ArrayList<>();
    private final ConcurrentHashMap<Path, List<FailedExportFile>> failedFilesByDirectory = new ConcurrentHashMap<>();
    private boolean processorRunning;

    // Counts files currently in all directory queues plus the one being processed.
    // Drops to zero when every pending file finishes; triggers the completion
    // notice.
    private final AtomicInteger pendingFileCount = new AtomicInteger(0);

    // Sort by known database size only; source lookup and restore happen in the
    // single-file processing path so queue submission stays cheap.
    private static final Comparator<ExportFileItem> EXPORT_FILE_SIZE_COMPARATOR = Comparator
            .comparingLong(ExportFileItem::sortSize);

    DataExportWorker(FolderManagerService folderManagerService,
            QueueManagerService queueManagerService,
            AdminSettingsDialogService adminSettingsDialogService,
            RestoreService restoreService,
            AppNoticeService appNoticeService,
            ApplicationEventPublisher eventPublisher,
            Session session) {
        this.folderManagerService = folderManagerService;
        this.queueManagerService = queueManagerService;
        this.adminSettingsDialogService = adminSettingsDialogService;
        this.restoreService = restoreService;
        this.appNoticeService = appNoticeService;
        this.eventPublisher = eventPublisher;
        this.session = session;
    }

    // ── Storage recovery events ───────────────────────────────────────────────

    @EventListener
    public void onStorageRestored(StorageRestoredEvent event) {
        if (event != null && event.getTarget() == FolderType.EXPORT) {
            synchronized (recoveryLock) {
                exportRecoveryDeferred = false;
                recoveryLock.notifyAll();
            }
        }
    }

    @EventListener
    public void onStorageRecoveryDeferred(StorageRecoveryDeferredEvent event) {
        if (event != null && event.getTarget() == FolderType.EXPORT) {
            synchronized (recoveryLock) {
                exportRecoveryDeferred = true;
                recoveryLock.notifyAll();
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Stops the executor, drops all queued tasks, and clears queue state.
     */
    public void cancelAndCleanup() {
        cancellationRequested = true;
        executorRef.get().shutdownNow();
        synchronized (recoveryLock) {
            recoveryLock.notifyAll();
        }
        activeExportPaths.clear();
        processedExportRequests.clear();
        synchronized (queueLock) {
            directoryQueues.clear();
            failedFilesByDirectory.clear();
            processorRunning = false;
        }
        pendingFileCount.set(0);
        // Keep the visible export queue in sync with the worker lifecycle so stale
        // directory nodes cannot survive logout and appear after the next login.
        queueManagerService.clearAll();
        for (Path tmp : activeTmpPaths) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ex) {
                log.warn("Failed to delete tmp file on cancel: {}", tmp, ex);
            }
        }
        activeTmpPaths.clear();
    }

    private boolean isCancellationRequested() {
        return cancellationRequested || Thread.currentThread().isInterrupted();
    }

    private void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException("Export worker cancellation requested");
        }
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DataExportWorker");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Queue submission ──────────────────────────────────────────────────────

    /**
     * Sorts, deduplicates, and appends files into the directory queue for the
     * requested export directory. A source file may appear in several directory
     * queues because the queue identity is its full destination export path.
     */
    public void enqueueFiles(List<FileView> selectedFiles, Path exportDir) {
        Path normalizedDir = exportDir.toAbsolutePath().normalize();
        logReceivedExportFiles(selectedFiles, normalizedDir);

        synchronized (queueLock) {
            if (findDirectoryQueue(normalizedDir) == null) {
                resetDirectoryQueueForNewRun(normalizedDir);
            }
        }

        List<ExportFileItem> acceptedItems = new ArrayList<>();
        for (FileView fileView : selectedFiles) {
            String progressTrackerRowId = buildProgressTrackerRowId(fileView, normalizedDir);
            if (!processedExportRequests.contains(progressTrackerRowId)
                    && activeExportPaths.add(progressTrackerRowId)) {
                acceptedItems.add(createExportFileItem(fileView, progressTrackerRowId));
            }
        }
        acceptedItems.sort(EXPORT_FILE_SIZE_COMPARATOR);

        if (acceptedItems.isEmpty()) {
            log.info("Export request skipped: no new files accepted for dir={} received={}",
                    normalizedDir, selectedFiles.size());
            return;
        }

        pendingFileCount.addAndGet(acceptedItems.size());

        synchronized (queueLock) {
            ExportDirectoryQueue directoryQueue = getOrCreateDirectoryQueue(normalizedDir);

            for (ExportFileItem item : acceptedItems) {
                directoryQueue.files.add(item);
                queueManagerService.addFileToExportTracker(item.progressTrackerRowId(),
                        resolveFileName(item.fileView()),
                        item.fileSize(), item.sortSize());
            }
            queueManagerService.allExportFilesAddedToTracker(acceptedItems.size());
            // Re-sort the pending list after appends so an already processing directory
            // continues with the smallest remaining files first.
            directoryQueue.files.sort(EXPORT_FILE_SIZE_COMPARATOR);

            if (!processorRunning) {
                processorRunning = true;
                executorRef.get().submit(this::processDirectoryQueues);
            }
        }
    }

    // ── Per-file copy logic ───────────────────────────────────────────────────

    /**
     * Resets worker and visible queue state before a completed destination root is
     * reused as a fresh queue run. Removing and later recreating the queue also
     * moves the root directory to the bottom of the directory-first processing
     * list.
     */
    private void resetDirectoryQueueForNewRun(Path normalizedDir) {
        queueManagerService.resetExportDirectoryForNewRun(normalizedDir);
        ExportDirectoryQueue staleQueue = findDirectoryQueue(normalizedDir);
        if (staleQueue != null) {
            directoryQueues.remove(staleQueue);
        }
        processedExportRequests.removeIf(
                progressTrackerRowId -> isProgressTrackerRowInDirectory(progressTrackerRowId, normalizedDir));
    }

    private ExportDirectoryQueue findDirectoryQueue(Path normalizedDir) {
        return directoryQueues.stream()
                .filter(queue -> queue.exportDir.equals(normalizedDir))
                .findFirst()
                .orElse(null);
    }

    private ExportDirectoryQueue getOrCreateDirectoryQueue(Path normalizedDir) {
        ExportDirectoryQueue existing = findDirectoryQueue(normalizedDir);
        if (existing != null) {
            return existing;
        }
        ExportDirectoryQueue created = new ExportDirectoryQueue(normalizedDir);
        directoryQueues.add(created);
        return created;
    }

    /**
     * Drains one directory queue at a time. New files appended while processing are
     * picked up before the worker moves to the next directory.
     */
    private void processDirectoryQueues() {
        try {
            while (!isCancellationRequested()) {
                ExportDirectoryQueue directoryQueue;
                synchronized (queueLock) {
                    directoryQueue = directoryQueues.isEmpty() ? null : directoryQueues.getFirst();
                    if (directoryQueue == null) {
                        processorRunning = false;
                        return;
                    }
                }

                processDirectoryQueue(directoryQueue);
            }
        } catch (CancellationException ex) {
            log.debug("Export worker stopped because cancellation was requested");
        } finally {
            synchronized (queueLock) {
                if (isCancellationRequested()) {
                    processorRunning = false;
                }
            }
        }
    }

    /**
     * Completes a directory only when it actually owns finished results.
     * Directories
     * that merely received moved files and then moved them again are removed from
     * the UI without leaving zero-count history rows.
     */
    private void finishDirectoryQueue(ExportDirectoryQueue directoryQueue) {
        synchronized (queueLock) {
            directoryQueues.remove(directoryQueue);
        }

        if (directoryQueue.total == 0) {
            return;
        }

        queueManagerService.markExportDirectoryFinished(directoryQueue.exportDir, directoryQueue.total,
                directoryQueue.passed, directoryQueue.failed);
        showDirectoryFailureSummary(directoryQueue);
    }

    private void processDirectoryQueue(ExportDirectoryQueue directoryQueue) {
        while (!isCancellationRequested()) {
            ExportFileItem item;
            synchronized (queueLock) {
                item = directoryQueue.files.isEmpty() ? null : directoryQueue.files.removeFirst();
                if (item == null) {
                    finishDirectoryQueue(directoryQueue);
                    return;
                }
            }

            ExportResult result = ExportResult.failure(item, ERROR_UNKNOWN);
            try {
                result = runFileCopy(item, directoryQueue);
            } finally {
                if (!isCancellationRequested()) {
                    ExportDirectoryQueue resultQueue = resolveResultDirectoryQueue(directoryQueue, result);
                    recordDirectoryResult(resultQueue, result);
                    logProcessedExportFile(resultQueue, result);
                    processedExportRequests.add(result.item().progressTrackerRowId());
                    activeExportPaths.remove(result.item().progressTrackerRowId());
                    queueManagerService.finishExportForSingleFile();
                    pendingFileCount.decrementAndGet();
                    if (pendingFileCount.get() == 0) {
                        Platform.runLater(this::fireCompletionNotice);
                    }
                }
            }
        }
    }

    /**
     * Copies one file to the export directory.
     * <ul>
     * <li>Same size at destination: skip (already exported).</li>
     * <li>Different size at destination: copy to a renamed target.</li>
     * <li>No conflict: copy normally.</li>
     * <li>Drive unavailable or disk full: pause via {@link #pauseAndRecover},
     * then retry from the top of the loop with the new directory.</li>
     * </ul>
     */
    private ExportResult runFileCopy(ExportFileItem item, ExportDirectoryQueue directoryQueue) {
        ExportAttempt attempt = new ExportAttempt(item, directoryQueue.exportDir);
        String fileName = resolveFileName(item.fileView());

        try {
            queueManagerService.markExportFileProcessing(attempt.progressTrackerRowId());

            while (true) {
                StorageCheckResult storageCheck = validateExportStorage(attempt);
                if (storageCheck.failureResult() != null) {
                    return storageCheck.failureResult();
                }
                attempt = storageCheck.attempt();

                ExportResult result = tryCopyToCurrentDirectory(attempt);
                if (result != null) {
                    return result;
                }

                RecoveryResult recovery = recoverFromStorageException(attempt, attempt.storageException());
                if (!recovery.recovered()) {
                    return ExportResult.failure(attempt.item(), storageErrorMessage(attempt.storageException()));
                }
                attempt = new ExportAttempt(recovery.item(), recovery.exportDir());
            }
        } catch (CancellationException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error(LOG_EXPORT_FAILED, fileName, ex);
            queueManagerService.markExportFileFailed(attempt.progressTrackerRowId(), ERROR_UNKNOWN);
            return ExportResult.failure(attempt.item(), ERROR_UNKNOWN);
        }
    }

    /**
     * Validates destination availability before copy starts. Storage failures pause
     * the queue and retry with the recovered export directory when available.
     */
    private StorageCheckResult validateExportStorage(ExportAttempt attempt) {
        Path exportDir = attempt.exportDir();
        if (!folderManagerService.isDriveAccessible(exportDir.toFile())) {
            RecoveryResult recovery = pauseAndRecover(attempt.item(), exportDir,
                    StorageIssueReason.DRIVE_UNAVAILABLE, ERROR_EXPORT_DRIVE_UNAVAILABLE);
            return recovery.recovered()
                    ? StorageCheckResult.retry(new ExportAttempt(recovery.item(), recovery.exportDir()))
                    : StorageCheckResult.failed(ExportResult.failure(attempt.item(), ERROR_EXPORT_DRIVE_UNAVAILABLE));
        }

        return StorageCheckResult.retry(attempt);
    }

    /**
     * Returns a terminal result for success or non-storage failures. Storage
     * exceptions are attached to the attempt and handled by the recovery loop.
     */
    private ExportResult tryCopyToCurrentDirectory(ExportAttempt attempt) {
        ExportFileItem item = attempt.item();
        String progressTrackerRowId = attempt.progressTrackerRowId();
        String fileName = resolveFileName(item.fileView());
        Path source = resolveSourcePathForExport(item, progressTrackerRowId, fileName);
        if (source == null) {
            return ExportResult.failure(item, classifyExportError(new IOException(ERROR_SOURCE_NOT_FOUND)));
        }

        try {
            return copyResolvedSource(attempt, source, fileName);
        } catch (DiskFullException | DriveUnavailableException ex) {
            attempt.setStorageException(ex);
            return null;
        } catch (IOException ex) {
            IOException storageException = classifyStorageException(ex);
            if (storageException != null) {
                attempt.setStorageException(storageException);
                return null;
            }
            log.error(LOG_EXPORT_FAILED, fileName, ex);
            queueManagerService.markExportFileFailed(progressTrackerRowId, ERROR_IO_EXCEPTION);
            return ExportResult.failure(item, ERROR_IO_EXCEPTION);
        }
    }

    /**
     * Source resolution failures are data errors and should not trigger storage
     * recovery prompts.
     */
    private Path resolveSourcePathForExport(ExportFileItem item, String progressTrackerRowId, String fileName) {
        try {
            return resolveSourcePath(item.fileView());
        } catch (IOException ex) {
            String reason = classifyExportError(ex);
            if (ERROR_SOURCE_NOT_FOUND.equals(reason)) {
                log.warn("Export source file not found: name={}", fileName);
            } else {
                log.error(LOG_EXPORT_FAILED, fileName, ex);
            }
            queueManagerService.markExportFileFailed(progressTrackerRowId, reason);
            return null;
        }
    }

    private ExportResult copyResolvedSource(ExportAttempt attempt, Path source, String fileName) throws IOException {
        Files.createDirectories(attempt.exportDir());

        long sourceSize = Files.size(source);
        Path matchingTarget = findExistingTargetWithSize(attempt.exportDir(), fileName, sourceSize);
        if (matchingTarget != null) {
            // A previous lifecycle may have exported this file under a conflict
            // suffix, so count this request as a successful no-op.
            String exportedFileName = matchingTarget.getFileName().toString();
            completeExportFile(attempt.progressTrackerRowId(), exportedFileName);
            return ExportResult.completed(attempt.item(), exportedFileName);
        }

        if (!folderManagerService.hasSufficientSpace(attempt.exportDir().toFile(), sourceSize)) {
            throw new DiskFullException("Destination does not have enough space for export");
        }

        Path target = resolveUniqueTarget(attempt.exportDir(), fileName);
        String exportedFileName = target.getFileName().toString();
        queueManagerService.renameExportFile(attempt.progressTrackerRowId(), exportedFileName);
        copyWithProgress(source, target, attempt.progressTrackerRowId());
        queueManagerService.markExportFileCompleted(attempt.progressTrackerRowId());
        processedExportRequests.add(attempt.progressTrackerRowId());
        return ExportResult.completed(attempt.item(), exportedFileName);
    }

    private void completeExportFile(String progressTrackerRowId, String exportedFileName) {
        queueManagerService.renameExportFile(progressTrackerRowId, exportedFileName);
        queueManagerService.markExportFileCompleted(progressTrackerRowId);
        processedExportRequests.add(progressTrackerRowId);
    }

    private IOException classifyStorageException(IOException ex) {
        try {
            rethrowAsStorageException(ex);
        } catch (DiskFullException | DriveUnavailableException storageException) {
            return storageException;
        } catch (IOException ignored) {
            // rethrowAsStorageException only re-throws as storage exceptions.
        }
        return null;
    }

    private RecoveryResult recoverFromStorageException(ExportAttempt attempt, IOException ex) {
        return pauseAndRecover(attempt.item(), attempt.exportDir(), storageIssueReason(ex), storageErrorMessage(ex));
    }

    private StorageIssueReason storageIssueReason(IOException ex) {
        return ex instanceof DiskFullException ? StorageIssueReason.LOW_SPACE : StorageIssueReason.DRIVE_UNAVAILABLE;
    }

    private String storageErrorMessage(IOException ex) {
        return ex instanceof DiskFullException ? ERROR_EXPORT_DISK_FULL : ERROR_EXPORT_DRIVE_UNAVAILABLE;
    }

    /**
     * Pauses the current directory queue on a storage error. Remaining count is
     * exactly the current processing item plus the items still waiting in this
     * directory queue.
     */
    private RecoveryResult pauseAndRecover(ExportFileItem processingItem, Path currentExportDir,
            StorageIssueReason reason, String errorMessage) {
        if (isCancellationRequested()) {
            return new RecoveryResult(false, processingItem, currentExportDir.toAbsolutePath().normalize());
        }
        String progressTrackerRowId = processingItem.progressTrackerRowId();
        Path normalizedCurrentDir = currentExportDir.toAbsolutePath().normalize();
        ExportDirectoryQueue directoryQueue;
        int remainingCount;
        synchronized (queueLock) {
            directoryQueue = findDirectoryQueue(normalizedCurrentDir);
            if (directoryQueue == null) {
                return new RecoveryResult(false, processingItem, normalizedCurrentDir);
            }
            remainingCount = 1 + directoryQueue.files.size();
        }

        log.error("Export storage error [{}]: {} (remaining={})", reason, normalizedCurrentDir, remainingCount);
        queueManagerService.markExportFileDeferred(progressTrackerRowId, errorMessage);

        eventPublisher.publishEvent(
                new StorageUnavailableEvent(FolderType.EXPORT, reason, normalizedCurrentDir, remainingCount));
        boolean recovered = waitForStorageRecovery();

        if (!recovered) {
            failDirectoryQueue(processingItem, directoryQueue, errorMessage);
            return new RecoveryResult(false, processingItem, normalizedCurrentDir);
        }

        Path newDir = resolveExportDirectory().toAbsolutePath().normalize();
        ExportFileItem updatedProcessingItem = moveDirectoryQueue(directoryQueue, processingItem, newDir);
        return new RecoveryResult(true, updatedProcessingItem, newDir);
    }

    /**
     * Marks pending items in the same directory queue as failed when recovery is
     * canceled. The current processing item is only marked in the UI here; its
     * aggregate counters are recorded by the normal per-file finally block so it is
     * not counted twice.
     */
    private void failDirectoryQueue(ExportFileItem processingItem, ExportDirectoryQueue directoryQueue,
            String errorMessage) {
        List<ExportFileItem> pendingItems;
        synchronized (queueLock) {
            pendingItems = new ArrayList<>(directoryQueue.files);
            directoryQueue.files.clear();
        }

        queueManagerService.markExportFileFailed(processingItem.progressTrackerRowId(), errorMessage);

        for (ExportFileItem pending : pendingItems) {
            queueManagerService.markExportFileFailed(pending.progressTrackerRowId(), errorMessage);
            recordDirectoryResult(directoryQueue, ExportResult.failure(pending, errorMessage));
            processedExportRequests.add(pending.progressTrackerRowId());
            activeExportPaths.remove(pending.progressTrackerRowId());
            queueManagerService.finishExportForSingleFile();
            pendingFileCount.decrementAndGet();
            if (pendingFileCount.get() == 0) {
                Platform.runLater(this::fireCompletionNotice);
            }
        }
    }

    /**
     * Moves the current processing item and every remaining item in the directory
     * queue to a new export directory. Recovered files keep the old directory's
     * position so the worker finishes them before moving to the next directory.
     */
    private ExportFileItem moveDirectoryQueue(ExportDirectoryQueue oldQueue, ExportFileItem processingItem,
            Path newDir) {
        Path normalizedNewDir = newDir.toAbsolutePath().normalize();
        boolean targetQueueExists = findDirectoryQueue(normalizedNewDir) != null;
        if (!targetQueueExists) {
            resetDirectoryQueueForNewRun(normalizedNewDir);
        }

        List<ExportFileItem> pendingItems;
        int recoveredQueueIndex;
        synchronized (queueLock) {
            recoveredQueueIndex = directoryQueues.indexOf(oldQueue);
            pendingItems = new ArrayList<>(oldQueue.files);
            oldQueue.files.clear();
            directoryQueues.remove(oldQueue);
        }

        ExportFileItem updatedProcessingItem = moveExportItem(processingItem, normalizedNewDir);
        List<ExportFileItem> movedPendingItems = new ArrayList<>();
        for (ExportFileItem pending : pendingItems) {
            movedPendingItems.add(moveExportItem(pending, normalizedNewDir));
        }

        synchronized (queueLock) {
            ExportDirectoryQueue targetQueue = getOrCreateDirectoryQueue(normalizedNewDir);
            targetQueue.files.addAll(movedPendingItems);
            targetQueue.files.sort(EXPORT_FILE_SIZE_COMPARATOR);

            // A recovered queue is still the same logical directory run; keep its
            // turn instead of appending it behind later destination queues.
            directoryQueues.remove(targetQueue);
            int insertIndex = recoveredQueueIndex < 0 ? 0 : Math.min(recoveredQueueIndex, directoryQueues.size());
            directoryQueues.add(insertIndex, targetQueue);
        }
        return updatedProcessingItem;
    }

    private ExportFileItem moveExportItem(ExportFileItem item, Path newDir) {
        Path normalizedNewDir = newDir.toAbsolutePath().normalize();
        String oldProgressTrackerRowId = item.progressTrackerRowId();
        String newProgressTrackerRowId = buildProgressTrackerRowId(resolveFileName(item.fileView()), normalizedNewDir);
        if (oldProgressTrackerRowId.equals(newProgressTrackerRowId)) {
            return item;
        }

        queueManagerService.moveExportFile(oldProgressTrackerRowId, newProgressTrackerRowId);
        activeExportPaths.remove(oldProgressTrackerRowId);
        activeExportPaths.add(newProgressTrackerRowId);

        return new ExportFileItem(item.fileView(), newProgressTrackerRowId, item.sortSize(), item.fileSize());
    }

    /**
     * Blocks until the export drive is recovered (StorageRestoredEvent) or the
     * user clicks "Later" (StorageRecoveryDeferredEvent).
     *
     * @return true if the drive was recovered, false if deferred or interrupted
     */
    private boolean waitForStorageRecovery() {
        synchronized (recoveryLock) {
            // Clear any stale deferred flag before blocking so a leftover signal
            // from a previous recovery cycle does not cause an immediate false return.
            exportRecoveryDeferred = false;
            while (!exportRecoveryDeferred && !isCancellationRequested()) {
                try {
                    recoveryLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (isCancellationRequested()) {
                    return false;
                }
                if (!exportRecoveryDeferred) {
                    return true;
                }
            }
            return false;
        }
    }

    // ── Completion notice ─────────────────────────────────────────────────────

    private void fireCompletionNotice() {
        // If the user enqueued more files between when this notice was scheduled and
        // now, the queue is still active — skip and let the next completion fire it.
        if (pendingFileCount.get() > 0) {
            return;
        }
        appNoticeService.showSuccess(I18n.get("file.export.notice.finish"));
        processedExportRequests.clear();
    }

    /**
     * Resolves the destination queue that owns the final export path for a result.
     * Recovery can move the current item from its original directory to a new one,
     * so final counters must be written to the queue matching the completed path.
     */
    private ExportDirectoryQueue resolveResultDirectoryQueue(ExportDirectoryQueue fallbackQueue, ExportResult result) {
        Path resultDir = extractExportDir(result.item().progressTrackerRowId());
        if (resultDir == null || fallbackQueue.exportDir.equals(resultDir)) {
            return fallbackQueue;
        }

        synchronized (queueLock) {
            return getOrCreateDirectoryQueue(resultDir);
        }
    }

    /**
     * Updates the per-directory counters used by the detailed failure summary.
     */
    private void recordDirectoryResult(ExportDirectoryQueue directoryQueue, ExportResult result) {
        directoryQueue.total++;
        if (result.status() == ItemStatus.COMPLETED) {
            directoryQueue.passed++;
            return;
        }

        directoryQueue.failed++;
        String reason = result.failureReason() != null ? result.failureReason() : ERROR_UNKNOWN;
        String displayName = result.displayName() != null ? result.displayName()
                : resolveFileName(result.item().fileView());
        directoryQueue.failedFiles.add(new FailedExportFile(result.item().fileView(), displayName, reason));
    }

    private void logReceivedExportFiles(List<FileView> selectedFiles, Path normalizedDir) {
        if (!log.isInfoEnabled()) {
            return;
        }
        List<String> fileNames = selectedFiles.stream()
                .map(this::resolveFileName)
                .toList();
        log.info("Export request received: dir={} total={} files={}", normalizedDir, selectedFiles.size(), fileNames);
    }

    private void logProcessedExportFile(ExportDirectoryQueue directoryQueue, ExportResult result) {
        if (!log.isInfoEnabled()) {
            return;
        }
        String name = result.displayName() != null ? result.displayName() : resolveFileName(result.item().fileView());
        log.info("Export file processed: name={} dir={} status={} total={} success={} failed={}",
                name, directoryQueue.exportDir, result.status(), directoryQueue.total,
                directoryQueue.passed, directoryQueue.failed);
    }

    /**
     * Shows a DataSyncWorker-style table after a directory finishes with failures.
     */
    private void showDirectoryFailureSummary(ExportDirectoryQueue directoryQueue) {
        if (directoryQueue.failedFiles.isEmpty()) {
            return;
        }

        List<FailureSummaryRow> rows = new ArrayList<>();
        for (FailedExportFile failedFile : directoryQueue.failedFiles) {
            rows.add(new FailureSummaryRow(failedFile.displayName(), I18n.get(failedFile.reason())));
        }

        failedFilesByDirectory.put(directoryQueue.exportDir, List.copyOf(directoryQueue.failedFiles));

        String dir = directoryQueue.exportDir.toString();
        String header = I18n.get(EXPORT_SUMMARY_HEADER, dir, directoryQueue.failed);
        String content = I18n.get(EXPORT_SUMMARY_CONTENT, dir, directoryQueue.failed);
        eventPublisher.publishEvent(new FailureSummaryRequestedEvent(
                I18n.get(EXPORT_SUMMARY_TITLE),
                header,
                content,
                I18n.get(EXPORT_SUMMARY_COLUMN_FILENAME),
                I18n.get(EXPORT_SUMMARY_COLUMN_REASON),
                rows,
                () -> retryFailedDirectoryFiles(directoryQueue.exportDir)));
    }

    /**
     * Requeue only the failed files for the same export directory.
     *
     * @param exportDir export root row id selected by the queue UI
     */
    public void retryFailedDirectoryFiles(Path exportDir) {
        Path normalizedDir = exportDir.toAbsolutePath().normalize();
        List<FailedExportFile> failedFiles = failedFilesByDirectory.getOrDefault(normalizedDir, List.of());
        List<FileView> filesToRetry = failedFiles.stream()
                .map(FailedExportFile::fileView)
                .toList();
        enqueueFiles(filesToRetry, normalizedDir);
    }

    private boolean isProgressTrackerRowInDirectory(String progressTrackerRowId, Path exportDir) {
        Path parent = extractExportDir(progressTrackerRowId);
        return parent != null && parent.equals(exportDir);
    }

    private Path extractExportDir(String progressTrackerRowId) {
        if (progressTrackerRowId == null || progressTrackerRowId.isBlank()) {
            return null;
        }
        Path parent = Path.of(progressTrackerRowId).getParent();
        return parent != null ? parent.toAbsolutePath().normalize() : null;
    }

    // ── I/O helpers ───────────────────────────────────────────────────────────

    /**
     * Copy source to target, emitting progress updates at 5% increments.
     *
     * @throws DiskFullException         if the destination drive runs out of space
     * @throws DriveUnavailableException if the destination drive becomes
     *                                   inaccessible
     */
    private void copyWithProgress(Path source, Path target, String progressTrackerRowId) throws IOException {
        Path targetTmp = Path.of(target.toString() + AppConstants.TMP_EXTENSION);
        long totalBytes = Files.size(source);

        activeTmpPaths.add(targetTmp);
        try {
            if (totalBytes <= 0) {
                Files.copy(source, targetTmp, StandardCopyOption.REPLACE_EXISTING);
                throwIfCancellationRequested();
                Files.move(targetTmp, target, StandardCopyOption.REPLACE_EXISTING);
                queueManagerService.updateExportFileProgress(progressTrackerRowId, 100);
                return;
            }

            long copied = 0;
            int lastReported = 0;

            try (InputStream in = Files.newInputStream(source);
                    OutputStream out = Files.newOutputStream(targetTmp)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    throwIfCancellationRequested();
                    writeChunk(out, buffer, read);
                    copied += read;
                    int percent = (int) ((copied * 100) / totalBytes);
                    int normalized = normalizeTo5PercentStep(percent);
                    if (normalized >= lastReported + 5 && normalized <= 100) {
                        lastReported = normalized;
                        queueManagerService.updateExportFileProgress(progressTrackerRowId, normalized);
                    }
                }
            } catch (IOException ex) {
                rethrowAsStorageException(ex);
                throw ex;
            }

            try {
                throwIfCancellationRequested();
                Files.move(targetTmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                rethrowAsStorageException(ex);
                throw ex;
            }

            if (lastReported < 100) {
                queueManagerService.updateExportFileProgress(progressTrackerRowId, 100);
            }
        } finally {
            // Remove from tracking regardless of outcome; the file is either fully
            // committed to its final name or has been left for cancelAndCleanup.
            activeTmpPaths.remove(targetTmp);
        }
    }

    private void writeChunk(OutputStream out, byte[] buffer, int length) throws IOException {
        try {
            out.write(buffer, 0, length);
        } catch (IOException ex) {
            rethrowAsStorageException(ex);
            throw ex;
        }
    }

    /**
     * Re-throws well-known OS errors as typed storage exceptions so callers can
     * distinguish disk-full and drive-unavailable conditions from ordinary I/O
     * errors.
     * <p>
     * Detection strategy — type first, message string only as a fallback:
     * <ul>
     * <li>{@link NoSuchFileException} — target path disappeared (drive removed
     * mid-write; OS buffered the writes without error).</li>
     * <li>Exact {@link FileSystemException} (not a specific subclass like
     * {@code FileAlreadyExistsException}) with a non-null reason — Windows OS-level
     * drive error. Subclasses that encode normal operations are excluded.</li>
     * <li>Message string matching — fallback for non-NIO {@link IOException}s that
     * carry no dedicated type (e.g. disk-full from buffered streams).</li>
     * </ul>
     */
    private static void rethrowAsStorageException(IOException ex) throws IOException {
        // NoSuchFileException: target path gone — drive was removed during write.
        if (ex instanceof NoSuchFileException) {
            throw new DriveUnavailableException(ex.getMessage());
        }
        // Base FileSystemException with a reason is a Windows OS-level drive error.
        // Specific subclasses represent normal filesystem operations and are excluded.
        if (ex.getClass() == FileSystemException.class) {
            FileSystemException fse = (FileSystemException) ex;
            String reason = fse.getReason();
            if (reason != null) {
                if (reason.contains("not enough space") || reason.contains("No space left")
                        || reason.contains("insufficient space")) {
                    throw new DiskFullException(fse.getMessage());
                }
                throw new DriveUnavailableException(fse.getMessage());
            }
        }
        // Fallback for non-NIO IOExceptions (e.g. from buffered stream writes).
        String msg = ex.getMessage();
        if (msg == null) {
            return;
        }
        if (msg.contains("No space left") || msg.contains("There is not enough space")
                || msg.contains("insufficient space")) {
            throw new DiskFullException(msg);
        }
        if (msg.contains("device is not ready") || msg.contains("cannot find")
                || msg.contains("cannot access") || msg.contains("unreachable")) {
            throw new DriveUnavailableException(msg);
        }
    }

    // ── Path utilities ────────────────────────────────────────────────────────

    /**
     * Returns the last-chosen export directory, normalized to an absolute path.
     * This reads {@code KEY_USER_LAST_EXPORT_DIR} so that after storage recovery
     * (which saves only to that key) the worker retries with the new location.
     */
    Path resolveExportDirectory() {
        return Path.of(adminSettingsDialogService.getLastExportDir(session.getCurrentUserId())).toAbsolutePath()
                .normalize();
    }

    /**
     * Finds an existing target with matching size across the original file name and
     * conflict suffixes. Size is the available identity for export no-op detection.
     */
    private Path findExistingTargetWithSize(Path dir, String fileName, long sourceSize) throws IOException {
        TargetNameParts parts = splitTargetName(fileName);
        Path originalTarget = dir.resolve(fileName);
        if (Files.exists(originalTarget) && Files.size(originalTarget) == sourceSize) {
            return originalTarget;
        }

        for (int counter = 1;; counter++) {
            Path candidate = dir.resolve(parts.base() + " (" + counter + ")" + parts.ext());
            if (!Files.exists(candidate)) {
                return null;
            }
            if (Files.size(candidate) == sourceSize) {
                return candidate;
            }
        }
    }

    /**
     * Finds a non-existing path by appending (1), (2), ... before the extension.
     * Example: "report.pdf" → "report (1).pdf" → "report (2).pdf".
     */
    private Path resolveUniqueTarget(Path dir, String fileName) {
        TargetNameParts parts = splitTargetName(fileName);
        int counter = 0;
        Path candidate;
        do {
            candidate = counter == 0
                    ? dir.resolve(fileName)
                    : dir.resolve(parts.base() + " (" + counter + ")" + parts.ext());
            counter++;
        } while (Files.exists(candidate));
        return candidate;
    }

    private TargetNameParts splitTargetName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String base = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String ext = dotIndex > 0 ? fileName.substring(dotIndex) : "";
        return new TargetNameParts(base, ext);
    }

    /**
     * Builds the queue leaf row id from its full destination export path. This lets
     * the same source file be queued for several export directories while still
     * deduplicating duplicate exports to the same destination file.
     */
    static String buildProgressTrackerRowId(String fileName, Path exportDir) {
        return exportDir.resolve(fileName).toAbsolutePath().normalize().toString();
    }

    private String buildProgressTrackerRowId(FileView fileView, Path exportDir) {
        return buildProgressTrackerRowId(resolveFileName(fileView), exportDir);
    }

    private String resolveFileName(FileView fileView) {
        if (fileView.name() != null && !fileView.name().isBlank()) {
            return fileView.name();
        }
        return "unknown-file";
    }

    private ExportFileItem createExportFileItem(FileView fileView, String progressTrackerRowId) {
        Long fileSize = fileView.fileSize();
        return new ExportFileItem(fileView, progressTrackerRowId, fileSizeOrMax(fileSize), fileSize);
    }

    private Path resolveSourcePath(FileView fileView) throws IOException {
        String syncedPath = fileView.syncedPath();
        if (syncedPath == null || syncedPath.isBlank()) {
            throw new IOException(ERROR_MISSING_SYNCED_PATH);
        }

        Path resolvedPath = resolveSyncedPath(fileView);
        if (resolvedPath != null) {
            return resolvedPath;
        }

        return restoreMissingSource(fileView);
    }

    private Path resolveSyncedPath(FileView fileView) {
        long expectedSize = fileView.fileSize() != null && fileView.fileSize() > 0 ? fileView.fileSize() : 0;
        PathResolutionResult result = folderManagerService.findAbsolutePathFromNonDriveLetterPath(fileView.syncedPath(),
                expectedSize);
        return result.isFound() ? result.getPath() : null;
    }

    private Path restoreMissingSource(FileView fileView) throws IOException {
        String backedUpPath = fileView.backedUpPath();
        if (backedUpPath == null || backedUpPath.isBlank()) {
            throw new IOException(ERROR_SOURCE_NOT_FOUND);
        }
        if (folderManagerService.getSyncDir() == null) {
            throw new IOException(ERROR_SOURCE_NOT_FOUND);
        }

        BackupSyncResult result = restoreService.restoreSingleFile(folderManagerService.getSyncDir().getAbsolutePath(),
                backedUpPath);
        if (!result.success()) {
            if (result.failureReason() == RestoreFailureReason.BACKUP_NOT_FOUND) {
                throw new IOException(ERROR_SOURCE_NOT_FOUND);
            }
            String message = result.errorMessage() != null ? result.errorMessage() : ERROR_SOURCE_NOT_FOUND;
            throw new IOException(message);
        }
        if (result.restoredPath() == null || result.restoredPath().isBlank()) {
            throw new IOException(ERROR_SOURCE_NOT_FOUND);
        }
        return Path.of(result.restoredPath());
    }

    private long fileSizeOrMax(Long fileSize) {
        return fileSize != null ? fileSize : Long.MAX_VALUE;
    }

    /**
     * Classifies an IOException into the appropriate i18n error key.
     */
    private String classifyExportError(IOException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return ERROR_IO_EXCEPTION;
        }
        if (message.equals(ERROR_MISSING_SYNCED_PATH)) {
            return ERROR_MISSING_SYNCED_PATH;
        }
        if (message.equals(ERROR_SOURCE_NOT_FOUND)) {
            return ERROR_SOURCE_NOT_FOUND;
        }
        return ERROR_IO_EXCEPTION;
    }

    private int normalizeTo5PercentStep(int percent) {
        if (percent < 0) {
            return 0;
        }
        if (percent >= 100) {
            return 100;
        }
        return percent - (percent % 5);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private record ExportFileItem(FileView fileView, String progressTrackerRowId, long sortSize, Long fileSize) {
    }

    private record ExportResult(ItemStatus status, ExportFileItem item, String displayName, String failureReason) {
        private static ExportResult completed(ExportFileItem item, String displayName) {
            return new ExportResult(ItemStatus.COMPLETED, item, displayName, null);
        }

        private static ExportResult failure(ExportFileItem item, String failureReason) {
            return new ExportResult(ItemStatus.FAILED, item, null, failureReason);
        }
    }

    private record FailedExportFile(FileView fileView, String displayName, String reason) {
    }

    private record RecoveryResult(boolean recovered, ExportFileItem item, Path exportDir) {
    }

    private record StorageCheckResult(ExportAttempt attempt, ExportResult failureResult) {
        private static StorageCheckResult retry(ExportAttempt attempt) {
            return new StorageCheckResult(attempt, null);
        }

        private static StorageCheckResult failed(ExportResult failureResult) {
            return new StorageCheckResult(null, failureResult);
        }
    }

    private record TargetNameParts(String base, String ext) {
    }

    private static class ExportAttempt {
        private final ExportFileItem item;
        private final Path exportDir;
        private IOException storageException;

        private ExportAttempt(ExportFileItem item, Path exportDir) {
            this.item = item;
            this.exportDir = exportDir;
        }

        private ExportFileItem item() {
            return item;
        }

        private Path exportDir() {
            return exportDir;
        }

        private String progressTrackerRowId() {
            return item.progressTrackerRowId();
        }

        private IOException storageException() {
            return storageException;
        }

        private void setStorageException(IOException storageException) {
            this.storageException = storageException;
        }
    }

    private static class ExportDirectoryQueue {
        private final Path exportDir;
        private final List<ExportFileItem> files = new ArrayList<>();
        private final List<FailedExportFile> failedFiles = new ArrayList<>();
        private int total;
        private int passed;
        private int failed;

        private ExportDirectoryQueue(Path exportDir) {
            this.exportDir = exportDir;
        }
    }
}
