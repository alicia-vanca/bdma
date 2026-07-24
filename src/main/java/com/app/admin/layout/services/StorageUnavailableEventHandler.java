package com.app.admin.layout.services;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.MainApp;
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.helpers.AlertHelper;
import com.app.common.modules.foldermanager.events.StorageRecoveryDeferredEvent;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.foldermanager.services.DefaultStorageLocationService;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.foldermanager.services.StorageHealthMonitor;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.services.AppConfigService;
import com.app.common.services.AppNoticeService;
import com.app.common.services.UserSettingService;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.DirectoryChooser;

@Component
public class StorageUnavailableEventHandler {

    private static final Logger log = LoggerFactory.getLogger(StorageUnavailableEventHandler.class);

    private static final String STATUS_SYNC_DRIVE = "status.syncDrive";
    private static final String STATUS_BACKUP_DRIVE = "status.backupDrive";
    private static final String STATUS_EXPORT_DRIVE = "status.exportDrive";
    private static final String STATUS_DECRYPT_OUTPUT_FOLDER = "status.decryptOutputFolder";
    private static final String DIALOG_REASON_LOW_SPACE = "low-space";
    private static final String DIALOG_REASON_UNAVAILABLE = "unavailable";
    private static final String I18N_STORAGE_LOW_SPACE_TITLE = "storage.unavailable.dialog.low_space.title";
    private static final String I18N_STORAGE_LOW_SPACE_USER_MESSAGE = "storage.unavailable.dialog.low_space.user.message";
    private static final String I18N_STORAGE_LOW_SPACE_ADMIN_MESSAGE = "storage.unavailable.dialog.low_space.admin.message";
    private static final String I18N_STORAGE_DRIVE_MISSING_TITLE = "storage.unavailable.dialog.drive_missing.title";
    private static final String I18N_STORAGE_DRIVE_MISSING_USER_MESSAGE = "storage.unavailable.dialog.drive_missing.user.message";
    private static final String I18N_STORAGE_DRIVE_MISSING_ADMIN_MESSAGE = "storage.unavailable.dialog.drive_missing.admin.message";
    // Job-specific dialog messages include the failing path ({0}) and remaining
    // file count ({1}).
    private static final String I18N_STORAGE_EXPORT_DRIVE_MISSING_TITLE = "storage.unavailable.dialog.export.drive_missing.title";
    private static final String I18N_STORAGE_EXPORT_DRIVE_MISSING_ADMIN_MESSAGE = "storage.unavailable.dialog.export.drive_missing.admin.message";
    private static final String I18N_STORAGE_EXPORT_LOW_SPACE_TITLE = "storage.unavailable.dialog.export.low_space.title";
    private static final String I18N_STORAGE_EXPORT_LOW_SPACE_ADMIN_MESSAGE = "storage.unavailable.dialog.export.low_space.admin.message";
    private static final String I18N_STORAGE_DECRYPT_DRIVE_MISSING_TITLE = "storage.unavailable.dialog.decrypt.drive_missing.title";
    private static final String I18N_STORAGE_DECRYPT_DRIVE_MISSING_ADMIN_MESSAGE = "storage.unavailable.dialog.decrypt.drive_missing.admin.message";
    private static final String I18N_STORAGE_DECRYPT_LOW_SPACE_TITLE = "storage.unavailable.dialog.decrypt.low_space.title";
    private static final String I18N_STORAGE_DECRYPT_LOW_SPACE_ADMIN_MESSAGE = "storage.unavailable.dialog.decrypt.low_space.admin.message";
    private static final String I18N_SETTING_STORAGE_BTN_CHOOSE = "setting.storage.btn.choose";
    private static final String I18N_STORAGE_ACTION_LATER = "storage.unavailable.action.later";
    private static final String I18N_SETTING_STORAGE_CHOOSER_TITLE = "setting.storage.chooser.title";
    private static final String I18N_SETTING_STORAGE_SUCCESS = "setting.storage.success";
    private static final String I18N_SETTING_STORAGE_ERROR = "setting.storage.error";
    private static final String I18N_SETTING_STORAGE_LOW_SPACE = "setting.storage.low_space";
    private static final String I18N_SETTING_STORAGE_DRIVE_MISSING = "setting.storage.drive_missing";
    private static final String I18N_SETTING_STORAGE_REMOVABLE_MESSAGE = "setting.storage.removable.message";

