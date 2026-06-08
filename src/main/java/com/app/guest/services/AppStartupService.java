package com.app.guest.services;

import com.app.common.modules.databackup.DataBackupRunner;
import com.app.common.modules.databackup.services.DataBackupService;
import com.app.common.modules.datasync.DataSyncRunner;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import org.springframework.stereotype.Component;

@Component
public class AppStartupService {
    private final FolderManagerService folderManager;
    private final DataSyncRunner syncRunner;
    private final DataBackupRunner backupRunner;
    private final DataBackupService backupService;

    public AppStartupService(
            FolderManagerService folderManager,
            DataSyncRunner syncRunner,
            DataBackupRunner backupRunner,
            DataBackupService backupService) {
        this.folderManager = folderManager;
        this.syncRunner = syncRunner;
        this.backupRunner = backupRunner;
        this.backupService = backupService;
    }


    public void initialize() {

        folderManager.init();

        syncRunner.startSyncWorker();

        backupRunner.startBackupWorker();

        backupService.recoverPendingBackups();
    }
}
