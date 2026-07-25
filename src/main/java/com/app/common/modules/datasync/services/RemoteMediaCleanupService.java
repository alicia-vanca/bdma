package com.app.common.modules.datasync.services;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FileType;
import com.app.common.modules.device.services.AdbClient;
import com.app.common.services.MassStorageService;

/**
 * Performs best-effort cleanup of synchronized remote media files, checksum
 * sidecars, and empty date-pattern directories.
 */
@Service
public class RemoteMediaCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RemoteMediaCleanupService.class);

    private static final DateTimeFormatter STRICT_DATE_FORMATTER = DateTimeFormatter
            .ofPattern(AppConstants.REMOTE_DATE_FOLDER_FORMAT)
            .withResolverStyle(ResolverStyle.STRICT);

    private final AdbClient adbClient;
    private final MassStorageService massStorageService;

    public RemoteMediaCleanupService(@Lazy AdbClient adbClient, MassStorageService massStorageService) {
        this.adbClient = adbClient;
        this.massStorageService = massStorageService;
    }

    /**
     * Deletes a remote media file and its .md5 checksum sidecar.
     *
     * @param driveLetter Windows drive letter for mass-storage paths, or {@code null} for ADB paths
     */
    public void deleteRemoteFile(String hardwareId, String remotePath, String driveLetter) {
        if (remotePath == null) {
            return;
        }
        String sidecarPath = toMd5SidecarPath(remotePath);

        if (remotePath.startsWith(MassStorageService.MASS_STORAGE_PREFIX)) {
            if (driveLetter == null) {
                log.warn("[{}] Cannot delete mass-storage file because drive letter is missing: {}",
                        hardwareId, remotePath);
                return;
            }
            massStorageService.deleteFile(driveLetter, remotePath);
            if (sidecarPath != null) {
                massStorageService.deleteFile(driveLetter, sidecarPath);
            }
            return;
        }
        adbClient.deleteRemoteFile(hardwareId, remotePath);
        if (sidecarPath != null) {
            adbClient.deleteRemoteFile(hardwareId, sidecarPath);
        }
    }

    /**
     * Scans the configured media roots after synchronization and deletes empty
     * date-pattern directories.
     *
     * @param hardwareId device hardware ID
     * @param mediaRoots storage roots scanned during preparation
     */
    public void cleanupEmptyDateFolders(String hardwareId, List<String> mediaRoots, String driveLetter) {
        if (mediaRoots == null || mediaRoots.isEmpty()) {
            return;
        }

        for (String root : mediaRoots) {
            if (MassStorageService.MASS_STORAGE_PREFIX.equals(root)) {
                cleanupEmptyDateFoldersInMassStorage(hardwareId, driveLetter);
            } else {
                cleanupEmptyDateFoldersInAdb(hardwareId, root);
            }
        }
    }

    private void cleanupEmptyDateFoldersInMassStorage(String hardwareId, String driveLetter) {
        if (driveLetter == null) {
            log.warn("[{}] Cannot cleanup mass storage empty date folders because drive letter is missing",
                    hardwareId);
            return;
        }

        List<String> dateDirs = massStorageService.listDateDirectories(driveLetter, FileType.ALL_VALUES);
        for (String dir : dateDirs) {
            if (isValidDateFolder(dir)) {
                deleteMassStorageDateFolder(hardwareId, driveLetter, dir);
            }
        }
    }

    private void deleteMassStorageDateFolder(String hardwareId, String driveLetter, String dateFolder) {
        if (massStorageService.deleteDirectoryIfEmpty(driveLetter, dateFolder)) {
            log.debug("[{}] MassStorage deleted empty date-folder: {}", hardwareId, dateFolder);
        }
    }

    private void cleanupEmptyDateFoldersInAdb(String hardwareId, String root) {
        List<String> dateDirs = adbClient.findDateDirectories(hardwareId, root, FileType.ALL_VALUES);
        for (String dir : dateDirs) {
            if (isValidDateFolder(dir)) {
                adbClient.deleteRemoteDirectoryIfEmpty(hardwareId, dir);
            }
        }
    }

    /**
     * Validates whether a directory path represents a valid date-folder directly
     * under a media type root.
     */
    static boolean isValidDateFolder(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) {
            return false;
        }

        String normalized = directoryPath.replace('\\', '/');
        String[] segments = normalized.split("/");
        if (segments.length < 2) {
            return false;
        }

        String dateSegment = segments[segments.length - 1];
        String typeSegment = segments[segments.length - 2];

        return FileType.ALL_VALUES.stream().anyMatch(typeSegment::equalsIgnoreCase) && isValidStrictDate(dateSegment);
    }

    private static boolean isValidStrictDate(String dateString) {
        if (dateString == null || dateString.isBlank()) {
            return false;
        }
        try {
            STRICT_DATE_FORMATTER.parse(dateString);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static String toMd5SidecarPath(String remoteMediaPath) {
        if (remoteMediaPath == null || remoteMediaPath.isBlank()) {
            return null;
        }

        int lastSeparator = Math.max(remoteMediaPath.lastIndexOf('/'), remoteMediaPath.lastIndexOf('\\'));
        int lastDot = remoteMediaPath.lastIndexOf('.');
        if (lastDot <= lastSeparator + 1 || lastDot == remoteMediaPath.length() - 1) {
            return null;
        }
        return remoteMediaPath.substring(0, lastDot) + AppConstants.REMOTE_MD5_EXTENSION;
    }


}
