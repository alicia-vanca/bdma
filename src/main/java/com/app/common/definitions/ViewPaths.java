package com.app.common.definitions;

// Centralize FXML paths to avoid duplicated string literals and navigation typos.
public final class ViewPaths {

    public static final String LOGIN = "/fxml/auth/login.fxml";
    public static final String ADMIN_LAYOUT = "/fxml/admin/admin_layout.fxml";
    public static final String ADMIN_DASHBOARD = "/fxml/admin/dashboard.fxml";
    public static final String ADMIN_SETTINGS_DIALOG = "/fxml/admin/admin-settings-dialog.fxml";
    public static final String USER_INFO = "/fxml/user/user-info.fxml";
    public static final String USER_LIST = "/fxml/admin/usermanagement/user-management.fxml";
    public static final String USER_FORM = "/fxml/admin/usermanagement/user-management-form.fxml";
    public static final String USER_ACCOUNT_DIALOG = USER_FORM;
    public static final String USER_SETTINGS_DIALOG = "/fxml/user/user-settings-dialog.fxml";
    public static final String PRE_LOGIN_SETTINGS_POPUP = "/fxml/common/prelogin-settings-popup.fxml";
    public static final String FILE_LIST_PANEL = "/fxml/admin/file-list-panel.fxml";

    private ViewPaths() {
    }
}
