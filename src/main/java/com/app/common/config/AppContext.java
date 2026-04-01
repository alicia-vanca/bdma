package com.app.common.config;

public class AppContext {

    private AppContext() {
    }

    private static String deviceId;
    private static String version;
    private static String appDir;

    public static String getDeviceId() {
        return deviceId;
    }

    public static void setDeviceId(String deviceId) {
        AppContext.deviceId = deviceId;
    }

    public static String getVersion() {
        return version;
    }

    public static void setVersion(String version) {
        AppContext.version = version;
    }

    public static String getAppDir() {
        return appDir;
    }

    public static void setAppDir(String appDir) {
        AppContext.appDir = appDir;
    }
}