package com.app.common.modules.foldermanager.services;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.modules.foldermanager.events.StorageRestoredEvent;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.session.Session;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class StorageHealthMonitor {

    private final FolderManagerService folderManagerService;
    private final ApplicationEventPublisher publisher;
    private final Session session;

    public StorageHealthMonitor(FolderManagerService folderManagerService, ApplicationEventPublisher publisher,
            Session session) {
        this.folderManagerService = folderManagerService;
        this.publisher = publisher;
        this.session = session;
    }

    public void checkStorageHealth(FolderType target) {
        // Skip health check if user not logged in
        if (session.getUser() == null) {
            return;
        }
        if (target == null || target == FolderType.SYNC) {
            boolean syncDriveAccessible = folderManagerService.isDataDirAccessible();
            publishState(FolderType.SYNC, syncDriveAccessible);
        }
        if (target == null || target == FolderType.BACKUP) {
            boolean backupDriveAccessible = folderManagerService.isBackupDirAccessible();
            publishState(FolderType.BACKUP, backupDriveAccessible);
        }
    }

    public void checkNow(FolderType target) {
        checkStorageHealth(target);
    }

    private void publishState(FolderType target, boolean accessible) {
        if (target == FolderType.SYNC) {
            if (!accessible) {
                log.debug("StorageHealthMonitor firing StorageUnavailableEvent: target={} reason={}",
                        FolderType.SYNC, StorageIssueReason.DRIVE_UNAVAILABLE);
                publisher.publishEvent(
                        new StorageUnavailableEvent(FolderType.SYNC, StorageIssueReason.DRIVE_UNAVAILABLE));
            } else if (accessible) {
                log.debug("StorageHealthMonitor firing StorageRestoredEvent: target={}", FolderType.SYNC);
                publisher.publishEvent(new StorageRestoredEvent(FolderType.SYNC));
            }
            return;
        }

        if (target == FolderType.BACKUP) {
            if (!accessible) {
                log.debug("StorageHealthMonitor firing StorageUnavailableEvent: target={} reason={}",
                        FolderType.BACKUP, StorageIssueReason.DRIVE_UNAVAILABLE);
                publisher.publishEvent(
                        new StorageUnavailableEvent(FolderType.BACKUP, StorageIssueReason.DRIVE_UNAVAILABLE));
            } else if (accessible) {
                log.debug("StorageHealthMonitor firing StorageRestoredEvent: target={}", FolderType.BACKUP);
                publisher.publishEvent(new StorageRestoredEvent(FolderType.BACKUP));
            }
        }
    }
}
