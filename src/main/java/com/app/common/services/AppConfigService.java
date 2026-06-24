package com.app.common.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.repositories.AppConfigRepository;

@Service

public class AppConfigService {
    private final AppConfigRepository repository;

    @Autowired
    public AppConfigService(AppConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * Gets a config value by key from the app_config table.
     */
    public String getConfigValue(String key) {
        return repository.findValue(key).orElse(null);
    }

    /**
     * Gets a config value by folder type from the app_config table.
     */
    public String getConfigValue(FolderType folderType) {
        String key = switch (folderType) {
            case SYNC -> AppConstants.KEY_SYNC_DIR;
            case BACKUP -> AppConstants.KEY_BACKUP_DIR;
            default -> AppConstants.KEY_EXPORT_DIR;
        };
        return repository.findValue(key).orElse(null);
    }

    /**
     * Saves a config value by key to the app_config table.
     */
    public void saveConfigValue(String key, String value) {
        repository.saveValue(key, value);
    }

    /**
     * Saves a config value by folder type to the app_config table.
     */
    public void saveConfigValue(FolderType folderType, String value) {
        String key = switch (folderType) {
            case SYNC -> AppConstants.KEY_SYNC_DIR;
            case BACKUP -> AppConstants.KEY_BACKUP_DIR;
            default -> AppConstants.KEY_EXPORT_DIR;
        };
        saveConfigValue(key, value);
    }
}
