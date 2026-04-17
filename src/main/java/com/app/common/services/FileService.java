package com.app.common.services;

import com.app.common.dtos.FileFilter;
import com.app.common.dtos.FileView;
import com.app.common.models.User;
import com.app.common.modules.session.Session;
import com.app.common.repositories.FileRepository;
import org.springframework.stereotype.Service;

import java.util.List;

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

        if (!session.isAdmin()) {
            filter.setUserId(currentUser.getId());
        }

        return fileRepository.findByFilter(filter);
    }
}
