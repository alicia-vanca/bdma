package com.app.common.config;

import java.util.Arrays;

public final class AppContext {

    private AppContext() {
    }

    private static String deviceId;
    private static String version;
    // Raw 32-byte key derived from device ID via PBKDF2; used to open the encrypted
    // SQLite DB
    private static byte[] dbKey;

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

    public static byte[] getDbKey() {
        return dbKey == null ? null : Arrays.copyOf(dbKey, dbKey.length);
    }

    public static void setDbKey(byte[] dbKey) {
        AppContext.dbKey = dbKey == null ? null : Arrays.copyOf(dbKey, dbKey.length);
    }
}