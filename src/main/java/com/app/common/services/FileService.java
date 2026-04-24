package com.app.common.services;

import java.nio.file.Path;
import java.util.List;

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

        if (currentUser != null && !session.isAdmin()) {
            filter.setUserId(currentUser.getId());
        }

        return fileRepository.findByFilter(filter);
    }

    /**
     * Updates synced_path and backed_up_path for files after restore operation.
     * Rebuilds full paths based on new data and backup root directories.
     */
    public void batchUpdatePaths(List<String> relativeNames, String newDataRoot, String newBackupRoot) {
        List<Object[]> batchArgs = relativeNames.stream()
                .map(rel -> {
                    String fileName = Path.of(rel).getFileName().toString();
                    String newSyncedPath = Path.of(newDataRoot, rel).toString();
                    String newBackupPath = Path.of(newBackupRoot, rel).toString();
                    return new Object[] { newSyncedPath, newBackupPath,
                            AppConstants.FILE_STATUS_BACKEDUP, fileName };
                })
                .toList();

        fileRepository.batchUpdatePaths(batchArgs);
    }
}
