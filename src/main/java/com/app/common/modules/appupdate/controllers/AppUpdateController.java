package com.app.common.modules.appupdate.controllers;

import com.app.common.modules.i18n.I18n;
import com.app.common.helpers.AlertHelper;
import com.app.common.modules.appupdate.models.AppUpdateInfo;
import com.app.common.modules.appupdate.services.AppUpdateService;
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
import java.util.function.Consumer;

@Component
public class AppUpdateController {

    private static final Logger log = LoggerFactory.getLogger(AppUpdateController.class);

    private final AppUpdateService appUpdateService;

    @Setter
    private Runnable onCheckStart;
    @Setter
    private Runnable onCheckEnd;
    @Setter
    private Consumer<String> onStatusChange;

    public AppUpdateController(AppUpdateService appUpdateService) {
        this.appUpdateService = appUpdateService;
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

            Platform.runLater(() -> showUpdateDialog(info));
        });

        new Thread(task).start();
    }

    public void onCheckUpdateManual() {
        if (onCheckStart != null)
            onCheckStart.run();
        updateStatus(I18n.get("update.checking"));

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
                if (!info.hasUpdate()) {
                    updateStatus(I18n.get("update.latest"));
                } else {
                    updateStatus(I18n.get("update.available", info.latestVersion()));
                    showUpdateDialog(info);
                }
            });
        });

        task.setOnFailed(e -> Platform.runLater(() -> {
            if (onCheckEnd != null)
                onCheckEnd.run();
            updateStatus(I18n.get("update.failed"));
            log.warn("Update check failed", task.getException());
        }));

        new Thread(task).start();
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void updateStatus(String msg) {
        if (onStatusChange != null)
            onStatusChange.accept(msg);
    }

    private void showUpdateDialog(AppUpdateInfo info) {
        Alert alert = AlertHelper.createConfirmation(
                I18n.get("update.title"),
                I18n.get("update.header", info.latestVersion()),
                I18n.get("update.content"));

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
        log.info("Downloading installer for version {}", info.latestVersion());

        Task<File> task = new Task<>() {
            @Override
            protected File call() throws Exception {
                return appUpdateService.downloadInstaller(info);
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            try {
                File installer = task.getValue();
                log.info("Installer downloaded: {}", installer.getAbsolutePath());

                Alert alert = AlertHelper.createInformation(
                        I18n.get("update.install.ready"),
                        I18n.get("update.install.success"),
                        I18n.get("update.install.content"));
                alert.showAndWait();

                new ProcessBuilder(installer.getAbsolutePath()).start();
                Platform.exit();

            } catch (Exception ex) {
                log.error("Failed to launch installer", ex);
            }
        }));

        task.setOnFailed(e -> Platform.runLater(() -> log.error("Failed to download installer", task.getException())));

        new Thread(task).start();
    }
}