package com.app.common.config;

import com.app.common.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.mc.SQLiteMCWxAES256Config;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;

public final class AppRuntimeInitializer {

    private static final Logger log = LoggerFactory.getLogger(AppRuntimeInitializer.class);
    private static final byte[] APP_DB_KEY_CONTEXT = "bdma-db-key-v1".getBytes(StandardCharsets.UTF_8);
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final int DB_KEY_ITERATIONS = 65536;
    private static final int DB_KEY_LENGTH_BITS = 256;
    private static final String DB_ENCRYPTION_ENABLED_PROPERTY = "app.db.encryption.enabled";
    private static final String DB_ENCRYPTION_ENABLED_ENV = "APP_DB_ENCRYPTION_ENABLED";

    private AppRuntimeInitializer() {
    }

    public static void initialize() {
        ensureBaseDirectories();
        // App context (device ID) must be initialized before key derivation
        initializeAppContext();
        forceLoadSqliteDriver();
        ensureDatabaseFile();
        boolean dbEncryptionEnabled = resolveDbEncryptionEnabled();
        byte[] dbKey = deriveDbKey();
        AppContext.setDbEncryptionEnabled(dbEncryptionEnabled);
        AppContext.setDbKey(dbEncryptionEnabled ? dbKey : null);
        if (dbEncryptionEnabled) {
            migrateToEncryptedIfNeeded(dbKey);
        } else {
            migrateToPlaintextIfNeeded(dbKey);
        }
        configureSqlite();
        log.info("Database encryption is {}", dbEncryptionEnabled ? "enabled" : "disabled");
    }

