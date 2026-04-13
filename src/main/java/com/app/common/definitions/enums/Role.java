package com.app.common.definitions.enums;

public enum Role {
    ADMIN,
    USER;

    @Override
    public String toString() {
        return switch (this) {
            case ADMIN -> "Administrator";
            case USER -> "User";
        };
    }
}
