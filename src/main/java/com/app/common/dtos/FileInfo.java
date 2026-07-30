package com.app.common.dtos;

import java.util.Set;

/**
 * Parses file names with the following formats:
 * DSJ_{cameraId}_{username}_{yyyyMMdd}_{HHmmss}.ext
 * DCAM_{cameraId}_{username}_{yyyyMMdd}_{HHmmss}.ext
 *
 * Example:
 * DSJ_000003_000000_20260408_155023.jpg
 * DCAM_000003_000000_20260408_155023.aac
 */
public record FileInfo(
        String cameraId,
        String username,
        String createDate, // "yyyy-MM-dd HH:mm:ss"
        long size,
        String type) {

    private static final Set<String> PREFIXES = Set.of("DSJ_", "DCAM_");
    private static final Set<String> VALID_EXTENSIONS = Set.of("mp4", "mp3", "aac", "wav", "jpg", "jpeg", "png");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("aac", "mp3", "wav", "mp4");

    public static FileInfo parse(String fileName) {
        return parse(fileName, 0, null);
    }

    public static FileInfo parse(String fileName, long size) {
        return parse(fileName, size, null);
    }

    public static FileInfo parse(String fileName, long size, String type) {
        if (fileName == null || fileName.isBlank())
            return null;

        try {
            if (!fileName.contains("."))
                return null;
            String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            if (!VALID_EXTENSIONS.contains(ext))
                return null;
            if (isAudioType(type) && !AUDIO_EXTENSIONS.contains(ext))
                return null;

            String base = fileName.substring(0, fileName.lastIndexOf('.'));
            String prefix = PREFIXES.stream()
                    .filter(base::startsWith)
                    .findFirst()
                    .orElse(null);
            if (prefix == null)
                return null;

            String body = base.substring(prefix.length());
            String[] parts = body.split("_");
            if (parts.length < 4)
                return null;

            String cameraId = parts[0];
            String username = parts[1];
            String datePart = parts[2];
            String timePart = parts[3];

            if (datePart.length() != 8 || timePart.length() != 6)
                return null;

            String createDate = datePart.substring(0, 4) + "-"
                    + datePart.substring(4, 6) + "-"
                    + datePart.substring(6, 8) + " "
                    + timePart.substring(0, 2) + ":"
                    + timePart.substring(2, 4) + ":"
                    + timePart.substring(4, 6);

            return new FileInfo(cameraId, username, createDate, size, type);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAudioType(String type) {
        return type != null && "audio".equalsIgnoreCase(type);
    }
}
