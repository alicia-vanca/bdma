package com.app.common.definitions.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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

    public static final List<String> DCAM_VALUES = List.of("Audio", "Image", "Video", "IMP");

    private final String value;

    FileType(String value) {
        this.value = value;
    }

    public static String canonicalValue(String folderName) {
        if (folderName == null) {
            return null;
        }

        String normalized = folderName.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.value.toLowerCase(Locale.ROOT).equals(normalized))
                .map(FileType::getValue)
                .findFirst()
                .orElse(null);
    }
}
