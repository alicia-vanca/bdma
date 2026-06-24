package com.app.common.modules.datasync.services;

import java.util.List;

import com.app.common.models.FileRecord;
import com.app.common.modules.device.services.DeviceListState;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.dtos.FileInfo;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.databackup.services.DataBackupService;
import com.app.common.repositories.FileRepository;
import com.app.common.repositories.ValidatedDeviceRepository;

@Service
public class DataSyncService {

    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final FileRepository fileRepo;
    private final DataBackupService dataBackupService;
    private final DeviceListState deviceListState;

    public DataSyncService(ValidatedDeviceRepository validatedDeviceRepository,
            FileRepository fileRepo,
            DataBackupService dataBackupService,
            DeviceListState deviceListState) {
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.fileRepo = fileRepo;
        this.dataBackupService = dataBackupService;
        this.deviceListState = deviceListState;
    }

    public Long resolveDeviceId(String cameraId) {
        ValidatedDevice validatedDevice = validatedDeviceRepository.findByCameraId(cameraId).orElse(null);
        return validatedDevice != null ? validatedDevice.getId() : null;
    }

    /**
     * Marks the end of a device-level sync without modifying last_seen_at.
     * Keeps persisted and open in-memory device rows consistent.
     *
     * @param cameraId stable camera identifier for the synced device
     */
    public void markDeviceSyncCompleted(String cameraId) {
        validatedDeviceRepository.markLastSync(cameraId);
        deviceListState.markDeviceLastSyncNow(cameraId);
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
