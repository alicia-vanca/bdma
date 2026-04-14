package com.app.common.modules.session;

import com.app.common.definitions.enums.Role;
import com.app.common.models.User;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Session bean for storing the current user context.
 */
@Component
@Scope("singleton")
public class Session {

    private User currentUser;

    public void setUser(User user) {
        this.currentUser = user;
    }

    public User getUser() {
        return currentUser;
    }

    public void clear() {
        currentUser = null;
    }

    public boolean isAdmin() {
        return currentUser != null && currentUser.getRole() == Role.ADMIN;
    }

    public Long getCurrentUserId() {
        if (currentUser == null) {
            return 0L;
        }
        return currentUser.getId();
    }
}