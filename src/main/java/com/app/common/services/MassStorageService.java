package com.app.common.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.definitions.enums.FileType;
import com.app.common.modules.device.configs.DeviceMediaLayout;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
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

    private static final String DCAM_VIRTUAL_SOURCE = "dcam/";

    private final DeviceMediaLayout deviceMediaLayout;

    public MassStorageService(DeviceMediaLayout deviceMediaLayout) {
        this.deviceMediaLayout = deviceMediaLayout;
    }

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
        File cacheRoot = new File(driveLetter + deviceMediaLayout.legacyMassStorageMediaPath());
        File dcamRoot = new File(driveLetter + deviceMediaLayout.dcamMassStorageMediaPath());

        for (String type : types) {
            collectType(cacheRoot, type, "", result);
        }
        for (String type : FileType.DCAM_VALUES) {
            collectType(dcamRoot, type, DCAM_VIRTUAL_SOURCE, result);
        }

        log.info("MassStorage scan found {} files", result.size());
        return result;
    }

    private void collectType(File mediaRoot, String type, String virtualSource, List<String> result) {
        File typeDir = new File(mediaRoot, type);
        if (!typeDir.exists() || !typeDir.isDirectory()) return;

        File[] dateDirs = typeDir.listFiles(File::isDirectory);
        if (dateDirs == null) return;

        for (File dateDir : dateDirs) {
            collectDateDir(virtualSource, type, dateDir, result);
        }
    }

    private void collectDateDir(String virtualSource, String type, File dateDir, List<String> result) {
        File[] files = dateDir.listFiles(File::isFile);
        if (files == null) return;

        for (File file : files) {
            String virtualPath = toVirtualPath(virtualSource, type, dateDir.getName(), file.getName());
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

    public String readTextFile(String driveLetter, String virtualPath) {
        File file = toWindowsFile(driveLetter, virtualPath);
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            return Files.readString(file.toPath(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            log.warn("MassStorage text read failed: {}: {}", file.getAbsolutePath(), e.getMessage());
            return "";
        }
    }

    public String calculateMd5(String driveLetter, String virtualPath) {
        File file = toWindowsFile(driveLetter, virtualPath);
        if (file == null || !file.isFile()) {
            return "";
        }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            log.warn("MassStorage MD5 calculation failed: {}: {}", file.getAbsolutePath(), e.getMessage());
            return "";
        }
    }

    /**
     * Converts type/date/filename components to an ADB-style virtual path.
     *
     * <pre>
     * toVirtualPath("video", "2026-04-21", "file.mp4")
     *   → "/mass_storage/video/2026-04-21/file.mp4"
     * </pre>
     */
    private String toVirtualPath(String virtualSource, String type, String dateDir, String fileName) {
        return MASS_STORAGE_PREFIX + virtualSource + type + "/" + dateDir + "/" + fileName;
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

        String[] parts = relative.split("/");
        String mediaRoot;
        int offset;
        if (parts.length == 4 && "dcam".equals(parts[0])) {
            mediaRoot = deviceMediaLayout.dcamMassStorageMediaPath();
            offset = 1;
        } else if (parts.length == 3) {
            mediaRoot = deviceMediaLayout.legacyMassStorageMediaPath();
            offset = 0;
        } else {
            log.warn("Unexpected virtual path format: {}", virtualPath);
            return null;
        }

        return new File(new File(new File(driveLetter + mediaRoot, parts[offset]), parts[offset + 1]),
                parts[offset + 2]);
    }

    /**
     * Deletes a file from the mass-storage drive using the same virtual path format.
     *
     * @param driveLetter Windows drive letter with trailing backslash (e.g. {@code E:\})
     * @param virtualPath ADB-style virtual path (e.g. {@code /mass_storage/video/2026-04-21/file.mp4})
     * @return {@code true} if the file was deleted or did not exist
     */
    public boolean deleteFile(String driveLetter, String virtualPath) {
        File source = toWindowsFile(driveLetter, virtualPath);
        if (source == null) {
            log.warn("Cannot resolve Windows path for mass-storage delete: {}", virtualPath);
            return false;
        }
        if (!source.exists()) {
            log.debug("MassStorage delete skipped because file does not exist: {}", source.getAbsolutePath());
            return true;
        }

        try {
            Files.delete(source.toPath());
            log.debug("MassStorage deleted: {}", source.getAbsolutePath());
            return true;
        } catch (IOException e) {
            log.warn("MassStorage delete failed: {}: {}", source.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    /**
     * Lists all date-pattern subdirectories under each media type folder,
     * returning ADB-style virtual directory paths.
     * <p>
     * Scans {@code {driveLetter}\Android\...\cache\{type}\} and returns paths like
     * {@code /mass_storage/video/2026-04-21}.
     *
     * @param driveLetter Windows drive letter with trailing backslash (e.g. {@code E:\})
     * @param types       list of media type folder names
     * @return list of virtual directory paths for all discovered date directories
     */
    public List<String> listDateDirectories(String driveLetter, List<String> types) {
        List<String> result = new ArrayList<>();
        File cacheRoot = new File(driveLetter + deviceMediaLayout.legacyMassStorageMediaPath());
        collectDateDirectories(cacheRoot, types, "", result);
        collectDateDirectories(new File(driveLetter + deviceMediaLayout.dcamMassStorageMediaPath()),
                FileType.DCAM_VALUES,
                DCAM_VIRTUAL_SOURCE, result);
        return result;
    }

    private void collectDateDirectories(File mediaRoot, List<String> types, String virtualSource,
            List<String> result) {
        for (String type : types) {
            File typeDir = new File(mediaRoot, type);
            if (!typeDir.exists() || !typeDir.isDirectory()) {
                continue;
            }

            File[] dateDirs = typeDir.listFiles(File::isDirectory);
            if (dateDirs == null) {
                continue;
            }

            for (File dateDir : dateDirs) {
                result.add(MASS_STORAGE_PREFIX + virtualSource + type + "/" + dateDir.getName());
            }
        }
    }

    /**
     * Lists file names inside the given virtual directory path.
     * <p>
     * Given {@code /mass_storage/video/2026-04-21}, returns file names (not full paths)
     * found in the corresponding Windows directory.
     *
     * @param driveLetter   Windows drive letter with trailing backslash (e.g. {@code E:\})
     * @param virtualDirPath virtual directory path (e.g. {@code /mass_storage/video/2026-04-21})
     * @return list of file names, or empty list if the directory does not exist
     */
    public List<String> listDirectoryFiles(String driveLetter, String virtualDirPath) {
        if (!virtualDirPath.startsWith(MASS_STORAGE_PREFIX)) {
            return List.of();
        }

        File dir = toWindowsDirectory(driveLetter, virtualDirPath);
        if (dir == null) {
            log.warn("Unexpected virtual directory path format: {}", virtualDirPath);
            return List.of();
        }
        if (!dir.exists() || !dir.isDirectory()) {
            return List.of();
        }

        String[] files = dir.list();
        if (files == null) {
            return List.of();
        }
        return List.of(files);
    }

    /**
     * Attempts to delete an empty date directory identified by its virtual path.
     * <p>
     * Converts {@code /mass_storage/video/2026-04-21} to the corresponding Windows
     * path and deletes it only when it is empty.
     *
     * @param driveLetter   Windows drive letter with trailing backslash (e.g. {@code E:\})
     * @param virtualDirPath virtual directory path to delete
     * @return {@code true} if the directory was deleted
     */
    public boolean deleteDirectoryIfEmpty(String driveLetter, String virtualDirPath) {
        if (!virtualDirPath.startsWith(MASS_STORAGE_PREFIX)) {
            return false;
        }

        File dir = toWindowsDirectory(driveLetter, virtualDirPath);
        if (dir == null) {
            return false;
        }

        if (!dir.exists()) {
            return false;
        }

        if (!dir.isDirectory()) {
            return false;
        }

        String[] contents = dir.list();
        if (contents == null) {
            return false;
        }
        if (contents.length > 0) {
            return false;
        }

        try {
            Files.delete(dir.toPath());
            log.debug("MassStorage deleted empty directory: {}", dir.getAbsolutePath());
            return true;
        } catch (IOException e) {
            log.warn("MassStorage failed to delete directory {}: {}",
                    dir.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    private File toWindowsDirectory(String driveLetter, String virtualDirPath) {
        if (!virtualDirPath.startsWith(MASS_STORAGE_PREFIX)) {
            return null;
        }

        String[] parts = virtualDirPath.substring(MASS_STORAGE_PREFIX.length()).split("/");
        if (parts.length == 3 && "dcam".equals(parts[0])) {
            return new File(new File(driveLetter + deviceMediaLayout.dcamMassStorageMediaPath(), parts[1]), parts[2]);
        }
        if (parts.length == 2) {
            return new File(new File(driveLetter + deviceMediaLayout.legacyMassStorageMediaPath(), parts[0]), parts[1]);
        }
        return null;
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