    private final Session session;
    private final ApplicationEventPublisher publisher;
    private final FolderManagerService folderManagerService;
    private final StorageHealthMonitor storageHealthMonitor;
    private final AdminSettingsDialogService adminSettingsService;
    private final AppConfigService appConfigService;
    private final UserSettingService userSettingService;
    private final AppNoticeService appNoticeService;
    private final DefaultStorageLocationService defaultStorageLocationService;

    private boolean syncLowSpaceDialogVisible;
    private boolean backupLowSpaceDialogVisible;
    private boolean exportLowSpaceDialogVisible;
    private boolean decryptLowSpaceDialogVisible;
    private boolean syncUnavailableDialogVisible;
    private boolean backupUnavailableDialogVisible;
    private boolean exportUnavailableDialogVisible;
    private boolean decryptUnavailableDialogVisible;
    private volatile StorageIssueReason syncStorageBlocked;
    private CountDownLatch syncDialogLatch;
    private CountDownLatch backupDialogLatch;
    private CountDownLatch exportDialogLatch;
    private CountDownLatch decryptDialogLatch;

    @SuppressWarnings("unused")
    private volatile StorageIssueReason backupStorageBlocked;

    @SuppressWarnings("unused")
    private volatile StorageIssueReason exportStorageBlocked;

    @SuppressWarnings("unused")
    private volatile StorageIssueReason decryptStorageBlocked;

    private record FolderSelectionResult(boolean saved, String rejectionMessage, File selectedFolder) {
    }

    public StorageUnavailableEventHandler(Session session,
            ApplicationEventPublisher publisher,
            FolderManagerService folderManagerService,
            StorageHealthMonitor storageHealthMonitor,
            AdminSettingsDialogService adminSettingsService,
            AppConfigService appConfigService,
            UserSettingService userSettingService,
            AppNoticeService appNoticeService,
            DefaultStorageLocationService defaultStorageLocationService) {
        this.session = session;
        this.publisher = publisher;
        this.folderManagerService = folderManagerService;
        this.storageHealthMonitor = storageHealthMonitor;
        this.adminSettingsService = adminSettingsService;
        this.appConfigService = appConfigService;
        this.userSettingService = userSettingService;
        this.appNoticeService = appNoticeService;
        this.defaultStorageLocationService = defaultStorageLocationService;
    }

    public boolean isStorageBlocked(FolderType target) {
        return switch (target) {
            case SYNC -> syncStorageBlocked != null;
            case BACKUP -> backupStorageBlocked != null;
            case EXPORT -> exportStorageBlocked != null;
            case DECRYPT -> decryptStorageBlocked != null;
        };
    }

    private boolean isLowSpaceDialogVisible(FolderType target) {
        return switch (target) {
            case SYNC -> syncLowSpaceDialogVisible;
            case BACKUP -> backupLowSpaceDialogVisible;
            case EXPORT -> exportLowSpaceDialogVisible;
            case DECRYPT -> decryptLowSpaceDialogVisible;
        };
    }

    private boolean isUnavailableDialogVisible(FolderType target) {
        return switch (target) {
            case SYNC -> syncUnavailableDialogVisible;
            case BACKUP -> backupUnavailableDialogVisible;
            case EXPORT -> exportUnavailableDialogVisible;
            case DECRYPT -> decryptUnavailableDialogVisible;
        };
    }

