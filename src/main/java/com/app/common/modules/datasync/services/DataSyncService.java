package com.app.common.modules.datasync.services;

import java.util.Set;

import org.springframework.stereotype.Service;

import com.app.common.models.File;
import com.app.common.models.User;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.databackup.services.DataBackupService;
import com.app.common.repositories.FileRepository;
import com.app.common.repositories.UserRepository;
import com.app.common.repositories.ValidatedDeviceRepository;

@Service
public class DataSyncService {

    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final FileRepository fileRepo;
    private final UserRepository userRepo;
    private final DataBackupService dataBackupService;

    public DataSyncService(ValidatedDeviceRepository validatedDeviceRepository,
            FileRepository fileRepo,
            UserRepository userRepo,
            DataBackupService dataBackupService) {
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.fileRepo = fileRepo;
        this.userRepo = userRepo;
        this.dataBackupService = dataBackupService;
    }

    public Long validateDevice(String hardwareId) {
        return validatedDeviceRepository.findByHardwareId(hardwareId)
                .map(ValidatedDevice::getId)
                .orElse(null);
    }

    public Long resolveDeviceId(String deviceName) {
        return validatedDeviceRepository.findDeviceIdByName(deviceName).orElse(null);
    }

    public Long resolveUserId(String username) {
        return userRepo.findByUsername(username)
                .map(User::getId)
                .orElse(null);
    }

    /**
     * Loads paths of files already synced or backed up for a device.
     * Used for in-memory deduplication.
     */
    public Set<String> loadSyncedPaths(Long deviceId) {
        return fileRepo.loadSyncedPaths(deviceId);
    }

    /**
     * Saves a successfully synced file (status = SYNCED)
     * and immediately enqueues it for backup.
     */
    public void saveFile(Long userId, Long deviceId,
            String name, String path,
            long fileSize, String createDate, String type) {
        fileRepo.insert(new File(null, userId, deviceId, createDate, name, path, fileSize, type, "SYNCED", null));
        dataBackupService.enqueue(path);
    }

    /**
     * Saves large files for later sync (status = PENDING_LARGE).
     * Not enqueued for backup since they are not downloaded yet.
     */
    public void saveLargeFile(Long userId, Long deviceId,
            String name, String path,
            long fileSize, String createDate, String type) {
        fileRepo.insert(new File(null, userId, deviceId, createDate, name, path, fileSize, type, "PENDING_LARGE", null));
    }

    /**
     * Saves a file that failed after retries (status = FAILED).
     */
    public void saveFailed(Long deviceId, String path) {
        fileRepo.insertFailed(deviceId, path);
    }
}
