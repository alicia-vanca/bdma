package com.app.common.config;

import java.io.File;

public final class AppPaths {

    private static final String APP_DIR = System.getProperty("user.home") + "/.helloworld-app";

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

    public static String tmpDir() {
        return APP_DIR + "/tmp";
    }

    public static File dataFile() {
        return new File(APP_DIR + "/data.db");
    }

    public static File logConfigFile() {
        return new File(configDir() + "/logback.xml");
    }
}