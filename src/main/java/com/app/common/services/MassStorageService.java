package com.app.common.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MassStorageService {

    private static final Logger log = LoggerFactory.getLogger(MassStorageService.class);

    /**
     * Virtual path prefix used to distinguish mass storage paths from ADB paths.
     * DataSyncWorker checks this prefix to decide which pull method to use.
     */
    public static final String MASS_STORAGE_PREFIX = "/mass_storage/";

    /**
     * Relative path inside the SD card root to the bodycam cache directory.
     */
    private static final String BODYCAM_CACHE = "Android" + File.separator
            + "data" + File.separator
            + "com.bodycamera.nettysocket" + File.separator
            + "cache";

    /**
     * Lists all media files on the SD card, returning ADB-style virtual paths.
     * <p>
     * Scans {@code {driveLetter}\Android\data\com.bodycamera.nettysocket\cache\{type}\{date}\}
     * for each media type and converts the result to paths like
     * {@code /mass_storage/video/2026-04-21/DSJ_000003_000000_20260421_103000.mp4}.
     * </p>
     *
     * @param driveLetter Windows drive letter with trailing backslash (e.g. {@code E:\})
     * @param types       list of media type folder names (e.g. {@code ["video", "image", "audio", "IMP"]})
     * @return list of ADB-style virtual paths for all discovered files
     */
    public List<String> findFiles(String driveLetter, List<String> types) {
        List<String> result = new ArrayList<>();
        File cacheRoot = new File(driveLetter + BODYCAM_CACHE);

        if (!cacheRoot.exists()) {
            log.warn("MassStorage cache root not found: {}", cacheRoot.getAbsolutePath());
            return result;
        }

        for (String type : types) {
            collectType(cacheRoot, type, result);
        }

        log.info("MassStorage scan found {} files under {}", result.size(), cacheRoot.getAbsolutePath());
        return result;
    }

    private void collectType(File cacheRoot, String type, List<String> result) {
        File typeDir = new File(cacheRoot, type);
        if (!typeDir.exists() || !typeDir.isDirectory()) return;

        File[] dateDirs = typeDir.listFiles(File::isDirectory);
        if (dateDirs == null) return;

        for (File dateDir : dateDirs) {
            collectDateDir(type, dateDir, result);
        }
    }

    private void collectDateDir(String type, File dateDir, List<String> result) {
        File[] files = dateDir.listFiles(File::isFile);
        if (files == null) return;

        for (File file : files) {
            String virtualPath = toVirtualPath(type, dateDir.getName(), file.getName());
            result.add(virtualPath);
            log.trace("MassStorage found: {}", virtualPath);
        }
    }

    /**
     * Copies a file from the SD card drive to a local destination path.
     * Equivalent to {@code adbClient.pullFile()} for mass storage devices.
     *
     * @param driveLetter  Windows drive letter with trailing backslash (e.g. {@code E:\})
     * @param virtualPath  ADB-style virtual path (e.g. {@code /mass_storage/video/2026-04-21/file.mp4})
     * @param localPath    absolute local destination path
     * @return {@code true} if the copy succeeded
     */
    public boolean copyFile(String driveLetter, String virtualPath, String localPath) {
        File source = toWindowsFile(driveLetter, virtualPath);
        if (source == null) {
            log.warn("Cannot resolve Windows path for virtual path: {}", virtualPath);
            return false;
        }
        if (!source.exists()) {
            log.warn("Source file not found on drive: {}", source.getAbsolutePath());
            return false;
        }

        File dest = new File(localPath);
        try {
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.debug("MassStorage copied: {} → {}", source.getAbsolutePath(), localPath);
            return true;
        } catch (IOException e) {
            log.warn("MassStorage copy failed: {} → {}: {}", source.getAbsolutePath(), localPath, e.getMessage());
            return false;
        }
    }

    /**
     * Returns the size in bytes of a file on the SD card drive.
     * Equivalent to {@code adbClient.getRemoteSize()} for mass storage devices.
     *
     * @param driveLetter Windows drive letter with trailing backslash (e.g. {@code E:\})
     * @param virtualPath ADB-style virtual path
     * @return file size in bytes, or {@code -1} if the file cannot be found
     */
    public long getFileSize(String driveLetter, String virtualPath) {
        File file = toWindowsFile(driveLetter, virtualPath);
        if (file == null || !file.exists()) {
            return -1;
        }
        return file.length();
    }

    /**
     * Converts type/date/filename components to an ADB-style virtual path.
     *
     * <pre>
     * toVirtualPath("video", "2026-04-21", "file.mp4")
     *   → "/mass_storage/video/2026-04-21/file.mp4"
     * </pre>
     */
    private String toVirtualPath(String type, String dateDir, String fileName) {
        return MASS_STORAGE_PREFIX + type + "/" + dateDir + "/" + fileName;
    }

    /**
     * Converts an ADB-style virtual path back to a Windows {@link File}.
     *
     * <pre>
     * toWindowsFile("E:\", "/mass_storage/video/2026-04-21/file.mp4")
     *   → File("E:\Android\data\com.bodycamera.nettysocket\cache\video\2026-04-21\file.mp4")
     * </pre>
     *
     * @return the corresponding {@link File}, or {@code null} if the path is malformed
     */
    private File toWindowsFile(String driveLetter, String virtualPath) {
        if (!virtualPath.startsWith(MASS_STORAGE_PREFIX)) {
            return null;
        }

        // Strip prefix: /mass_storage/video/2026-04-21/file.mp4
        //             → video/2026-04-21/file.mp4
        String relative = virtualPath.substring(MASS_STORAGE_PREFIX.length());

        // Expected: type/dateDir/fileName
        String[] parts = relative.split("/");
        if (parts.length != 3) {
            log.warn("Unexpected virtual path format: {}", virtualPath);
            return null;
        }

        String type = parts[0];
        String dateDir = parts[1];
        String fileName = parts[2];

        return new File(
                new File(new File(new File(driveLetter + BODYCAM_CACHE, type), dateDir), fileName)
                        .getAbsolutePath()
        );
    }

    /**
     * Returns total capacity, free space, and usable space for the mass storage drive.
     * Windows reports these metrics from the drive root, so values include the whole SD card.
     *
     * @param driveLetter Windows drive letter with trailing backslash (e.g. {@code E:\})
     * @return storage metrics in bytes, or {@code null} if the drive is unavailable
     */
    public Map<String, Long> getStorageSize(String driveLetter) {
        File root = resolveDriveRoot(driveLetter);
        if (root == null || !root.exists()) {
            log.warn("MassStorage drive root not found: {}", driveLetter);
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("Used", root.getTotalSpace() - root.getUsableSpace());
        result.put("Size", root.getTotalSpace());
        return result;
    }

    private File resolveDriveRoot(String driveLetter) {
        if (driveLetter == null || driveLetter.isBlank()) {
            return null;
        }
        return new File(driveLetter.trim());
    }
}