    private void setLowSpaceDialogVisible(FolderType target, boolean visible) {
        switch (target) {
            case SYNC -> syncLowSpaceDialogVisible = visible;
            case BACKUP -> backupLowSpaceDialogVisible = visible;
            case EXPORT -> exportLowSpaceDialogVisible = visible;
            case DECRYPT -> decryptLowSpaceDialogVisible = visible;
        }
    }

    private void setUnavailableDialogVisible(FolderType target, boolean visible) {
        switch (target) {
            case SYNC -> syncUnavailableDialogVisible = visible;
            case BACKUP -> backupUnavailableDialogVisible = visible;
            case EXPORT -> exportUnavailableDialogVisible = visible;
            case DECRYPT -> decryptUnavailableDialogVisible = visible;
        }
    }

    private CountDownLatch getDialogLatch(FolderType target) {
        return switch (target) {
            case SYNC -> syncDialogLatch;
            case BACKUP -> backupDialogLatch;
            case EXPORT -> exportDialogLatch;
            case DECRYPT -> decryptDialogLatch;
        };
    }

    private void setDialogLatch(FolderType target, CountDownLatch latch) {
        switch (target) {
            case SYNC -> syncDialogLatch = latch;
            case BACKUP -> backupDialogLatch = latch;
            case EXPORT -> exportDialogLatch = latch;
            case DECRYPT -> decryptDialogLatch = latch;
        }
    }

    private String targetLabelKey(FolderType target) {
        return switch (target) {
            case SYNC -> STATUS_SYNC_DRIVE;
            case BACKUP -> STATUS_BACKUP_DRIVE;
            case EXPORT -> STATUS_EXPORT_DRIVE;
            case DECRYPT -> STATUS_DECRYPT_OUTPUT_FOLDER;
        };
    }

    /**
     * Returns the display label for the failing storage location.
     * When the event carries a specific failing directory (e.g. export jobs),
     * the directory path is returned so the user sees the exact location
     * ("F:\childDir") rather than a generic "Export drive" label.
     */
    private String resolveTargetLabel(StorageUnavailableEvent event) {
        if (event.getFailingDir() != null) {
            return event.getFailingDir().toString();
        }
        return I18n.get(targetLabelKey(event.getTarget()));
    }

    private void setStorageBlocked(FolderType target, StorageIssueReason reason) {
        switch (target) {
            case SYNC -> syncStorageBlocked = reason;
            case BACKUP -> backupStorageBlocked = reason;
            case EXPORT -> exportStorageBlocked = reason;
            case DECRYPT -> decryptStorageBlocked = reason;
        }
    }

    private void setDialogVisibleFlag(StorageUnavailableEvent event, boolean visible) {
        boolean isLowSpace = event.getReason() == StorageIssueReason.LOW_SPACE;
        FolderType target = event.getTarget();

        if (isLowSpace) {
            setLowSpaceDialogVisible(target, visible);
        } else {
            setUnavailableDialogVisible(target, visible);
        }
    }

    private String dialogReasonLabel(boolean lowSpace) {
        return lowSpace ? DIALOG_REASON_LOW_SPACE : DIALOG_REASON_UNAVAILABLE;
    }

    private boolean isMatchingDialogVisible(FolderType target, boolean lowSpace) {
        return lowSpace ? isLowSpaceDialogVisible(target) : isUnavailableDialogVisible(target);
    }

    private boolean isDialogAlreadyVisible(StorageUnavailableEvent event) {
        boolean isLowSpace = event.getReason() == StorageIssueReason.LOW_SPACE;
        FolderType target = event.getTarget();

        boolean visible = isLowSpace
                ? isLowSpaceDialogVisible(target)
                : isUnavailableDialogVisible(target);
        if (visible) {
            logDebug("{} {} dialog already visible. Skip duplicate.", target,
                    dialogReasonLabel(isLowSpace));
            return true;
        }
        return false;
    }

