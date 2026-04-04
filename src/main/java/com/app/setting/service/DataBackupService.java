package com.app.setting.service;

import com.app.common.config.AppConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DataBackupService {

    private static final Logger log = LoggerFactory.getLogger(DataBackupService.class);

    private final AppConfigService appConfigService;

    public DataBackupService(AppConfigService appConfigService) {
        this.appConfigService = appConfigService;
    }

    public boolean getAutoDelete() {
        return appConfigService.getConfig().getBodyCam().isAutoDelete();
    }

    public void setAutoDelete(boolean autoDelete) {
        appConfigService.saveBodyCamConfig(autoDelete);
        log.info("BodyCam autoDelete set to: {}", autoDelete);
    }
}