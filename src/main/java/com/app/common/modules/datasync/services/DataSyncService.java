package com.app.common.modules.datasync.services;

import java.util.List;
import java.util.function.Consumer;

import com.app.common.models.FileRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.Role;
import com.app.common.dtos.FileInfo;
import com.app.common.models.User;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.databackup.services.DataBackupService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.repositories.FileRepository;
import com.app.common.repositories.UserRepository;
import com.app.common.repositories.ValidatedDeviceRepository;
import com.app.common.services.AppNoticeService;

import static com.app.common.definitions.AppConstants.DEFAULT_SYNC_USER_HASH;

@Service
public class DataSyncService {
    private static final Logger log = LoggerFactory.getLogger(DataSyncService.class);

    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final FileRepository fileRepo;
    private final UserRepository userRepo;
    private final DataBackupService dataBackupService;
    private final Session session;
    private final AppNoticeService appNoticeService;

    private Consumer<String> onUserAutoCreated;

    public DataSyncService(ValidatedDeviceRepository validatedDeviceRepository,
            FileRepository fileRepo,
            UserRepository userRepo,
            DataBackupService dataBackupService,
            Session session,
            AppNoticeService appNoticeService) {
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.fileRepo = fileRepo;
        this.userRepo = userRepo;
        this.dataBackupService = dataBackupService;
        this.session = session;
        this.appNoticeService = appNoticeService;
    }

    public Long validateDevice(String hardwareId) {
        return validatedDeviceRepository.findByHardwareId(hardwareId)
                .map(ValidatedDevice::getId)
                .orElse(null);
    }

    public Long resolveDeviceId(String cameraId) {
        ValidatedDevice validatedDevice = validatedDeviceRepository.findByCameraId(cameraId).orElse(null);
        return validatedDevice != null ? validatedDevice.getId() : null;
    }

    public Long resolveUserId(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }

        String normalized = normalizeUsername(username);
        User existing = userRepo.findByUsername(normalized).orElse(null);
        if (existing != null) {
            return existing.getId();
        }
        if (!session.isAdmin()) {
            return null;
        }
        return createDefaultUser(normalized).getId();
    }

    // Notifys UserManagementController of new auto-created users so it can refresh
    // the user list and show a notification.
    public void setOnUserAutoCreated(Consumer<String> listener) {
        this.onUserAutoCreated = listener;
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

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase();
    }

    /**
     * Loads paths of files already synced or backed up for a device.
     * Used for in-memory deduplication.
     */
    public List<FileRecord> loadSyncedFiles(Long deviceId) {
        return fileRepo.loadSyncedFiles(deviceId);
    }

    /**
     * Saves a successfully synced file (status = SYNCED)
     * and immediately enqueues it for backup.
     */
    public void saveFile(Long userId, Long deviceId, String fileName, String localPath, FileInfo info) {
        fileRepo.insert(
                new FileRecord(null, userId, deviceId, info.createDate(), fileName, localPath, info.size(), info.type(),
                        AppConstants.FILE_STATUS_SYNCED, null, null, null));
        dataBackupService.enqueue(localPath);
    }
}
