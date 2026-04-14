package com.app.common.configs;

import lombok.Getter;

import java.util.Arrays;

public final class AppContext {

    private AppContext() {
    }

    @Getter
    private static String deviceId;
    @Getter
    private static String version;
    // Raw 32-byte key derived from device ID via PBKDF2
    // Used to open the encrypted SQLite DB
    private static byte[] dbKey;
    @Getter
    private static boolean dbEncryptionEnabled;

    public static void setDeviceId(String deviceId) {
        AppContext.deviceId = deviceId;
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

    public static void setDbEncryptionEnabled(boolean dbEncryptionEnabled) {
        AppContext.dbEncryptionEnabled = dbEncryptionEnabled;
    }
}