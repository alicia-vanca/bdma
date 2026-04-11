package com.app.sync.service;

import com.app.device.model.ValidatedDevice;
import com.app.device.repository.ValidatedDeviceRepository;
import com.app.sync.model.Files;
import com.app.sync.repository.FileRepository;
import com.app.user.model.User;
import com.app.user.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class SyncService {

    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final FileRepository fileRepo;
    private final UserRepository userRepo;
    private final BackupService backupService;

    public SyncService(ValidatedDeviceRepository validatedDeviceRepository,
                       FileRepository fileRepo,
                       UserRepository userRepo,
                       BackupService backupService) {
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.fileRepo = fileRepo;
        this.userRepo = userRepo;
        this.backupService = backupService;
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
        fileRepo.insert(new Files(null, userId, deviceId, createDate, name, path, fileSize, type, "SYNCED"));
        backupService.enqueue(path);
    }

    /**
     * Saves large files for later sync (status = PENDING_LARGE).
     * Not enqueued for backup since they are not downloaded yet.
     */
    public void saveLargeFile(Long userId, Long deviceId,
                              String name, String path,
                              long fileSize, String createDate, String type) {
        fileRepo.insert(new Files(null, userId, deviceId, createDate, name, path, fileSize, type, "PENDING_LARGE"));
    }

    /**
     * Saves a file that failed after retries (status = FAILED).
     */
    public void saveFailed(Long deviceId, String path) {
        fileRepo.insertFailed(deviceId, path);
    }
}
