package com.app.common.definitions.enums;

import com.app.common.modules.i18n.I18n;

public enum Role {
    ADMIN("user.role.admin"),
    USER("user.role.user"),
    DEV("user.role.dev");

    private final String messageKey;

    Role(String messageKey) {
        this.messageKey = messageKey;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getLocalizedName() {
        return I18n.get(messageKey);
    }

    @Override
    public String toString() {
        return switch (this) {
            case ADMIN -> "Administrator";
            case USER -> "User";
            case DEV -> "Developer";
        };
    }

    public static Role fromLocalizedName(String name) {
        for (Role r : values()) {
            if (r.getLocalizedName().equals(name)) return r;
        }
        return null;
    }
}
