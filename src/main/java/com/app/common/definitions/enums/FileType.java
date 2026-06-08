package com.app.common.definitions.enums;

import java.util.Arrays;
import java.util.List;

import lombok.Getter;

@Getter
public enum FileType {
    AUDIO("audio"),
    IMAGE("image"),
    VIDEO("video"),
    IMP("IMP"),
    SOS("SOS");

    public static final List<String> ALL_VALUES = Arrays.stream(values())
            .map(FileType::getValue)
            .toList();

    private final String value;

    FileType(String value) {
        this.value = value;
    }
}
