package com.app.common.modules.dataexport.services;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.MainApp;
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.common.dtos.FileView;
import com.app.common.modules.dataexport.workers.DataExportWorker;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.services.AppNoticeService;

import javafx.application.Platform;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

/**
 * Public entry point for file export. Resolves the destination directory,
 * shows the "request received" notice, and delegates all queue and copy work to
 * {@link DataExportWorker}.
 */
@Service
public class DataExportService {

    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);

    private final DataExportWorker worker;
    private final AdminSettingsDialogService adminSettingsDialogService;
    private final Session session;
    private final AppNoticeService appNoticeService;
    private final FolderManagerService folderManagerService;

    public DataExportService(DataExportWorker worker,
            AdminSettingsDialogService adminSettingsDialogService,
            Session session,
            AppNoticeService appNoticeService,
            FolderManagerService folderManagerService) {
        this.worker = worker;
        this.adminSettingsDialogService = adminSettingsDialogService;
        this.session = session;
        this.appNoticeService = appNoticeService;
        this.folderManagerService = folderManagerService;
    }

    /**
     * Resolves the export directory, shows the "request received" notice, and
     * submits files to the export worker.
     */
    public void exportSelectedFiles(List<FileView> selectedFiles) {
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            return;
        }

        Path exportDir;
        try {
            exportDir = resolveExportDirectoryForRequest();
        } catch (Exception ex) {
            log.error("Failed to resolve export directory", ex);
            appNoticeService.showError(I18n.get("setting.storage.error"));
            return;
        }

        if (exportDir == null) {
            log.debug("Export directory selection cancelled");
            return;
        }

        appNoticeService.showSuccess(I18n.get("file.export.notice.start"));
        worker.enqueueFiles(selectedFiles, exportDir);
    }

    /**
     * Stops all pending exports and resets the worker for the next session.
     * Called on logout.
     */
    public void resetForLogout() {
        worker.cancelAndCleanup();
        worker.resetExecutor();
        log.info("Export worker reset for logout");
    }

    // ── Directory resolution ──────────────────────────────────────────────────

    /**
     * Returns the export directory for this request.
     *
     * <p>
     * When ask-every-time is disabled:
     * <ul>
     * <li>No dir configured → save Downloads as the default, return it.</li>
     * <li>Dir configured but drive unavailable → open chooser with an
     * "unavailable" title, save the pick as the new configured default, return
     * it.</li>
     * <li>Dir configured and available → return it directly.</li>
     * </ul>
     *
     * <p>
     * When ask-every-time is enabled, opens the directory chooser initialised to
     * the last-chosen dir and only saves the last-chosen key after picking.
     */
    private Path resolveExportDirectoryForRequest() {
        Long userId = session.getCurrentUserId();

        if (!adminSettingsDialogService.getAskEveryTimeExport(userId)) {
            return resolveFixedExportDir(userId);
        }

        Path selectedPath = chooseExportDirectoryFromUiThread(
                I18n.get("setting.storage.chooser.title", I18n.get("storage.type.export")));
        if (selectedPath != null) {
            // Only update the last-chosen dir; the configured default is not changed.
            adminSettingsDialogService.saveLastExportDir(userId, selectedPath.toString());
        }
        return selectedPath;
    }

    /**
     * Resolves the export directory when ask-every-time is disabled.
     * Handles the null-config and drive-unavailable cases inline.
     */
    private Path resolveFixedExportDir(Long userId) {
        Path exportDir = Path.of(adminSettingsDialogService.getExportFolderForUser(userId)).toAbsolutePath()
                .normalize();
        if (!folderManagerService.isDriveAccessible(exportDir.toFile())) {
            exportDir = chooseExportDirectoryFromUiThread(
                    I18n.get("file.export.dir.unavailable.chooser.title"));
            if (exportDir != null) {
                adminSettingsDialogService.saveExportFolderForUser(userId, exportDir.toString());
            }
        }
        return exportDir;
    }

    /**
     * Opens a directory chooser on the FX application thread with the given title
     * and returns the selection, or null if the user cancelled. Blocks the calling
     * thread via a CountDownLatch when called off the FX thread.
     */
    private Path chooseExportDirectoryFromUiThread(String chooserTitle) {
        AtomicReference<Path> selected = new AtomicReference<>();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable task = () -> {
            try {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle(chooserTitle);

                // Open picker at the user's last-chosen export directory; falls back to
                // Downloads if that drive is no longer accessible.
                Long userId = session.getCurrentUserId();
                Path current = Path.of(adminSettingsDialogService.getLastExportDir(userId))
                        .toAbsolutePath().normalize();
                if (current.toFile().exists()) {
                    chooser.setInitialDirectory(current.toFile());
                }

                Stage owner = MainApp.getPrimaryStage();
                java.io.File selectedDir = chooser.showDialog(owner);
                if (selectedDir != null) {
                    selected.set(selectedDir.toPath().toAbsolutePath().normalize());
                }
            } catch (Exception ex) {
                error.set(new RuntimeException(ex));
            } finally {
                latch.countDown();
            }
        };

        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
            try {
                latch.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        if (error.get() != null) {
            throw error.get();
        }
        return selected.get();
    }
}
