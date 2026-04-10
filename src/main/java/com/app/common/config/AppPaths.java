package com.app.common.config;

import java.io.File;

public final class AppPaths {

    private static final String APP_DIR = System.getProperty("user.home") + "/AppData/Local/" +
            "bdma";

    private AppPaths() {
    }

    public static String appDir() {
        return APP_DIR;
    }

    public static String configDir() {
        return APP_DIR + "/config";
    }

    public static String logsDir() {
        return APP_DIR + "/logs";
    }

    public static String appTmpDir() {
        return APP_DIR + "/tmp/app";
    }

    public static String adbTmpDir() {
        return APP_DIR + "/tmp/adb";
    }

    public static String sqliteTmpDir() {
        return APP_DIR + "/tmp/sqlite";
    }

    public static File dataFile() {
        return new File(APP_DIR + "/data.db");
    }

    public static File logConfigFile() {
        return new File(configDir() + "/logback.xml");
    }

    public static File appConfigFile() {
        return new File(configDir() + "/app-config.json");
    }
}