    private boolean shouldQueueDialog(StorageUnavailableEvent event) {
        boolean isLowSpace = event.getReason() == StorageIssueReason.LOW_SPACE;
        FolderType target = event.getTarget();

        Optional<FolderType> visibleTarget = findVisibleDialogTarget(target, isLowSpace);
        visibleTarget.ifPresent(other -> {
            String reasonLabel = dialogReasonLabel(isLowSpace);
            logDebug("{} {} dialog is opening. Queueing {} {} dialog.",
                    other,
                    reasonLabel,
                    target,
                    reasonLabel);
        });
        return visibleTarget.isPresent();
    }

    private Optional<FolderType> findVisibleDialogTarget(FolderType target, boolean lowSpace) {
        for (FolderType other : FolderType.values()) {
            if (other != target && isMatchingDialogVisible(other, lowSpace)) {
                return Optional.of(other);
            }
        }
        return Optional.empty();
    }

    private void queueDialogUntilOtherCloses(StorageUnavailableEvent event) {
        boolean isLowSpace = event.getReason() == StorageIssueReason.LOW_SPACE;
        FolderType target = event.getTarget();
        CountDownLatch nextLatchToWait = findVisibleDialogTarget(target, isLowSpace)
                .map(this::getDialogLatch)
                .orElse(null);
        final CountDownLatch latchToWait = nextLatchToWait;

        new Thread(() -> {
            try {
                if (latchToWait != null) {
                    latchToWait.await();
                }
                Platform.runLater(() -> showStorageUnavailableDialog(event));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                setDialogVisibleFlag(event, false);
            }
        }, "storage-dialog-queue").start();
    }

    @EventListener
    public void onStorageUnavailable(StorageUnavailableEvent event) {
        if (event == null) {
            return;
        }
        logDebug("StorageUnavailableEvent: target: {}, reason: {}", event.getTarget(), event.getReason());

        if (isDialogAlreadyVisible(event)) {
            return;
        }

        boolean shouldQueue = shouldQueueDialog(event);
        setDialogVisibleFlag(event, true);

        if (shouldQueue) {
            queueDialogUntilOtherCloses(event);
        } else {
            setDialogLatch(event.getTarget(), new CountDownLatch(1));
            Platform.runLater(() -> showStorageUnavailableDialog(event));
        }
    }

    private void showStorageUnavailableDialog(StorageUnavailableEvent event) {
        logDebug("Opening storage-unavailable dialog: target={} reason={} requiredBytes={}",
                event.getTarget(), event.getReason(), event.getRequiredBytes());

        CountDownLatch latch = getDialogLatch(event.getTarget());

        try {
            FolderType target = event.getTarget();
            setStorageBlocked(target, event.getReason());
            String targetDrive = resolveTargetLabel(event);

            // Non-admin can't change sync/backup storage but can choose job output dirs.
            if (!session.isAdmin() && event.getTarget() != FolderType.EXPORT && event.getTarget() != FolderType.DECRYPT) {
                String header = "";
                String message = "";
                if (event.getReason() == StorageIssueReason.LOW_SPACE) {
                    header = I18n.get(I18N_STORAGE_LOW_SPACE_TITLE, targetDrive);
                    message = I18n.get(I18N_STORAGE_LOW_SPACE_USER_MESSAGE, targetDrive);
                } else if (event.getReason() == StorageIssueReason.DRIVE_UNAVAILABLE) {
                    header = I18n.get(I18N_STORAGE_DRIVE_MISSING_TITLE, targetDrive);
                    message = I18n.get(I18N_STORAGE_DRIVE_MISSING_USER_MESSAGE, targetDrive);
                }

                Alert alert = AlertHelper.createInformation(header, header, message);
                alert.showAndWait();
                publisher.publishEvent(new StorageRecoveryDeferredEvent(event.getTarget(), event.getReason()));
            } else {
                Alert alert = createStorageUnavailableAlert(event);
                handleStorageDialogLoop(event, alert);
            }
        } finally {
            setDialogVisibleFlag(event, false);
            setStorageBlocked(event.getTarget(), null);
            if (latch != null) {
                latch.countDown();
            }
            setDialogLatch(event.getTarget(), null);
            logDebug("Storage unavailable dialog closed. target={}", event.getTarget());
        }
    }