    private static void ensureBaseDirectories() {
        ensureDir(AppPaths.appDir());
        ensureDir(AppPaths.configDir());
        ensureDir(AppPaths.appTmpDir());
        ensureDir(AppPaths.adbTmpDir());
        ensureDir(AppPaths.sqliteTmpDir());
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
        } catch (IOException e) {
            log.error("Failed to create DB file: {}", dbFile.getAbsolutePath(), e);
        }
    }

    private static void configureSqlite() {
        System.setProperty("org.sqlite.tmpdir", AppPaths.sqliteTmpDir());
    }

    private static void forceLoadSqliteDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new AppException("Failed to load SQLite driver", e);
        }
    }

    private static void initializeAppContext() {
        DeviceIdManager deviceIdManager = new DeviceIdManager();
        AppContext.setDeviceId(deviceIdManager.getDeviceId());

        String version = AppRuntimeInitializer.class.getPackage().getImplementationVersion();
        if (version == null) {
            version = System.getProperty("app.version", "dev");
        }
        AppContext.setVersion(version);

        // Populate MDC as soon as runtime context is available so startup logs on
        // the launcher thread carry device and version information.
        LogContext.init();

        log.info("Machine-based device ID resolved");
        log.info("App started - version: {}", AppContext.getVersion());
    }

    // Derive a 32-byte database key from the persisted device ID. The device ID
    // acts as a stable per-install salt so the database remains recoverable as
    // long as config/device.id is preserved.
    private static byte[] deriveDbKey() {
        char[] deviceIdChars = AppContext.getDeviceId().toCharArray();
        byte[] deviceIdSalt = AppContext.getDeviceId().getBytes(StandardCharsets.UTF_8);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(deviceIdChars, deriveDbKeySalt(deviceIdSalt), DB_KEY_ITERATIONS,
                    DB_KEY_LENGTH_BITS);
            byte[] key = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return key;
        } catch (GeneralSecurityException e) {
            throw new AppException("Failed to derive database key", e);
        } finally {
            Arrays.fill(deviceIdChars, '\0');
            Arrays.fill(deviceIdSalt, (byte) 0);
        }
    }

    // Keep the salt deterministic per installation without storing an extra
    // file by hashing the app context label together with the persisted device
    // ID bytes.
    private static byte[] deriveDbKeySalt(byte[] deviceIdBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(APP_DB_KEY_CONTEXT);
            return digest.digest(deviceIdBytes);
        } catch (GeneralSecurityException e) {
            throw new AppException("Failed to derive database key salt", e);
        }
    }

    // Convert bytes to lowercase hex, matching SQLite3MultipleCiphers' expected
    // raw-key format.
    static String toHexString(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(HEX_DIGITS[(b >>> 4) & 0x0F]);
            hex.append(HEX_DIGITS[b & 0x0F]);
        }
        return hex.toString();
    }

    static String toRawKey(byte[] bytes) {
        return "raw:" + toHexString(bytes);
    }

    // Build the PRAGMA statement from a validated raw key because SQLite PRAGMA
    // rekey cannot be parameterized with PreparedStatement.
    private static String buildRekeyPragma(byte[] keyBytes) {
        String hexKey = toHexString(keyBytes);
        if (!hexKey.matches("[0-9a-f]+")) {
            throw new AppException("Derived database key contains invalid characters");
        }
        return "PRAGMA rekey = 'raw:" + hexKey + "'";
    }

    // Returns true only if the file exists, has at least 16 bytes, and its
    // header matches the plain SQLite magic string — meaning it is not yet
    // encrypted.
    private static boolean isPlainSqliteFile(File dbFile) {
        if (!dbFile.exists() || dbFile.length() < 16) {
            return false;
        }
        byte[] magic = "SQLite format 3\000".getBytes(StandardCharsets.US_ASCII);
        byte[] header = new byte[16];
        try (FileInputStream fis = new FileInputStream(dbFile)) {
            return fis.read(header) == 16 && Arrays.equals(header, magic);
        } catch (IOException e) {
            log.warn("Could not read database header for encryption check: {}", e.getMessage());
            return false;
        }
    }

    private static boolean isEncryptedSqliteFile(File dbFile) {
        return dbFile.exists() && dbFile.length() >= 16 && !isPlainSqliteFile(dbFile);
    }

    // Encrypt plain-text databases in place using the derived key so subsequent
    // connections can open them with the configured cipher settings.
    private static void migrateToEncryptedIfNeeded(byte[] dbKey) {
        File dbFile = AppPaths.dataFile();
        if (!isPlainSqliteFile(dbFile)) {
            return;
        }

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        // Open the plain database using AES-256 cipher config but without a key,
        // then apply PRAGMA rekey to encrypt it with the derived raw key.
        Properties migrationProps = SQLiteMCWxAES256Config.getDefault().build().toProperties();
        try (Connection conn = DriverManager.getConnection(url, migrationProps);
             Statement stmt = conn.createStatement()) {
            stmt.execute(buildRekeyPragma(dbKey));
            log.info("Existing database encrypted with AES-256");
        } catch (SQLException e) {
            throw new AppException("Failed to encrypt database " + dbFile.getAbsolutePath(), e);
        }
    }

    private static void migrateToPlaintextIfNeeded(byte[] dbKey) {
        File dbFile = AppPaths.dataFile();
        if (!isEncryptedSqliteFile(dbFile)) {
            return;
        }

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Properties migrationProps = SQLiteMCWxAES256Config.getDefault()
                .withKey(toRawKey(dbKey))
                .build()
                .toProperties();
        try (Connection conn = DriverManager.getConnection(url, migrationProps);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA rekey = ''");
            log.info("Existing database decrypted to plain-text");
        } catch (SQLException e) {
            throw new AppException("Failed to decrypt database " + dbFile.getAbsolutePath(), e);
        }
    }

    private static boolean resolveDbEncryptionEnabled() {
        String value = System.getProperty(DB_ENCRYPTION_ENABLED_PROPERTY);
        if (value != null && !value.isBlank()) {
            return parseDbEncryptionEnabled(value, "system property");
        }

        value = System.getenv(DB_ENCRYPTION_ENABLED_ENV);
        if (value != null && !value.isBlank()) {
            return parseDbEncryptionEnabled(value, "environment variable");
        }

        Properties properties = loadApplicationProperties();
        value = properties.getProperty(DB_ENCRYPTION_ENABLED_PROPERTY);
        if (value != null && !value.isBlank()) {
            return parseDbEncryptionEnabled(value, "application.properties");
        }

        return true;
    }

    private static Properties loadApplicationProperties() {
        Properties properties = new Properties();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = AppRuntimeInitializer.class.getClassLoader();
        }

        try (InputStream inputStream = classLoader.getResourceAsStream("application.properties")) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException e) {
            log.warn("Could not read application.properties: {}", e.getMessage());
        }
        return properties;
    }

    private static boolean parseDbEncryptionEnabled(String value, String source) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }

        log.warn("Invalid {} value '{}' for {}. Falling back to enabled.", source, value,
                DB_ENCRYPTION_ENABLED_PROPERTY);
        return true;
    }
}