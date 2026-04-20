package com.app.common.definitions.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

@Getter
public enum FileType {
    AUDIO("audio"),
    IMAGE("image"),
    VIDEO("video"),
    IMP("IMP"),
    SOS("SOS");

    private final String value;

    FileType(String value) {
        this.value = value;
    }

    public static List<String> toValues() {
        return Arrays.stream(values()).map(FileType::getValue).toList();
    }
}
