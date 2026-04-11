package com.app.sync.model;

/**
 * Parses file names with the following format:
 * DSJ_{deviceName}_{userName}_{yyyyMMdd}_{HHmmss}.ext
 * <p>
 * Example:
 * DSJ_000003_000000_20260408_155023.jpg
 * → deviceName = "000003"
 * → userName   = "000000"
 * → createDate = "2026-04-08 15:50:23"
 */
public record FileInfo(
        String deviceName,
        String userName,
        String createDate   // "yyyy-MM-dd HH:mm:ss"
) {

    private static final String PREFIX = "DSJ_";

    /**
     * Parses a FileInfo from a file name.
     * Returns null if the format is invalid.
     */
    public static FileInfo parse(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;

        try {
            // Remove file extension
            String base = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;

            // Validate prefix
            if (!base.startsWith(PREFIX)) return null;

            // Remove prefix "DSJ_"
            String body = base.substring(PREFIX.length());

            // Split: deviceName _ userName _ yyyyMMdd _ HHmmss
            String[] parts = body.split("_");
            if (parts.length < 4) return null;

            String deviceName = parts[0];
            String userName = parts[1];
            String datePart = parts[2]; // yyyyMMdd
            String timePart = parts[3]; // HHmmss

            if (datePart.length() != 8 || timePart.length() != 6) return null;

            // Format to "yyyy-MM-dd HH:mm:ss"
            String createDate = datePart.substring(0, 4) + "-"
                    + datePart.substring(4, 6) + "-"
                    + datePart.substring(6, 8) + " "
                    + timePart.substring(0, 2) + ":"
                    + timePart.substring(2, 4) + ":"
                    + timePart.substring(4, 6);

            return new FileInfo(deviceName, userName, createDate);

        } catch (Exception e) {
            return null;
        }
    }
}
