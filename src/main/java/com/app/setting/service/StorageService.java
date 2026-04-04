package com.app.setting.service;

import com.app.common.config.AppConfig;
import com.app.common.config.AppConfigService;
import com.app.common.enums.FolderType;
import com.app.file.service.DataFolderManager;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class StorageService {

    private final AppConfigService appConfigService;
    private final DataFolderManager dataFolderManager;

    public StorageService(AppConfigService appConfigService,
                          DataFolderManager dataFolderManager) {
        this.appConfigService = appConfigService;
        this.dataFolderManager = dataFolderManager;
    }

    public Optional<String> getActiveFolderPath(FolderType type) {
        AppConfig.Storage storage = appConfigService.getConfig().getStorage();
        String path = switch (type) {
            case SAVE -> storage.getDataDir();
            case BACKUP -> storage.getBackupDir();
        };
        return (path != null && !path.isBlank()) ? Optional.of(path) : Optional.empty();
    }

    public void saveFolder(String path, FolderType type) {
        AppConfig config = appConfigService.getConfig();
        if (type == FolderType.SAVE) {
            config.getStorage().setDataDir(path);
        } else {
            config.getStorage().setBackupDir(path);
        }
        appConfigService.save(config);

        dataFolderManager.init();
    }
}