    private Alert createStorageUnavailableAlert(StorageUnavailableEvent event) {
        String targetDrive = resolveTargetLabel(event);
        String header;
        String content;
        if (event.getTarget() == FolderType.EXPORT || event.getTarget() == FolderType.DECRYPT) {
            // Job-specific messages include the folder path and remaining file count.
            int remaining = event.getRemainingCount();
            boolean decryptTarget = event.getTarget() == FolderType.DECRYPT;
            if (event.getReason() == StorageIssueReason.LOW_SPACE) {
                header = I18n.get(decryptTarget ? I18N_STORAGE_DECRYPT_LOW_SPACE_TITLE : I18N_STORAGE_EXPORT_LOW_SPACE_TITLE);
                content = I18n.get(
                        decryptTarget ? I18N_STORAGE_DECRYPT_LOW_SPACE_ADMIN_MESSAGE : I18N_STORAGE_EXPORT_LOW_SPACE_ADMIN_MESSAGE,
                        targetDrive, remaining);
            } else {
                header = I18n.get(decryptTarget ? I18N_STORAGE_DECRYPT_DRIVE_MISSING_TITLE : I18N_STORAGE_EXPORT_DRIVE_MISSING_TITLE);
                content = I18n.get(
                        decryptTarget ? I18N_STORAGE_DECRYPT_DRIVE_MISSING_ADMIN_MESSAGE : I18N_STORAGE_EXPORT_DRIVE_MISSING_ADMIN_MESSAGE,
                        targetDrive, remaining);
            }
        } else if (event.getReason() == StorageIssueReason.LOW_SPACE) {
            header = I18n.get(I18N_STORAGE_LOW_SPACE_TITLE, targetDrive);
            content = I18n.get(I18N_STORAGE_LOW_SPACE_ADMIN_MESSAGE, targetDrive);
        } else {
            header = I18n.get(I18N_STORAGE_DRIVE_MISSING_TITLE, targetDrive);
            content = I18n.get(I18N_STORAGE_DRIVE_MISSING_ADMIN_MESSAGE, targetDrive);
        }
        Alert alert = AlertHelper.createConfirmation(header, header, content);

        ButtonType chooseButton = new ButtonType(I18n.get(I18N_SETTING_STORAGE_BTN_CHOOSE),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType laterButton = new ButtonType(I18n.get(I18N_STORAGE_ACTION_LATER),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertHelper.setButtons(alert, chooseButton, laterButton);

        return alert;
    }

    private void handleStorageDialogLoop(StorageUnavailableEvent event, Alert alert) {
        while (true) {
            Optional<ButtonType> chosen = alert.showAndWait();

            if (chosen.isEmpty()) {
                logDebug("Storage-unavailable dialog dismissed without selecting folder. target={}", event.getTarget());
                return;
            }

            ButtonType selectedButton = chosen.get();

            if (isLaterButtonSelected(selectedButton)) {
                handleLaterSelection(event);
                return;
            }

            if (!isChooseButtonSelected(selectedButton)) {
                logDebug("Storage-unavailable dialog closed with unexpected action. target={} action={}",
                        event.getTarget(), selectedButton.getText());
                return;
            }

            logDebug("User selected 'Select folder' on storage-unavailable dialog. target={}", event.getTarget());

            if (handleFolderSelection(event)) {
                return;
            }
        }
    }

    private boolean isLaterButtonSelected(ButtonType selected) {
        return selected.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE;
    }

    private boolean isChooseButtonSelected(ButtonType selected) {
        return selected.getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    private void handleLaterSelection(StorageUnavailableEvent event) {
        logDebug("User selected 'Later' on storage-unavailable dialog. target={} reason={} requiredBytes={}",
                event.getTarget(), event.getReason(), event.getRequiredBytes());

        publisher.publishEvent(new StorageRecoveryDeferredEvent(event.getTarget(), event.getReason()));
    }

    private boolean handleFolderSelection(StorageUnavailableEvent event) {
        FolderSelectionResult result = chooseAndSaveStorageFolder(event);

        if (result.saved()) {
            logDebug("Storage folder updated successfully from unavailable dialog. target={}", event.getTarget());
            handleSuccessfulFolderSave(event, result.selectedFolder());
            return true;
        }

        if (result.rejectionMessage() != null) {
            logDebug("Selected storage folder rejected. target={} message={}", event.getTarget(),
                    result.rejectionMessage());

            String header = "";
            if (event.getReason() == StorageIssueReason.LOW_SPACE) {
                header = I18n.get(I18N_STORAGE_LOW_SPACE_TITLE, I18n.get("status.selectedDrive"));
            } else if (event.getReason() == StorageIssueReason.DRIVE_UNAVAILABLE) {
                header = I18n.get(I18N_STORAGE_DRIVE_MISSING_TITLE, I18n.get("status.selectedDrive"));
            }
            showStorageSelectionError(header, result.rejectionMessage());
        } else {
            logDebug("Folder selection canceled by user. target={}", event.getTarget());
        }

        return false;
    }

    private void handleSuccessfulFolderSave(StorageUnavailableEvent event, File selectedFolder) {
        setStorageBlocked(event.getTarget(), null);
        publisher.publishEvent(new StorageRestoredEvent(event.getTarget()));
    }

    @EventListener
    public void onStorageRestored(StorageRestoredEvent event) {
        if (event != null) {
            setStorageBlocked(event.getTarget(), null);
        }
    }

    private void logDebug(String message, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug(message, args);
        }
    }

    private FolderSelectionResult chooseAndSaveStorageFolder(StorageUnavailableEvent event) {
        FolderType target = event.getTarget();
        if (target == FolderType.SYNC || target == FolderType.BACKUP) {
            defaultStorageLocationService.refreshVolumesAsync();
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get(I18N_SETTING_STORAGE_CHOOSER_TITLE,
                target.toLocalizedString()));
        resolveInitialChooserDirectory(event).ifPresent(chooser::setInitialDirectory);

        File selected = chooser.showDialog(MainApp.getPrimaryStage());
        if (selected == null) {
            if (log.isDebugEnabled()) {
                log.debug("Folder chooser canceled. target={}", target);
            }
            return new FolderSelectionResult(false, null, null);
        }

        if (log.isDebugEnabled()) {
            log.debug("Folder selected from chooser. target={} path={} requiredBytes={}",
                    target, selected.getAbsolutePath(), event.getRequiredBytes());
        }

        String rejection = validateSelectedStorageFolder(target, selected, event.getRequiredBytes());
        if (rejection != null) {
            return new FolderSelectionResult(false, rejection, null);
        }

        saveSelectedStorageFolder(target, selected);
        appNoticeService.showSuccess(I18n.get(I18N_SETTING_STORAGE_SUCCESS));
        if (log.isInfoEnabled()) {
            log.info("Storage folder changed from unavailable dialog. target={} path={}",
                    target, selected.getAbsolutePath());
        }
        return new FolderSelectionResult(true, null, selected);
    }

    private Optional<File> resolveInitialChooserDirectory(StorageUnavailableEvent event) {
        FolderType target = event.getTarget();
        // When the event carries the exact failing dir, open the chooser there so the
        // user navigates from a relevant starting point.
        if (event.getFailingDir() != null && event.getFailingDir().toFile().exists()) {
            return Optional.of(event.getFailingDir().toFile());
        }
        if (target == FolderType.EXPORT || target == FolderType.DECRYPT) {
            // For job output dirs the failing dir is usually unavailable; fall back to
            // Downloads so the chooser opens in a writable location.
            File downloads = new File(System.getProperty("user.home"), "Downloads");
            return downloads.exists() ? Optional.of(downloads) : Optional.empty();
        }
        File configuredRoot = readConfiguredRootPath(target);
        return configuredRoot != null && configuredRoot.exists() ? Optional.of(configuredRoot) : Optional.empty();
    }

    private void saveSelectedStorageFolder(FolderType target, File selected) {
        if (target == FolderType.EXPORT) {
            // Export uses a per-user last-chosen dir; no managed subfolder or health
            // monitor.
            adminSettingsService.saveLastExportDir(session.getCurrentUserId(), selected.getAbsolutePath());
        } else if (target == FolderType.DECRYPT) {
            saveDecryptOutputFolder(selected.getAbsolutePath());
        } else {
            adminSettingsService.saveFolder(target, selected.getAbsolutePath());
            folderManagerService.init(target);
            storageHealthMonitor.checkNow(target);
        }
    }

    private void saveDecryptOutputFolder(String selectedPath) {
        if (session.isGuest()) {
            appConfigService.saveConfigValue(AppConstants.DECRYPT_OUTPUT_DIR, selectedPath);
        } else {
            userSettingService.saveConfigValue(session.getCurrentUserId(), AppConstants.DECRYPT_OUTPUT_DIR, selectedPath);
        }
    }

    private void showStorageSelectionError(String header, String message) {
        if (log.isDebugEnabled()) {
            log.debug("Showing storage selection error dialog: {}", message);
        }
        Alert error = AlertHelper.create(Alert.AlertType.ERROR,
                I18n.get(I18N_SETTING_STORAGE_ERROR),
                header,
                message);
        AlertHelper.setButtons(error, ButtonType.OK);
        error.showAndWait();
    }

    private File readConfiguredRootPath(FolderType target) {
        String path = adminSettingsService.getFolderPath(target).orElse(null);
        if (path == null || path.isBlank()) {
            return null;
        }
        return new File(path);
    }

    private String validateSelectedStorageFolder(FolderType target, File selectedRoot, long requiredBytes) {
        if (target == FolderType.EXPORT || target == FolderType.DECRYPT) {
            // Job output writes directly to the chosen dir — no managed subfolder.
            if (!folderManagerService.isDriveAccessible(selectedRoot)) {
                return I18n.get(I18N_SETTING_STORAGE_DRIVE_MISSING);
            }
            if (requiredBytes > 0 && !folderManagerService.hasSufficientSpace(selectedRoot, requiredBytes)) {
                return I18n.get(I18N_SETTING_STORAGE_LOW_SPACE);
            }
            return null;
        }

        // For sync/backup: validate through the managed subfolder.
        if (defaultStorageLocationService.isOnRemovableVolume(selectedRoot.toPath())) {
            return I18n.get(I18N_SETTING_STORAGE_REMOVABLE_MESSAGE);
        }

        String folderName = target.getPhysicalFolderName();
        File managedDir = new File(selectedRoot, folderName);

        if (!folderManagerService.isDirAccessible(managedDir)) {
            return I18n.get(I18N_SETTING_STORAGE_DRIVE_MISSING);
        }

        if (requiredBytes > 0 && !folderManagerService.hasSufficientSpace(managedDir, requiredBytes)) {
            return I18n.get(I18N_SETTING_STORAGE_LOW_SPACE);
        }

        try {
            folderManagerService.withSpecificDirPrepared(managedDir, () -> Boolean.TRUE);
        } catch (Exception e) {
            log.warn("Rejected storage folder selection for {}: {}", target, selectedRoot.getAbsolutePath(), e);
            return I18n.get(I18N_SETTING_STORAGE_ERROR);
        }

        return null;
    }
}
