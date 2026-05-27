package com.app.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public final class AppRuntimeInitializer {

    private static final Logger log = LoggerFactory.getLogger(AppRuntimeInitializer.class);

    private AppRuntimeInitializer() {
    }

    public static void initialize() {
        String appDir = AppPaths.appDir();

        ensureBaseDirectories();
        ensureDatabaseFile();
        configureSqlite();
        forceLoadSqliteDriver();
        initializeAppContext(appDir);

    }

    private static void ensureBaseDirectories() {
        ensureDir(AppPaths.appDir());
        ensureDir(AppPaths.configDir());
        ensureDir(AppPaths.tmpDir());
    }

    private static void ensureDir(String path) {
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("Failed to create directory: {}", path);
        }
    }

    private static void ensureDatabaseFile() {
        File dbFile = AppPaths.dataFile();
        try {
            if (!dbFile.exists() && dbFile.createNewFile()) {
                log.info("Database file created: {}", dbFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to create DB file: {}", dbFile.getAbsolutePath(), e);
        }
    }

    private static void configureSqlite() {
        System.setProperty("org.sqlite.tmpdir", AppPaths.tmpDir());
    }

    private static void forceLoadSqliteDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (Exception e) {
            log.error("Failed to load SQLite driver", e);
        }
    }

    private static void initializeAppContext(String appDir) {
        DeviceIdManager deviceIdManager = new DeviceIdManager(appDir);
        AppContext.setDeviceId(deviceIdManager.getDeviceId());

        String version = AppRuntimeInitializer.class.getPackage().getImplementationVersion();
        if (version == null) {
            version = System.getProperty("app.version", "dev");
        }
        AppContext.setVersion(version);

        log.info("App started — version: {}, deviceId: {}", AppContext.getVersion(), AppContext.getDeviceId());
    }
}