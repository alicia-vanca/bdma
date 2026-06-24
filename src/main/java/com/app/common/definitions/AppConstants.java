package com.app.common.definitions;

import java.time.format.DateTimeFormatter;

/**
 * Centralized constants for the application.
 */
public final class AppConstants {

    private AppConstants() {
    }

    // ────────────── Application ──────────────
    public static final int SINGLE_INSTANCE_PORT = 54321;
    public static final String VERSION_DEV = "dev";

    // ────────────── User Validation ──────────────
    public static final int USERNAME_MIN_LENGTH = 4;
    public static final String PASSWORD_POLICY_MESSAGE_KEY = "{user.account.password.policy}";

    // ────────────── Theme ──────────────
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String PATH_LIGHT = "/css/theme-light.css";
    public static final String PATH_DARK = "/css/theme-dark.css";
    public static final String ACTIVE_BUTTON = "active-button";

    // ────────────── Language ──────────────
    public static final String LANG_EN = "en";
    public static final String LANG_VI = "vi";

    // ────────────── App configs ──────────────
    public static final String SYNC_FOLDER_NAME = "sync_bdma.{21EC2020-3AEA-1069-A2DD-08002B30309D}";
    public static final String BACKUP_FOLDER_NAME = "backup_bdma.{21EC2020-3AEA-1069-A2DD-08002B30309D}";
    public static final String EXPORT_FOLDER_NAME = "export_bdma";
    public static final String KEY_SYNC_DIR = "dataSync.syncDir";
    public static final String KEY_BACKUP_DIR = "dataBackup.backupDir";
    public static final String KEY_EXPORT_DIR = "dataExport.exportDir";
    // Per-user key stored in user_config; remembers the last directory chosen in
    // the export picker.
    public static final String KEY_USER_LAST_EXPORT_DIR = "export.lastDir";
    // Per-user key stored in user_config; the configured default export directory
    // shown and set on the settings page.
    public static final String KEY_USER_EXPORT_DIR = "user.export.dir";
    // Per-user key stored in user_config; tracks whether the user wants to be
    // prompted for an export location on every export action.
    public static final String KEY_USER_ASK_EVERY_TIME_EXPORT = "user.export.askEveryTime";
    // Per-user key stored in user_config; remembers the last directory opened in
    // any file/folder picker.
    public static final String KEY_USER_LAST_OPEN_PATH = "picker.lastOpenPath";
    public static final String KEY_IS_AUTO_DELETE_AFTER_SYNC = "dataSync.isAutoDeleteAfterSync";
    public static final String KEY_IS_START_WITH_WINDOWS = "appConfig.isStartWithWindows";
    public static final String KEY_LANGUAGE = "appConfig.language";
    public static final String KEY_THEME = "appConfig.theme";
    public static final String KEY_LAST_CHECK_DATE = "appUpdate.lastCheckDate";
    public static final String KEY_SKIPPED_VERSION = "appUpdate.skippedVersion";
    public static final String KEY_LAST_RESTORE_PROGRESS = "last.restore.progress";
    public static final String DECRYPT_OUTPUT_DIR = "decrypt.output.lastDir";
    public static final String DECRYPT_INPUT_DIR = "decrypt.input.lastDir";

    public static final String STARTUP_REG_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    public static final String STARTUP_REG_VALUE = "BDMA";

    // ────────────── UI ──────────────
    public static final String FILTER_ALL_ROLES = "filter.allRoles";
    public static final String FILTER_ALL_STATUS = "filter.allStatus";

    // ────────────── File Status ──────────────
    public static final String FILE_STATUS_SYNCED = "SYNCED";
    public static final String FILE_STATUS_BACKEDUP = "BACKEDUP";

    // ────────────── Dashboard ──────────────
    public static final String KEY_DEVICE_CONNECTED = "dashboard.device.connected";
    public static final String KEY_DEVICE_SYNCED = "dashboard.device.synced";

    // ────────────── ADB Properties ──────────────
    public static final String ADB_PROP_SERIAL = "ro.serialno";
    public static final String ADB_PROP_PRODUCT_MODEL = "ro.product.model";
    public static final String ADB_PROP_PRODUCT_DEVICE = "ro.product.device";
    public static final String ADB_PROP_BOARD_PLATFORM = "ro.board.platform";

    // ────────────── User Management ──────────────
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final java.util.List<Integer> PAGE_SIZE_THRESHOLDS = java.util.List.of(10, 25, 50, 100);

    // ────────────── Data Sync ──────────────
    public static final java.util.List<String> MEDIA_TYPES = java.util.List.of("audio", "image", "video", "IMP", "SOS");
    public static final int MAX_RETRY = 3;
    public static final String TMP_EXTENSION = ".tmp";
    public static final String BODYCAM_ENCRYPTED_FILENAME_MARKER = "_enc";

    // ────────────── File List ──────────────
    public static final String DATE_PICKER_FORMAT = "dd/MM/yyyy";
    public static final String DATE_DISPLAY_FORMAT = "dd/MM/yyyy";

    // ────────────── Update ──────────────
    public static final String GITHUB_API = "https://api.github.com/repos/DucVietTech/bdma/releases";
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ────────────── Default password ──────────────
    @SuppressWarnings("secrets:S8215")
    public static final String DEFAULT_SYNC_USER_HASH = "$2a$10$xm8T0M6tezbn5RyrBL8FuOfmHtahqAtmUN.2XVRfllJ17211QBvgu";
}
