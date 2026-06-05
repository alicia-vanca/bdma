package com.app.common.utils;

import java.io.File;
import java.nio.file.Path;

public class FileUtil {
    private FileUtil() {
    }

    public static String stripDriveLetter(String absolutePath) {
        Path path = Path.of(absolutePath);
        Path root = path.getRoot();
        return root != null ? root.relativize(path).toString() : absolutePath;
    }


    public static String extractDriveLetter(File file) {
        return file == null ? null : extractDriveLetter(file.toPath());
    }


    public static String extractDriveLetter(Path path) {
        Path root = path.getRoot();
        if (root == null) {
            return null;
        }
        return root.toString().replace("\\", "").stripTrailing();
    }
}
