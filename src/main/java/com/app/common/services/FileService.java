package com.app.common.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.app.common.definitions.enums.Role;
import com.app.common.dtos.FileInfo;
import com.app.common.models.FileRecord;
import com.app.common.modules.i18n.I18n;
import com.app.common.repositories.UserRepository;
import com.app.common.utils.FileUtil;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.dtos.FileFilter;
import com.app.common.dtos.FileView;
import com.app.common.models.User;
import com.app.common.modules.session.Session;
import com.app.common.repositories.FileRepository;

import static com.app.common.definitions.AppConstants.DEFAULT_SYNC_USER_HASH;

@Service
public class FileService {


    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final FileRepository fileRepository;
    private final UserRepository userRepo;
    private final DeviceValidationService deviceValidationService;
    private final Session session;
    private final AppNoticeService appNoticeService;

    // Notifys UserManagementController of new auto-created users so it can refresh
    // the user list and show a notification.
    @Setter
    private Consumer<String> onUserAutoCreated;

    public FileService(FileRepository fileRepository, Session session, UserRepository userRepo,
            DeviceValidationService deviceValidationService, AppNoticeService appNoticeService) {
        this.fileRepository = fileRepository;
        this.session = session;
        this.userRepo = userRepo;
        this.deviceValidationService = deviceValidationService;
        this.appNoticeService = appNoticeService;
    }

    public List<FileView> query(FileFilter filter) {
        User currentUser = session.getUser();

        if (currentUser != null && !session.isAdmin()) {
            filter.setUserId(currentUser.getId());
        }

        return fileRepository.findByFilter(filter);
    }

    /**
     * Gets file size from disk (tries synced path first, then backup path).
     * Returns 0 if file not found.
     */
    private long getFileSizeFromDisk(String syncedPath, String backupPath) {
        try {
            Path path = Path.of(syncedPath);
            if (Files.exists(path)) {
                return Files.size(path);
            }

            path = Path.of(backupPath);
            if (Files.exists(path)) {
                return Files.size(path);
            }
        } catch (IOException e) {
            log.warn("Cannot read file size: {}", e.getMessage());
        }
        return 0;
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


    public Long resolveUserId(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        User existing = userRepo.findByUsername(username).orElse(null);
        if (existing != null) {
            return existing.getId();
        }
        if (!session.isAdmin()) {
            return null;
        }
        return createDefaultUser(username).getId();
    }

    private User createDefaultUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(DEFAULT_SYNC_USER_HASH);
        user.setRole(Role.USER);

        User saved = userRepo.save(user);
        log.info("Auto-created sync user '{}'", username);

        // Show notification on screen
        appNoticeService.showSuccess(I18n.get("user.auto.created", username));

        // Notify UserManagementController to refresh list if it's open
        if (onUserAutoCreated != null) {
            onUserAutoCreated.accept(username);
        }
        return saved;
    }

    /**
     * Batch resolve userIds from filenames.
     * Returns map: username -> userId
     */
    public Map<String, Long> batchResolveUsers(List<Path> files) {
        Map<String, Long> result = new HashMap<>();

        Set<String> usernames = files.stream()
                .map(p -> FileInfo.parse(p.getFileName().toString()))
                .filter(Objects::nonNull)
                .map(FileInfo::username)
                .collect(Collectors.toSet());

        for (String username : usernames) {
            Long userId = resolveUserId(username);
            if (userId != null) {
                result.put(username, userId);
            }
        }

        return result;
    }

    /**
     * Batch resolve deviceIds from filenames.
     * Returns map: cameraId -> deviceId
     */
    public Map<String, Long> batchResolveDevices(List<Path> files) {
        Map<String, Long> result = new HashMap<>();

        Set<String> cameraIds = files.stream()
                .map(p -> FileInfo.parse(p.getFileName().toString()))
                .filter(Objects::nonNull)
                .map(FileInfo::cameraId)
                .collect(Collectors.toSet());

        for (String cameraId : cameraIds) {
            Long deviceId = deviceValidationService.resolveDeviceId(cameraId);
            if (deviceId != null) {
                result.put(cameraId, deviceId);
            }
        }

        return result;
    }

    /**
     * Upsert a single file into DB using pre-resolved user/device maps.
     */
    public void upsertFileFromRestore(String relativePath,
                                      String dataRoot, String backupRoot,
                                      Map<String, Long> userMap, Map<String, Long> deviceMap) {

        String fileName = Path.of(relativePath).getFileName().toString();

        FileInfo info = FileInfo.parse(fileName);
        if (info == null) {
            log.warn("Cannot parse file name: {}", fileName);
            return;
        }

        Long userId = userMap.get(info.username());
        if (userId == null) {
            log.warn("Cannot resolve user for username: {}", info.username());
            return;
        }

        Long deviceId = deviceMap.get(info.cameraId());
        if (deviceId == null) {
            log.warn("Cannot resolve device for cameraId: {}", info.cameraId());
            return;
        }

        String newSyncedPath = Path.of(dataRoot, relativePath).toString();
        String newBackupPath = Path.of(backupRoot, relativePath).toString();
        long fileSize = getFileSizeFromDisk(newSyncedPath, newBackupPath);

        FileRecord fileRecord = new FileRecord();
        fileRecord.setUserId(userId);
        fileRecord.setDeviceId(deviceId);
        fileRecord.setCreateDate(info.createDate());
        fileRecord.setName(fileName);
        fileRecord.setSyncedPath(FileUtil.stripDriveLetter(newSyncedPath));
        fileRecord.setBackedUpPath(FileUtil.stripDriveLetter(newBackupPath));
        fileRecord.setFileSize(fileSize);
        fileRecord.setType(extractTypeFromPath(newSyncedPath));
        fileRecord.setStatus(AppConstants.FILE_STATUS_BACKEDUP);

        fileRepository.upsert(fileRecord);
    }
}
