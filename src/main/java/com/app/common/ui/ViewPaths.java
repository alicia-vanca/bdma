package com.app.common.ui;

// Centralize FXML paths to avoid duplicated string literals and navigation typos.
public final class ViewPaths {

    public static final String LOGIN = "/fxml/auth/login.fxml";
    public static final String ADMIN_LAYOUT = "/fxml/admin/admin_layout.fxml";
    public static final String ADMIN_DASHBOARD = "/fxml/admin/dashboard.fxml";
    public static final String STORAGE_SETTING = "/fxml/setting/storage-setting.fxml";
    public static final String DATA_BACKUP_SETTING = "/fxml/setting/data-backup-setting.fxml";
    public static final String USER_LIST = "/fxml/user/user.fxml";
    public static final String USER_INFO = "/fxml/user/user-info.fxml";
    public static final String USER_FORM = "/fxml/user/user-form.fxml";
    public static final String USER_ACCOUNT_DIALOG = USER_FORM;
    public static final String SETTINGS_POPUP = "/fxml/common/settings-popup.fxml";

    private ViewPaths() {
    }
}