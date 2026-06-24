package com.app.common.modules.appupdate.controllers;

import com.app.common.helpers.AlertHelper;
import com.app.common.modules.appupdate.models.AppUpdateInfo;
import com.app.common.modules.appupdate.services.AppUpdateService;
import com.app.common.modules.i18n.I18n;
import com.app.common.services.AppNoticeService;
import com.app.common.services.WindowsCommandService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AppUpdateController {

    private static final Logger log = LoggerFactory.getLogger(AppUpdateController.class);
    private static final String INSTALLER_AUTO_UPDATE_ARGUMENT = "/BDMA_AUTO_UPDATE";

    private final AppUpdateService appUpdateService;
    private final AppNoticeService appNoticeService;
    private final WindowsCommandService windowsCommandService;
    private final AtomicBoolean updateInstallInProgress = new AtomicBoolean(false);

    @Setter
    private Runnable onCheckStart;
    @Setter
    private Runnable onCheckEnd;

    public AppUpdateController(AppUpdateService appUpdateService,
            AppNoticeService appNoticeService,
            WindowsCommandService windowsCommandService) {
        this.appUpdateService = appUpdateService;
        this.appNoticeService = appNoticeService;
        this.windowsCommandService = windowsCommandService;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void checkOnStartup() {
        if (!appUpdateService.shouldCheckThisWeek())
            return;
        appUpdateService.saveCheckDate();

        Task<AppUpdateInfo> task = new Task<>() {
            @Override
            protected AppUpdateInfo call() {
                return appUpdateService.checkLatestVersion();
            }
        };

        task.setOnSucceeded(e -> {
            AppUpdateInfo info = task.getValue();
            if (info == null || !info.hasUpdate())
                return;

            String skipped = appUpdateService.getSkippedVersion();
            if (info.latestVersion().equals(skipped))
                return;

            Platform.runLater(() -> showAvailableUpdateDialog(info));
        });

        new Thread(task).start();
    }

    public void onCheckUpdateManual() {
        if (updateInstallInProgress.get()) {
            log.info("Update install flow is already in progress; ignoring manual update check");
            appNoticeService.showSuccess(I18n.get("update.install.inProgress"));
            return;
        }

        if (onCheckStart != null)
            onCheckStart.run();
        log.info("Checking for application updates");

        Task<AppUpdateInfo> task = new Task<>() {
            @Override
            protected AppUpdateInfo call() {
                return appUpdateService.checkLatestVersion();
            }
        };

        task.setOnSucceeded(e -> {
            AppUpdateInfo info = task.getValue();
            Platform.runLater(() -> {
                if (onCheckEnd != null)
                    onCheckEnd.run();
                if (info == null) {
                    log.warn("Update check completed without version information");
                    appNoticeService.showError(I18n.get("update.failed"));
                } else if (!info.hasUpdate()) {
                    log.info("Application is already at the latest version");
                    showUpToDateDialog();
                } else {
                    log.info("Application update available: {}", info.latestVersion());
                    showAvailableUpdateDialog(info);
                }
            });
        });

        task.setOnFailed(e -> Platform.runLater(() -> {
            if (onCheckEnd != null)
                onCheckEnd.run();
            log.warn("Update check failed", task.getException());
            appNoticeService.showError(I18n.get("update.failed"));
        }));

        new Thread(task).start();
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void showUpToDateDialog() {
        Alert alert = AlertHelper.createInformation(
                I18n.get("update.upToDate.title"),
                I18n.get("update.upToDate.header"),
                I18n.get("update.upToDate.content"));
        alert.showAndWait();
    }

    private void showAvailableUpdateDialog(AppUpdateInfo info) {
        Alert alert = AlertHelper.createConfirmation(
                I18n.get("update.available.title"),
                I18n.get("update.available.header", info.latestVersion()),
                I18n.get("update.available.content"));

        ButtonType btnUpdate = new ButtonType(I18n.get("update.btn.update"));
        ButtonType btnSkip = new ButtonType(I18n.get("update.btn.skip"));
        ButtonType btnLater = new ButtonType(I18n.get("update.btn.later"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertHelper.setButtons(alert, btnUpdate, btnSkip, btnLater);
        alert.setOnShown(event -> {
            // Preventing JavaFX from separating cancel-type buttons into a different group.
            var buttonBarNode = alert.getDialogPane().lookup(".button-bar");
            if (buttonBarNode instanceof ButtonBar buttonBar) {
                buttonBar.setButtonOrder(ButtonBar.BUTTON_ORDER_NONE);
            }
        });

        alert.showAndWait().ifPresent(result -> {
            if (result == btnUpdate) {
                log.info("User chose to update to version {}", info.latestVersion());
                downloadAndInstall(info);
            } else if (result == btnSkip) {
                log.info("User skipped version {}", info.latestVersion());
                appUpdateService.saveSkippedVersion(info.latestVersion());
            }
        });
    }

    private void downloadAndInstall(AppUpdateInfo info) {
        if (!updateInstallInProgress.compareAndSet(false, true)) {
            log.info("Update install flow is already in progress; ignoring duplicate request");
            appNoticeService.showSuccess(I18n.get("update.install.inProgress"));
            return;
        }

        log.info("Downloading installer for version {}", info.latestVersion());
        appNoticeService.showSuccess(I18n.get("update.download.started", info.latestVersion()));

        Task<File> task = new Task<>() {
            @Override
            protected File call() throws Exception {
                return appUpdateService.downloadInstaller(info,
                        percent -> appNoticeService.showSuccess(I18n.get("update.download.progress", percent)));
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            File installer = task.getValue();
            appNoticeService.showSuccess(I18n.get("update.download.success"));

            Alert alert = AlertHelper.createInformation(
                    I18n.get("update.install.ready"),
                    I18n.get("update.install.success"),
                    I18n.get("update.install.content"));
            alert.showAndWait();

            launchInstallerThenClose(installer);
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            updateInstallInProgress.set(false);
            log.error("Failed to download installer", task.getException());
            appNoticeService.showError(I18n.get("update.download.failed"));
        }));

        new Thread(task).start();
    }

    private void launchInstallerThenClose(File installer) {
        appNoticeService.showSuccess(I18n.get("update.install.launching"));
        try {
            launchInstallerAfterAppExit(installer);
            Platform.exit();
        } catch (IOException e) {
            updateInstallInProgress.set(false);
            log.error("Failed to queue installer launch", e);
            appNoticeService.showError(I18n.get("update.install.launch.failed"));
        }
    }

    /**
     * Starts a hidden PowerShell watcher that waits for this JVM to exit before
     * opening the elevated installer. This avoids any visible command window while
     * ensuring setup starts after BDMA releases its files.
     *
     * @param installer downloaded installer executable
     * @throws IOException when PowerShell cannot be started
     */
    private void launchInstallerAfterAppExit(File installer) throws IOException {
        windowsCommandService.startInstallerAfterProcessExit(
                ProcessHandle.current().pid(),
                installer,
                INSTALLER_AUTO_UPDATE_ARGUMENT);
    }
}
