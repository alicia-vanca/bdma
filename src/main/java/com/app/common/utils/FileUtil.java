package com.app.common.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileUtil {
    private static final Logger log = LoggerFactory.getLogger(FileUtil.class);

    private FileUtil() {
    }

    /**
     * Gets file size from disk (tries synced path first, then backup path).
     * Returns 0 if file not found.
     */
    public static long getFileSizeFromDisk(String syncedPath, String backupPath) {
        try {
            Path path = Path.of(syncedPath);
            if (Files.exists(path)) {
                return Files.size(path);
            }

            path = Path.of(backupPath);
            if (Files.exists(path)) {
                return Files.size(path);
            }
        } catch (IOException e) {
            log.warn("Cannot read file size: {}", e.getMessage());
        }
        return 0;
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
