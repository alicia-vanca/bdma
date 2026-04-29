package com.app.common.definitions;

/**
 * Centralized constants for the application.
 */
public final class AppConstants {

    private AppConstants() {
    }

    // ────────────── Application ──────────────
    public static final int SINGLE_INSTANCE_PORT = 54321;

    // ────────────── User Validation ──────────────
    public static final int USERNAME_MIN_LENGTH = 4;
    public static final int USERNAME_MAX_LENGTH = 20;
    public static final String USERNAME_REGEX = "^[a-z][a-z0-9_]{3,19}$";
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
    public static final String DATA_FOLDER_NAME = "data_bdma";
    public static final String BACKUP_FOLDER_NAME = "backup_bdma";
    public static final String KEY_DATA_DIR = "dataSync.dataDir";
    public static final String KEY_BACKUP_DIR = "dataBackup.backupDir";
    public static final String KEY_IS_AUTO_DELETE_AFTER_SYNC = "dataSync.isAutoDeleteAfterSync";
    public static final String KEY_IS_START_WITH_WINDOWS = "appConfig.isStartWithWindows";
    public static final String KEY_LANGUAGE = "appConfig.language";
    public static final String KEY_THEME = "appConfig.theme";
    public static final String KEY_LAST_CHECK_DATE = "appUpdate.lastCheckDate";
    public static final String KEY_SKIPPED_VERSION = "appUpdate.skippedVersion";

    public static final String STARTUP_REG_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    public static final String STARTUP_REG_VALUE = "BDMA";

    public static final String SYNC_TEMP_FOLDER_NAME = ".sync_tmp";
    public static final String BACKUP_TEMP_FOLDER_NAME = ".backup_tmp";

    // ────────────── UI ──────────────
    public static final String MESSAGE_ERROR = "message-error";
    public static final String MESSAGE_SUCCESS = "message-success";
    public static final String COMMON_ALL = "common.all";
    public static final String FILTER_ALL_ROLES = "filter.allRoles";
    public static final String FILTER_ALL_STATUS = "filter.allStatus";
    public static final int PAGE_SIZE = 10;

    // ────────────── File Status ──────────────
    public static final String FILE_STATUS_SYNCED = "SYNCED";
    public static final String FILE_STATUS_BACKEDUP = "BACKEDUP";
    public static final String FILE_STATUS_FAILED = "FAILED";

    // ────────────── Dashboard ──────────────
    public static final String KEY_DEVICE_CONNECTED = "dashboard.device.connected";
    public static final String KEY_DEVICE_SYNCED = "dashboard.device.synced";

    // ────────────── User Management ──────────────
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final java.util.List<Integer> PAGE_SIZE_THRESHOLDS = java.util.List.of(10, 25, 50, 100);

    // ────────────── Data Sync ──────────────
    public static final java.util.List<String> MEDIA_TYPES = java.util.List.of("audio", "image", "video", "IMP", "SOS");
    public static final int MAX_RETRY = 3;

    // ────────────── File List ──────────────
    public static final String DATE_PICKER_FORMAT = "dd/MM/yyyy";
    public static final String DATE_DISPLAY_FORMAT = "dd/MM/yyyy HH:mm:ss";

    // ────────────── Update ──────────────
    public static final String GITHUB_API = "https://api.github.com/repos/DucVietTech/bdma/releases";
    public static final String DATE_FORMAT = "yyyy-MM-dd";

    // ────────────── Loggly ──────────────
    public static final int BATCH_SIZE = 50;
    public static final String SYNC_FILENAME = "loggly-sync.json";
    public static final String FILES = "files";
}
