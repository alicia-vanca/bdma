package com.app.dev.settingsdialog.controllers;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.app.admin.layout.controllers.AdminLayoutController;
import com.app.admin.settingsdialog.controllers.AdminSettingsDialogController;
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.common.definitions.enums.Role;
import com.app.common.modules.datarestore.services.RestoreService;
import com.app.auth.totp.services.DevOtpGuardService;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.modules.databackup.queues.DataBackupQueue;
import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import com.app.common.modules.foldermanager.services.DefaultStorageLocationService;
import com.app.common.modules.session.Session;
import com.app.common.repositories.RestoreFailureRepository;
import com.app.common.services.DriveResolverService;
import com.app.common.services.UserSettingService;

/**
 * Developer settings dialog mirrors the admin settings dialog so DEV can manage
 * advanced storage and application preferences without sharing admin FXML
 * paths.
 */
@Component
public class DevSettingsDialogController extends AdminSettingsDialogController {

    private final DevOtpGuardService devOtpGuardService;

    public DevSettingsDialogController(
            AdminSettingsDialogService adminSettingsService,
            Session session,
            UserSettingService userSettingService,
            AppUpdateController appUpdateController,
            DriveResolverService driveResolverService,
            AdminLayoutController adminLayoutController,
            ApplicationEventPublisher eventPublisher,
            RestoreService restoreService,
            DeviceSyncQueue deviceSyncQueue,
            DataBackupQueue dataBackupQueue,
            RestoreFailureRepository restoreFailureRepository,
            DefaultStorageLocationService defaultStorageLocationService,
            DevOtpGuardService devOtpGuardService) {
        super(adminSettingsService,
                session,
                userSettingService,
                appUpdateController,
                driveResolverService,
                adminLayoutController,
                eventPublisher,
                restoreService,
                deviceSyncQueue,
                dataBackupQueue,
                restoreFailureRepository,
                defaultStorageLocationService);
        this.devOtpGuardService = devOtpGuardService;
    }

    @Override
    protected void openUserInfo() {
        if (!devOtpGuardService.promptProtectedAction(null)) {
            return;
        }
        super.openUserInfo();
    }

    @Override
    protected Role getResetScope() {
        return Role.DEV;
    }
}
