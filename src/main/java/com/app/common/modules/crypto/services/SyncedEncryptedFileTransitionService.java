package com.app.common.modules.crypto.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.models.FileRecord;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.foldermanager.services.FolderSecurityService;
import com.app.common.repositories.FileRepository;

/**
 * Temporary transition service that converts already-synced local encrypted
 * media
 * into normal decrypted media. Remove after all legacy _enc records are
 * migrated.
 */
@Service
@Lazy
public class SyncedEncryptedFileTransitionService {

    private static final Logger log = LoggerFactory.getLogger(SyncedEncryptedFileTransitionService.class);
    private static final String DECRYPTED_TEMP_SUFFIX = ".transition-dec" + AppConstants.TMP_EXTENSION;

    private final FileRepository fileRepository;
    private final FolderManagerService folderManagerService;
    private final BodycamCryptoService bodycamCryptoService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SyncedEncryptedFileTransitionService(FileRepository fileRepository,
            FolderManagerService folderManagerService,
            BodycamCryptoService bodycamCryptoService) {
        this.fileRepository = fileRepository;
        this.folderManagerService = folderManagerService;
        this.bodycamCryptoService = bodycamCryptoService;
    }

    /**
     * Converts legacy synced _enc files to normal paths during startup transition.
     * Database metadata changes before obsolete encrypted files are deleted so an
     * interrupted transition keeps the normal file tracked.
     */
    public void migrate() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Synced encrypted transition already running, skip overlapping trigger.");
            return;
        }

        try {
            List<FileRecord> encryptedRecords = fileRepository.findSyncedEncryptedTransitionFiles();
            if (encryptedRecords.isEmpty()) {
                log.debug("Synced encrypted transition: no records found.");
                return;
            }

            log.info("Synced encrypted transition: found {} record(s).", encryptedRecords.size());
            for (FileRecord fileRecord : encryptedRecords) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Synced encrypted transition interrupted.");
                    return;
                }
                migrateRecord(fileRecord);
            }
        } finally {
            running.set(false);
        }
    }

    private void migrateRecord(FileRecord fileRecord) {
        if (fileRecord == null || fileRecord.getId() == null || fileRecord.getSyncedPath() == null) {
            return;
        }

        String encryptedSyncedPath = fileRecord.getSyncedPath();
        String normalSyncedPath = normalPath(encryptedSyncedPath);
        if (encryptedSyncedPath.equals(normalSyncedPath)) {
            return;
        }

        try {
            PathResolutionResult existingNormal = folderManagerService
                    .findAbsolutePathFromNonDriveLetterPath(normalSyncedPath, fileRecord.getFileSize());
            if (existingNormal.isFound() && Files.isRegularFile(existingNormal.getPath())) {
                finishRecoveredTransition(fileRecord, encryptedSyncedPath, normalSyncedPath, existingNormal.getPath());
                return;
            }

            Path encryptedPath = resolveExistingPath(encryptedSyncedPath, fileRecord.getFileSize());
            Path normalPath = resolveNormalPath(encryptedPath, normalSyncedPath);

            if (Files.isRegularFile(normalPath)) {
                finishRecoveredTransition(fileRecord, encryptedSyncedPath, normalSyncedPath, normalPath);
                return;
            }

            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            if (!folderManagerService.hasSufficientSpace(normalPath.getParent().toFile(), fileRecord.getFileSize())) {
                log.warn("Skip synced encrypted transition due to low disk space: {}", encryptedSyncedPath);
                return;
            }

            decryptAndMove(encryptedPath, normalPath);
            fileRepository.transitionEncryptedSyncedFile(
                    fileRecord.getId(),
                    normalName(fileRecord.getName()),
                    normalSyncedPath,
                    Files.size(normalPath));
            deleteLocalFiles(encryptedPath, fileRecord.getBackedUpPath());
            log.info("Converted synced encrypted file: {} -> {}", encryptedSyncedPath, normalSyncedPath);
        } catch (Exception e) {
            log.warn("Failed synced encrypted transition for: {}", encryptedSyncedPath, e);
        }
    }

    private void finishRecoveredTransition(FileRecord fileRecord, String encryptedSyncedPath,
            String normalSyncedPath, Path normalPath) throws IOException {
        fileRepository.transitionEncryptedSyncedFile(
                fileRecord.getId(),
                normalName(fileRecord.getName()),
                normalSyncedPath,
                Files.size(normalPath));
        PathResolutionResult encryptedResult = folderManagerService
                .findAbsolutePathFromNonDriveLetterPath(encryptedSyncedPath, fileRecord.getFileSize());
        deleteLocalFiles(encryptedResult.getPath(), fileRecord.getBackedUpPath(), fileRecord.getFileSize());
        log.info("Recovered synced encrypted transition from existing normal file: {} -> {}",
                encryptedSyncedPath, normalSyncedPath);
    }

    private Path resolveExistingPath(String nonDriveLetterPath, long expectedSize) throws IOException {
        PathResolutionResult result = folderManagerService.findAbsolutePathFromNonDriveLetterPath(nonDriveLetterPath,
                expectedSize);
        if (!result.isFound()) {
            throw new IOException("Encrypted synced file not found: " + nonDriveLetterPath);
        }
        return result.getPath();
    }

    private Path resolveNormalPath(Path encryptedPath, String normalSyncedPath) throws IOException {
        Path root = encryptedPath.getRoot();
        if (root == null) {
            throw new IOException("Encrypted path has no root: " + encryptedPath);
        }
        return root.resolve(normalSyncedPath);
    }

    private void decryptAndMove(Path encryptedPath, Path normalPath) throws IOException {
        Path parent = normalPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path decryptedTemp = Path.of(normalPath + DECRYPTED_TEMP_SUFFIX);
        Files.deleteIfExists(decryptedTemp);
        try {
            bodycamCryptoService.decryptMediaFile(encryptedPath, decryptedTemp);
            Files.move(decryptedTemp, normalPath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(decryptedTemp);
        }
    }

    private void deleteLocalFiles(Path encryptedPath, String backedUpPath) {
        deleteLocalFiles(encryptedPath, backedUpPath, fileSize(encryptedPath));
    }

    private void deleteLocalFiles(Path encryptedPath, String backedUpPath, long encryptedSize) {
        deleteIfExists(encryptedPath);
        if (backedUpPath == null || backedUpPath.isBlank()) {
            return;
        }

        try {
            Path backupPath = resolveExistingPath(backedUpPath, encryptedSize);
            deleteIfExists(FolderSecurityService.primaryChecksumPath(backupPath));
            deleteIfExists(FolderSecurityService.backupChecksumPath(backupPath));
            deleteIfExists(backupPath);
        } catch (Exception e) {
            log.warn("Failed to delete backup files for transition: {}", backedUpPath, e);
        }
    }

    private long fileSize(Path path) {
        try {
            return path != null && Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete transition file: {}", path, e);
        }
    }

    private String normalPath(String path) {
        int separatorIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String parent = separatorIndex >= 0 ? path.substring(0, separatorIndex + 1) : "";
        String fileName = separatorIndex >= 0 ? path.substring(separatorIndex + 1) : path;
        return parent + normalName(fileName);
    }

    private String normalName(String fileName) {
        if (fileName == null) {
            return null;
        }
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex <= 0) {
            return fileName;
        }

        String baseName = fileName.substring(0, extensionIndex);
        if (!baseName.endsWith(AppConstants.BODYCAM_ENCRYPTED_FILENAME_MARKER)) {
            return fileName;
        }
        return baseName.substring(0, baseName.length() - AppConstants.BODYCAM_ENCRYPTED_FILENAME_MARKER.length())
                + fileName.substring(extensionIndex);
    }
}
