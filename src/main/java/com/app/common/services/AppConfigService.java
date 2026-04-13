package com.app.common.services;

import org.springframework.stereotype.Service;

import com.app.common.repositories.AppConfigRepository;

import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;

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
     * Saves a config value by key to the app_config table.
     */
    public void saveConfigValue(String key, String value) {
        repository.saveValue(key, value);
    }

    /**
     * Gets all config values as a map.
     */
    public Map<String, String> getAllConfig() {
        return repository.findAll();
    }
}