package com.app.common.modules.foldermanager.services;

import java.util.concurrent.TimeUnit;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.app.common.definitions.enums.FolderType;
import com.app.common.modules.foldermanager.events.StorageIssueReason;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;

@Component
public class StorageHealthMonitor {

    private final FolderManagerService folderManagerService;
    private final ApplicationEventPublisher publisher;

    private volatile boolean dataUnavailablePublished;
    private volatile boolean backupUnavailablePublished;

    public StorageHealthMonitor(FolderManagerService folderManagerService, ApplicationEventPublisher publisher) {
        this.folderManagerService = folderManagerService;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void checkStorageHealth() {
        boolean dataAccessible = folderManagerService.isDataDirAccessible();
        boolean backupAccessible = folderManagerService.isBackupDirAccessible();

        publishState(FolderType.SAVE, dataAccessible);
        publishState(FolderType.BACKUP, backupAccessible);
    }

    public void checkNow() {
        checkStorageHealth();
    }

    private void publishState(FolderType target, boolean accessible) {
        if (target == FolderType.SAVE) {
            if (!accessible && !dataUnavailablePublished) {
                publisher.publishEvent(
                        new StorageUnavailableEvent(FolderType.SAVE, StorageIssueReason.DRIVE_UNAVAILABLE));
                dataUnavailablePublished = true;
            } else if (accessible && dataUnavailablePublished) {
                publisher.publishEvent(new StorageRestoredEvent(FolderType.SAVE));
                dataUnavailablePublished = false;
            }
            return;
        }

        if (!accessible && !backupUnavailablePublished) {
            publisher
                    .publishEvent(new StorageUnavailableEvent(FolderType.BACKUP, StorageIssueReason.DRIVE_UNAVAILABLE));
            backupUnavailablePublished = true;
        } else if (accessible && backupUnavailablePublished) {
            publisher.publishEvent(new StorageRestoredEvent(FolderType.BACKUP));
            backupUnavailablePublished = false;
        }
    }
}
