package com.app.common.dtos;

import java.util.Set;

/**
 * Parses file names with the following format:
 * DSJ_{deviceName}_{userName}_{yyyyMMdd}_{HHmmss}.ext
 * <p>
 * Example:
 * DSJ_000003_000000_20260408_155023.jpg
 * → deviceName = "000003"
 * → userName = "000000"
 * → createDate = "2026-04-08 15:50:23"
 * → size = file size in bytes
 * → type = folder category from remote path
 */
public record FileInfo(
        String deviceName,
        String userName,
        String createDate, // "yyyy-MM-dd HH:mm:ss"
        long size,
        String type) {

    private static final String PREFIX = "DSJ_";
    private static final Set<String> VALID_EXTENSIONS = Set.of("mp4", "mp3", "jpg", "jpeg", "png");

    /**
     * Parses a FileInfo from a file name.
     * Returns null if the format is invalid.
     */
    public static FileInfo parse(String fileName) {
        return parse(fileName, 0, null);
    }

    /**
     * Parses a FileInfo from a file name with size.
     * Returns null if the format is invalid.
     */
    public static FileInfo parse(String fileName, long size) {
        return parse(fileName, size, null);
    }

    /**
     * Parses a FileInfo from a file name with size and type.
     * Returns null if the format is invalid.
     */
    public static FileInfo parse(String fileName, long size, String type) {
        if (fileName == null || fileName.isBlank())
            return null;

        try {
            // Validate and remove file extension
            if (!fileName.contains("."))
                return null;
            String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            if (!VALID_EXTENSIONS.contains(ext))
                return null;
            String base = fileName.substring(0, fileName.lastIndexOf('.'));

            // Validate prefix
            if (!base.startsWith(PREFIX))
                return null;

            // Remove prefix "DSJ_"
            String body = base.substring(PREFIX.length());

            // Split: deviceName _ userName _ yyyyMMdd _ HHmmss
            String[] parts = body.split("_");
            if (parts.length < 4)
                return null;

            String deviceName = parts[0];
            String userName = parts[1];
            String datePart = parts[2]; // yyyyMMdd
            String timePart = parts[3]; // HHmmss

            if (datePart.length() != 8 || timePart.length() != 6)
                return null;

            // Format to "yyyy-MM-dd HH:mm:ss"
            String createDate = datePart.substring(0, 4) + "-"
                    + datePart.substring(4, 6) + "-"
                    + datePart.substring(6, 8) + " "
                    + timePart.substring(0, 2) + ":"
                    + timePart.substring(2, 4) + ":"
                    + timePart.substring(4, 6);

            return new FileInfo(deviceName, userName, createDate, size, type);

        } catch (Exception e) {
            return null;
        }
    }
}
