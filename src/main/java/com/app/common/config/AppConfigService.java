package com.app.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

@Service
public class AppConfigService {

    private static final Logger log = LoggerFactory.getLogger(AppConfigService.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    private AppConfig config;

    public void loadOrCreate() {
        File file = AppPaths.appConfigFile();
        if (!file.exists()) {
            log.info("app-config.json not found, creating default...");
            config = new AppConfig();
            persist(config);
        } else {
            try {
                config = objectMapper.readValue(file, AppConfig.class);
                boolean normalized = normalizeConfig(config);
                if (normalized) {
                    persist(config);
                }
                log.info("app-config.json loaded.");
            } catch (IOException e) {
                log.error("Failed to read app-config.json, using default.", e);
                config = new AppConfig();
            }
        }
    }

    public void save(AppConfig updated) {
        this.config = updated;
        persist(updated);
    }

    public void saveBodyCamConfig(boolean autoDelete) {
        AppConfig cfg = getConfig();
        cfg.getBodyCam().setAutoDelete(autoDelete);
        persist(cfg);
        log.info("BodyCam config saved — autoDelete={}", autoDelete);
    }

    public AppConfig getConfig() {
        if (config == null) loadOrCreate();
        return config;
    }

    private boolean normalizeConfig(AppConfig cfg) {
        boolean changed = false;

        if (cfg.getStorage() == null) {
            cfg.setStorage(new AppConfig.Storage());
            changed = true;
        }

        if (cfg.getBodyCam() == null) {
            cfg.setBodyCam(new AppConfig.BodyCam());
            changed = true;
        }

        return changed;
    }

    private void persist(AppConfig cfg) {
        try {
            objectMapper.writeValue(AppPaths.appConfigFile(), cfg);
            log.info("app-config.json saved.");
        } catch (IOException e) {
            log.error("Failed to save app-config.json", e);
        }
    }
}