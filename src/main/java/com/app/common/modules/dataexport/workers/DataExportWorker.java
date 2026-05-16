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
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.dtos.FileView;
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

import jakarta.annotation.PreDestroy;
import javafx.application.Platform;

/**
 * Single-threaded worker that copies queued export files to the destination
 * directory and handles storage errors. {@link DataExportService} is the public
 * entry point; this class owns the executor, all queue state, and storage
 * recovery signalling.
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

    // Key prefix used to namespace export target paths; parsed by the queue UI to
    // extract the dir label.
    public static final String FILE_KEY_PREFIX = "export:target:";

    private final FolderManagerService folderManagerService;
    private final QueueManagerService queueManagerService;
    private final AdminSettingsDialogService adminSettingsDialogService;
    private final AppNoticeService appNoticeService;
    private final ApplicationEventPublisher eventPublisher;
    private final Session session;

    private final AtomicReference<ExecutorService> executorRef = new AtomicReference<>(newExecutor());

    // Guards recovery state; notified by Spring event listeners, waited on by the
    // worker thread.
    private final Object recoveryLock = new Object();
    private volatile boolean exportRecoveryDeferred = false;

    // Tracks full export paths currently QUEUED or PROCESSING to prevent duplicate
    // submissions to the same destination file.
    private final Set<String> activeExportPaths = ConcurrentHashMap.newKeySet();

    // Tracks export requests that reached a terminal state in the current
    // lifecycle.
    // This prevents a repeated export click from re-queuing completed or failed
    // rows
    // before the completion notice closes the lifecycle.
    private final Set<String> processedExportRequests = ConcurrentHashMap.newKeySet();

    // Tracks .tmp paths currently being written so they can be deleted on cancel.
    private final Set<Path> activeTmpPaths = ConcurrentHashMap.newKeySet();

    // Directory-first queue: each directory queue owns its pending file queue. The
    // worker drains one directory fully before moving to the next directory.
    private final Object queueLock = new Object();
    private final List<ExportDirectoryQueue> directoryQueues = new ArrayList<>();
    private final ConcurrentHashMap<Path, ExportDirectoryQueue> directoryQueueByPath = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, List<FailedExportFile>> failedFilesByDirectory = new ConcurrentHashMap<>();
    private boolean processorRunning;

    // Counts files currently in all directory queues plus the one being processed.
    // Drops to zero when every pending file finishes; triggers the completion
    // notice.
    private final AtomicInteger pendingFileCount = new AtomicInteger(0);
    private final AtomicInteger queuedFileCount = new AtomicInteger(0);
    private final AtomicInteger failedFileCount = new AtomicInteger(0);

    // Missing sources still show size 0 in the UI/free-space check, but sort after
    // resolvable files so they fail only after valid exports have run.
    private static final Comparator<ExportFileItem> EXPORT_FILE_SIZE_COMPARATOR = Comparator
            .comparing(ExportFileItem::sourceMissing)
            .thenComparingLong(ExportFileItem::sortSize);

    DataExportWorker(FolderManagerService folderManagerService,
            QueueManagerService queueManagerService,
            AdminSettingsDialogService adminSettingsDialogService,
            AppNoticeService appNoticeService,
            ApplicationEventPublisher eventPublisher,
            Session session) {
        this.folderManagerService = folderManagerService;
        this.queueManagerService = queueManagerService;
        this.adminSettingsDialogService = adminSettingsDialogService;
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
     * Call {@link #resetExecutor()} afterwards when the worker should remain
     * usable.
     */
    public void cancelAndCleanup() {
        executorRef.get().shutdownNow();
        activeExportPaths.clear();
        processedExportRequests.clear();
        synchronized (queueLock) {
            directoryQueues.clear();
            directoryQueueByPath.clear();
            failedFilesByDirectory.clear();
            processorRunning = false;
        }
        pendingFileCount.set(0);
        queuedFileCount.set(0);
        failedFileCount.set(0);
        for (Path tmp : activeTmpPaths) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ex) {
                log.warn("Failed to delete tmp file on cancel: {}", tmp, ex);
            }
        }
        activeTmpPaths.clear();
    }

    /** Replaces the executor so the worker can accept new tasks after a reset. */
    public void resetExecutor() {
        executorRef.set(newExecutor());
    }

    @PreDestroy
    public void onShutdown() {
        cancelAndCleanup();
        log.info("Export worker shut down");
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
            if (!directoryQueueByPath.containsKey(normalizedDir)) {
                // A finished directory starts a fresh queue run when the user retries or
                // exports more files to the same destination.
                queueManagerService.resetExportDirectoryForNewRun(normalizedDir);
                processedExportRequests.removeIf(exportPathId -> isExportPathInDirectory(exportPathId, normalizedDir));
            }
        }

        List<ExportFileItem> acceptedItems = new ArrayList<>();
        for (FileView fileView : selectedFiles) {
            String exportPathId = buildExportPathId(fileView, normalizedDir);
            if (!processedExportRequests.contains(exportPathId) && activeExportPaths.add(exportPathId)) {
                acceptedItems.add(createExportFileItem(fileView, exportPathId));
            }
        }
        acceptedItems.sort(EXPORT_FILE_SIZE_COMPARATOR);

        if (acceptedItems.isEmpty()) {
            log.info("Export request skipped: no new files accepted for dir={} received={}",
                    normalizedDir, selectedFiles.size());
            return;
        }

        // Reset summary counters when the whole export queue is idle.
        if (pendingFileCount.get() == 0) {
            queuedFileCount.set(0);
            failedFileCount.set(0);
        }
        pendingFileCount.addAndGet(acceptedItems.size());
        queuedFileCount.addAndGet(acceptedItems.size());

        synchronized (queueLock) {
            ExportDirectoryQueue directoryQueue = directoryQueueByPath.computeIfAbsent(normalizedDir, dir -> {
                ExportDirectoryQueue created = new ExportDirectoryQueue(dir);
                directoryQueues.add(created);
                return created;
            });

            for (ExportFileItem item : acceptedItems) {
                directoryQueue.files.add(item);
                queueManagerService.addFileToExportTracker(item.exportPathId(), resolveFileName(item.fileView()),
                        item.displaySize(), item.sortSize());
            }
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
     * Drains one directory queue at a time. New files appended while processing are
     * picked up before the worker moves to the next directory.
     */
    private void processDirectoryQueues() {
        while (true) {
            ExportDirectoryQueue directoryQueue;
            synchronized (queueLock) {
                directoryQueue = directoryQueues.isEmpty() ? null : directoryQueues.get(0);
                if (directoryQueue == null) {
                    processorRunning = false;
                    return;
                }
            }

            processDirectoryQueue(directoryQueue);
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
            directoryQueueByPath.remove(directoryQueue.exportDir, directoryQueue);
        }

        if (directoryQueue.total == 0) {
            return;
        }

        queueManagerService.markExportDirectoryFinished(directoryQueue.exportDir, directoryQueue.total,
                directoryQueue.passed, directoryQueue.failed,
                directoryQueue.failed > 0 ? () -> retryFailedDirectoryFiles(directoryQueue.exportDir) : null);
        showDirectoryFailureSummary(directoryQueue);
    }

    private void processDirectoryQueue(ExportDirectoryQueue directoryQueue) {
        while (true) {
            ExportFileItem item;
            synchronized (queueLock) {
                item = directoryQueue.files.isEmpty() ? null : directoryQueue.files.remove(0);
                if (item == null) {
                    finishDirectoryQueue(directoryQueue);
                    return;
                }
            }

            ExportResult result = ExportResult.failure(item, ERROR_UNKNOWN);
            try {
                result = runFileCopy(item, directoryQueue);
            } finally {
                ExportDirectoryQueue resultQueue = resolveResultDirectoryQueue(directoryQueue, result);
                recordDirectoryResult(resultQueue, result);
                logProcessedExportFile(resultQueue, result);
                processedExportRequests.add(result.item().exportPathId());
                if (result.status() == ItemStatus.FAILED) {
                    failedFileCount.incrementAndGet();
                }
                activeExportPaths.remove(result.item().exportPathId());
                if (pendingFileCount.decrementAndGet() == 0) {
                    Platform.runLater(this::fireCompletionNotice);
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
        String exportPathId = item.exportPathId();
        FileView fileView = item.fileView();
        String fileName = resolveFileName(fileView);
        Path currentExportDir = directoryQueue.exportDir;

        try {
            queueManagerService.markExportFileProcessing(exportPathId);

            while (true) {
                Path exportDir = currentExportDir;

                // Check drive accessibility before any I/O; on failure pause and retry.
                if (!folderManagerService.isDriveAccessible(exportDir.toFile())) {
                    RecoveryResult result = pauseAndRecover(item, exportDir,
                            StorageIssueReason.DRIVE_UNAVAILABLE, ERROR_EXPORT_DRIVE_UNAVAILABLE);
                    if (!result.recovered()) {
                        return ExportResult.failure(item, ERROR_EXPORT_DRIVE_UNAVAILABLE);
                    }
                    item = result.item();
                    exportPathId = item.exportPathId();
                    currentExportDir = result.exportDir();
                    continue;
                }

                // Check free space before writing; on failure pause and retry.
                long requiredBytes = fileView.fileSize() != null ? fileView.fileSize() : 0;
                if (!folderManagerService.hasSufficientSpace(exportDir.toFile(), requiredBytes)) {
                    RecoveryResult result = pauseAndRecover(item, exportDir,
                            StorageIssueReason.LOW_SPACE, ERROR_EXPORT_DISK_FULL);
                    if (!result.recovered()) {
                        return ExportResult.failure(item, ERROR_EXPORT_DISK_FULL);
                    }
                    item = result.item();
                    exportPathId = item.exportPathId();
                    currentExportDir = result.exportDir();
                    continue;
                }

                // Source resolution failures are data errors — no retry.
                Path source;
                try {
                    source = resolveSourcePath(fileView);
                } catch (IOException ex) {
                    String reason = classifyExportError(ex);
                    log.error("Export failed for {}", fileName, ex);
                    queueManagerService.markExportFileFailed(exportPathId, reason);
                    return ExportResult.failure(item, reason);
                }

                try {
                    Files.createDirectories(exportDir);

                    Path matchingTarget = findExistingTargetWithSize(exportDir, fileName, Files.size(source));
                    if (matchingTarget != null) {
                        // A previous lifecycle may have exported this file under a conflict
                        // suffix, so count this request as a successful no-op.
                        String exportedFileName = matchingTarget.getFileName().toString();
                        queueManagerService.renameExportFile(exportPathId, exportedFileName);
                        queueManagerService.markExportFileCompleted(exportPathId);
                        processedExportRequests.add(exportPathId);
                        return ExportResult.completed(item, exportedFileName);
                    }

                    Path target = resolveUniqueTarget(exportDir, fileName);
                    String exportedFileName = target.getFileName().toString();
                    queueManagerService.renameExportFile(exportPathId, exportedFileName);
                    copyWithProgress(source, target, exportPathId);
                    queueManagerService.markExportFileCompleted(exportPathId);
                    processedExportRequests.add(exportPathId);
                    return ExportResult.completed(item, exportedFileName);

                } catch (DiskFullException ex) {
                    RecoveryResult result = pauseAndRecover(item, currentExportDir,
                            StorageIssueReason.LOW_SPACE, ERROR_EXPORT_DISK_FULL);
                    if (!result.recovered()) {
                        return ExportResult.failure(item, ERROR_EXPORT_DISK_FULL);
                    }
                    item = result.item();
                    exportPathId = item.exportPathId();
                    currentExportDir = result.exportDir();
                    // continue loop to retry with new dir
                } catch (DriveUnavailableException ex) {
                    RecoveryResult result = pauseAndRecover(item, currentExportDir,
                            StorageIssueReason.DRIVE_UNAVAILABLE, ERROR_EXPORT_DRIVE_UNAVAILABLE);
                    if (!result.recovered()) {
                        return ExportResult.failure(item, ERROR_EXPORT_DRIVE_UNAVAILABLE);
                    }
                    item = result.item();
                    exportPathId = item.exportPathId();
                    currentExportDir = result.exportDir();
                    // continue loop to retry with new dir
                } catch (IOException ex) {
                    // FileSystemException from createDirectories means the drive went away.
                    try {
                        rethrowAsStorageException(ex);
                    } catch (DiskFullException dfe) {
                        RecoveryResult result = pauseAndRecover(item, currentExportDir,
                                StorageIssueReason.LOW_SPACE, ERROR_EXPORT_DISK_FULL);
                        if (!result.recovered()) {
                            return ExportResult.failure(item, ERROR_EXPORT_DISK_FULL);
                        }
                        item = result.item();
                        exportPathId = item.exportPathId();
                        currentExportDir = result.exportDir();
                        continue;
                    } catch (DriveUnavailableException due) {
                        RecoveryResult result = pauseAndRecover(item, currentExportDir,
                                StorageIssueReason.DRIVE_UNAVAILABLE, ERROR_EXPORT_DRIVE_UNAVAILABLE);
                        if (!result.recovered()) {
                            return ExportResult.failure(item, ERROR_EXPORT_DRIVE_UNAVAILABLE);
                        }
                        item = result.item();
                        exportPathId = item.exportPathId();
                        currentExportDir = result.exportDir();
                        continue;
                    } catch (IOException ignored) {
                        // rethrowAsStorageException only re-throws as storage exceptions.
                    }
                    log.error("Export failed for {}", fileName, ex);
                    queueManagerService.markExportFileFailed(exportPathId, ERROR_IO_EXCEPTION);
                    return ExportResult.failure(item, ERROR_IO_EXCEPTION);
                }
            }

        } catch (Exception ex) {
            log.error("Export failed for {}", fileName, ex);
            queueManagerService.markExportFileFailed(exportPathId, ERROR_UNKNOWN);
            return ExportResult.failure(item, ERROR_UNKNOWN);
        }
    }

    /**
     * Pauses the current directory queue on a storage error. Remaining count is
     * exactly the current processing item plus the items still waiting in this
     * directory queue.
     */
    private RecoveryResult pauseAndRecover(ExportFileItem processingItem, Path currentExportDir,
            StorageIssueReason reason, String errorMessage) {
        String exportPathId = processingItem.exportPathId();
        Path normalizedCurrentDir = currentExportDir.toAbsolutePath().normalize();
        ExportDirectoryQueue directoryQueue;
        int remainingCount;
        synchronized (queueLock) {
            directoryQueue = directoryQueueByPath.get(normalizedCurrentDir);
            remainingCount = 1 + (directoryQueue != null ? directoryQueue.files.size() : 0);
        }

        log.error("Export storage error [{}]: {} (remaining={})", reason, normalizedCurrentDir, remainingCount);
        queueManagerService.markExportFileDeferred(exportPathId, errorMessage);

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

        queueManagerService.markExportFileFailed(processingItem.exportPathId(), errorMessage);

        for (ExportFileItem pending : pendingItems) {
            queueManagerService.markExportFileFailed(pending.exportPathId(), errorMessage);
            recordDirectoryResult(directoryQueue, ExportResult.failure(pending, errorMessage));
            processedExportRequests.add(pending.exportPathId());
            failedFileCount.incrementAndGet();
            activeExportPaths.remove(pending.exportPathId());
            if (pendingFileCount.decrementAndGet() == 0) {
                Platform.runLater(this::fireCompletionNotice);
            }
        }
    }

    /**
     * Moves the current processing item and every remaining item in the directory
     * queue to a new export directory. If that directory already has a queue, the
     * remaining items are merged into it.
     */
    private ExportFileItem moveDirectoryQueue(ExportDirectoryQueue oldQueue, ExportFileItem processingItem,
            Path newDir) {
        Path normalizedNewDir = newDir.toAbsolutePath().normalize();
        List<ExportFileItem> pendingItems;
        synchronized (queueLock) {
            pendingItems = new ArrayList<>(oldQueue.files);
            oldQueue.files.clear();
            directoryQueues.remove(oldQueue);
            directoryQueueByPath.remove(oldQueue.exportDir, oldQueue);
        }

        ExportFileItem updatedProcessingItem = moveExportItem(processingItem, normalizedNewDir);
        List<ExportFileItem> movedPendingItems = new ArrayList<>();
        for (ExportFileItem pending : pendingItems) {
            movedPendingItems.add(moveExportItem(pending, normalizedNewDir));
        }

        synchronized (queueLock) {
            ExportDirectoryQueue targetQueue = directoryQueueByPath.computeIfAbsent(normalizedNewDir, dir -> {
                ExportDirectoryQueue created = new ExportDirectoryQueue(dir);
                directoryQueues.add(created);
                return created;
            });
            targetQueue.files.addAll(movedPendingItems);
            targetQueue.files.sort(EXPORT_FILE_SIZE_COMPARATOR);
        }
        return updatedProcessingItem;
    }

    private ExportFileItem moveExportItem(ExportFileItem item, Path newDir) {
        String oldExportPathId = item.exportPathId();
        String newExportPathId = buildExportPathId(resolveFileName(item.fileView()), newDir);
        queueManagerService.moveExportFile(oldExportPathId, newExportPathId);
        activeExportPaths.remove(oldExportPathId);
        activeExportPaths.add(newExportPathId);
        return new ExportFileItem(item.fileView(), newExportPathId, item.sourceMissing(), item.sortSize(),
                item.displaySize());
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
            while (!exportRecoveryDeferred && !Thread.currentThread().isInterrupted()) {
                try {
                    recoveryLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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
        Path resultDir = extractExportDir(result.item().exportPathId());
        if (resultDir == null || fallbackQueue.exportDir.equals(resultDir)) {
            return fallbackQueue;
        }

        synchronized (queueLock) {
            return directoryQueueByPath.computeIfAbsent(resultDir, dir -> {
                ExportDirectoryQueue created = new ExportDirectoryQueue(dir);
                directoryQueues.add(created);
                return created;
            });
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
     * Requeues only the failed files for the same export directory.
     */
    private void retryFailedDirectoryFiles(Path exportDir) {
        Path normalizedDir = exportDir.toAbsolutePath().normalize();
        List<FailedExportFile> failedFiles = failedFilesByDirectory.getOrDefault(normalizedDir, List.of());
        List<FileView> filesToRetry = failedFiles.stream()
                .map(FailedExportFile::fileView)
                .toList();
        enqueueFiles(filesToRetry, normalizedDir);
    }

    private boolean isExportPathInDirectory(String exportPathId, Path exportDir) {
        Path parent = extractExportDir(exportPathId);
        return parent != null && parent.equals(exportDir);
    }

    private Path extractExportDir(String exportPathId) {
        if (exportPathId == null || !exportPathId.startsWith(FILE_KEY_PREFIX)) {
            return null;
        }
        Path parent = Path.of(exportPathId.substring(FILE_KEY_PREFIX.length())).getParent();
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
    private void copyWithProgress(Path source, Path target, String exportPathId) throws IOException {
        Path targetTmp = Path.of(target.toString() + AppConstants.TMP_EXTENSION);
        long totalBytes = Files.size(source);

        activeTmpPaths.add(targetTmp);
        try {
            if (totalBytes <= 0) {
                Files.copy(source, targetTmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(targetTmp, target, StandardCopyOption.REPLACE_EXISTING);
                queueManagerService.updateExportFileProgress(exportPathId, 100);
                return;
            }

            long copied = 0;
            int lastReported = 0;

            try (InputStream in = Files.newInputStream(source);
                    OutputStream out = Files.newOutputStream(targetTmp)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    writeChunk(out, buffer, read);
                    copied += read;
                    int percent = (int) ((copied * 100) / totalBytes);
                    int normalized = normalizeTo5PercentStep(percent);
                    if (normalized >= lastReported + 5 && normalized <= 100) {
                        lastReported = normalized;
                        queueManagerService.updateExportFileProgress(exportPathId, normalized);
                    }
                }
            } catch (IOException ex) {
                rethrowAsStorageException(ex);
                throw ex;
            }

            try {
                Files.move(targetTmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                rethrowAsStorageException(ex);
                throw ex;
            }

            if (lastReported < 100) {
                queueManagerService.updateExportFileProgress(exportPathId, 100);
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
        // NoSuchFileException: target path gone — drive was removed during the write.
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
     * Builds the queue item id from its full destination export path. This lets the
     * same source file be queued for several export directories while still
     * deduplicating duplicate exports to the same destination file.
     */
    static String buildExportPathId(String fileName, Path exportDir) {
        return FILE_KEY_PREFIX + exportDir.resolve(fileName).toAbsolutePath().normalize();
    }

    private String buildExportPathId(FileView fileView, Path exportDir) {
        return buildExportPathId(resolveFileName(fileView), exportDir);
    }

    private String resolveFileName(FileView fileView) {
        if (fileView.name() != null && !fileView.name().isBlank()) {
            return fileView.name();
        }
        return "unknown-file";
    }

    private ExportFileItem createExportFileItem(FileView fileView, String exportPathId) {
        boolean sourceMissing = isSourceMissing(fileView);
        long sortSize = sourceMissing ? Long.MAX_VALUE : fileSizeOrMax(fileView.fileSize());
        Long displaySize = sourceMissing ? 0L : fileView.fileSize();
        return new ExportFileItem(fileView, exportPathId, sourceMissing, sortSize, displaySize);
    }

    /**
     * Performs a lightweight source lookup for queue ordering. Missing files are
     * still enqueued so the normal export path records the final failure reason.
     */
    private boolean isSourceMissing(FileView fileView) {
        try {
            resolveSourcePath(fileView);
            return false;
        } catch (IOException ex) {
            return true;
        }
    }

    private Path resolveSourcePath(FileView fileView) throws IOException {
        String syncedPath = fileView.syncedPath();
        if (syncedPath == null || syncedPath.isBlank()) {
            throw new IOException(ERROR_MISSING_SYNCED_PATH);
        }
        long expectedSize = fileView.fileSize() != null && fileView.fileSize() > 0 ? fileView.fileSize() : 0;
        PathResolutionResult result = folderManagerService.findAbsolutePathFromNonDriveLetterPath(syncedPath,
                expectedSize);
        if (result.isFound() && result.getPath() != null) {
            return result.getPath();
        }
        throw new IOException(ERROR_SOURCE_NOT_FOUND);
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

    private record ExportFileItem(FileView fileView, String exportPathId, boolean sourceMissing, long sortSize,
            Long displaySize) {
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

    private record TargetNameParts(String base, String ext) {
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

    private static class DiskFullException extends IOException {
        DiskFullException(String message) {
            super(message);
        }
    }

    private static class DriveUnavailableException extends IOException {
        DriveUnavailableException(String message) {
            super(message);
        }
    }
}
