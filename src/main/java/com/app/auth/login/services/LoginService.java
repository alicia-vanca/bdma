package com.app.auth.login.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.models.User;
import com.app.common.modules.databackup.DataBackupRunner;
import com.app.common.modules.databackup.services.DataBackupService;
import com.app.common.modules.datasync.DataSyncRunner;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.session.Session;

@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final Session session;
    private final DataSyncRunner syncRunner;
    private final DataBackupRunner backupRunner;
    private final DataBackupService backupService;
    private final FolderManagerService folderManager;

    public LoginService(Session session,
            DataSyncRunner syncRunner,
            DataBackupRunner backupRunner,
            DataBackupService backupService,
            FolderManagerService folderManager) {
        this.session = session;
        this.syncRunner = syncRunner;
        this.backupRunner = backupRunner;
        this.backupService = backupService;
        this.folderManager = folderManager;
    }

    public void onLoginSuccess() {

        User user = session.getUser();
        if (user == null) {
            log.warn("onLoginSuccess called but session is empty.");
            return;
        }

        // Ensure data and backup directories are initialized and accessible
        folderManager.init();

        syncRunner.startSyncWorker();
        backupRunner.startBackupWorker();

        // Clear deferred backup flag and notify user if drive still unavailable
        backupService.recoverPendingBackups();

        log.info("Login init [{}] [{}] finished.",
                user.getUsername(),
                user.getRole());
    }
}
