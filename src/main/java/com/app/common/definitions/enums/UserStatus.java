package com.app.common.definitions.enums;

import com.app.common.modules.i18n.I18n;

public enum UserStatus {
    ACTIVE("user.status.active"),
    INACTIVE("user.status.inactive");

    private final String messageKey;

    UserStatus(String messageKey) {
        this.messageKey = messageKey;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getLocalizedName() {
        return I18n.get(messageKey);
    }
}
