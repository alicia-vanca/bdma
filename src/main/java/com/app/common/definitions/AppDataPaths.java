package com.app.common.definitions;

import java.io.File;
import java.nio.file.Path;

public final class AppDataPaths {

    private static final String APP_DIR = Path.of(System.getProperty("user.home"), "AppData", "Local", "bdma")
            .toString();

    private AppDataPaths() {
    }

    public static String appDir() {
        return APP_DIR;
    }

    public static String configDir() {
        return Path.of(APP_DIR, "config").toString();
    }

    public static String logsDir() {
        return Path.of(APP_DIR, "logs").toString();
    }

    public static String appTmpDir() {
        return Path.of(APP_DIR, "tmp", "app").toString();
    }

    public static String adbTmpDir() {
        return Path.of(APP_DIR, "tmp", "adb").toString();
    }

    public static String sqliteTmpDir() {
        return Path.of(APP_DIR, "tmp", "sqlite").toString();
    }

    public static File dataFile() {
        return Path.of(APP_DIR, "data.db").toFile();
    }
}