package com.app.common.session;

import com.app.common.enums.Role;
import com.app.user.model.User;

public class Session {

    private Session() {
    }

    private static User currentUser;

    public static void setUser(User user) {
        currentUser = user;
    }

    public static User getUser() {
        return currentUser;
    }

    public static void clear() {
        currentUser = null;
    }

    public static boolean isAdmin() {
        return currentUser != null && currentUser.getRole() == Role.ADMIN;
    }

    public static Long getCurrentUserId() {
        if (currentUser == null) {
            return 0L;
        }
        return currentUser.getId();
    }
}