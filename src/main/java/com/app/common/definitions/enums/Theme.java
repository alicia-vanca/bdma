package com.app.common.definitions.enums;

public enum Theme {
    LIGHT, DARK;

    @Override
    public String toString() {
        return switch (this) {
            case LIGHT -> "Light";
            case DARK -> "Dark";
        };
    }
}