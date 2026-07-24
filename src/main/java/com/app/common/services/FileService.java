package com.app.common.services;

import java.nio.file.Path;
import java.util.List;

import com.app.common.models.FileRecord;
import com.app.common.utils.FileUtil;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.dtos.FileFilter;
import com.app.common.dtos.FileView;
import com.app.common.models.User;
import com.app.common.modules.session.Session;
import com.app.common.repositories.FileRepository;

@Service
public class FileService {

    private final FileRepository fileRepository;
    private final Session session;

    public FileService(FileRepository fileRepository, Session session) {
        this.fileRepository = fileRepository;
        this.session = session;
    }

    public List<FileView> query(FileFilter filter) {
        User currentUser = session.getUser();

        if (currentUser != null) {
            if (!session.isAdmin()) {
                filter.setUserId(currentUser.getId());
            }
            filter.setBookmarkUserId(currentUser.getId());
        }

        return fileRepository.findByFilter(filter);
    }

    private String extractTypeFromPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path path = Path.of(filePath);
        if (path.getParent() == null) {
            return null;
        }

        return path.getParent().getFileName().toString();
    }

    /**
     * Upserts file record from pre-parsed metadata. Does not re-parse filename.
     */
    public void upsertFileRecord(String syncedFilePath, String backupFilePath,
            Long userId, Long deviceId, String createDate, String fileName) {

        long fileSize = FileUtil.getFileSizeFromDisk(syncedFilePath, backupFilePath);

        FileRecord fileRecord = new FileRecord();
        fileRecord.setUserId(userId);
        fileRecord.setDeviceId(deviceId);
        fileRecord.setCreateDate(createDate);
        fileRecord.setName(fileName);
        fileRecord.setSyncedPath(FileUtil.stripDriveLetter(syncedFilePath));
        fileRecord.setBackedUpPath(FileUtil.stripDriveLetter(backupFilePath));
        fileRecord.setFileSize(fileSize);
        fileRecord.setType(extractTypeFromPath(syncedFilePath));
        fileRecord.setStatus(AppConstants.FILE_STATUS_BACKEDUP);

        fileRepository.upsert(fileRecord);
    }
}
