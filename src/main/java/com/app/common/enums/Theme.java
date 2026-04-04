package com.app.common.enums;

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