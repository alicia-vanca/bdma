package com.app.common.enums;

public enum Language {
    VI, EN;

    @Override
    public String toString() {
        return switch (this) {
            case VI -> "Tiếng Việt";
            case EN -> "English";
        };
    }
}