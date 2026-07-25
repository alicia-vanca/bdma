package com.app.admin.devicemanagement.services;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.app.common.models.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.modules.device.dtos.DeviceSummary;
import com.app.common.exceptions.ValidationException;
import com.app.admin.devicemanagement.dtos.DeviceManagementRow;
import com.app.common.models.DeviceActivationHistory;
import com.app.common.models.ValidatedDevice;
import com.app.common.modules.databaserecovery.services.DatabaseRecoveryService;
import com.app.common.modules.session.Session;
import com.app.common.repositories.DeviceActivationHistoryRepository;
import com.app.common.repositories.UserRepository;
import com.app.common.repositories.ValidatedDeviceRepository;
import com.app.common.modules.device.services.DeviceListState;

@Service
@Lazy
public class DeviceManagementService {

    private static final Logger log = LoggerFactory.getLogger(DeviceManagementService.class);

    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final DeviceActivationHistoryRepository activationHistoryRepository;
    private final UserRepository userRepository;
    private final DeviceListState deviceListState;
    private final Session session;
    private final DatabaseRecoveryService databaseRecoveryService;

    public DeviceManagementService(ValidatedDeviceRepository validatedDeviceRepository,
            DeviceActivationHistoryRepository activationHistoryRepository,
            UserRepository userRepository,
            DeviceListState deviceListState,
            Session session,
            DatabaseRecoveryService databaseRecoveryService) {
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.activationHistoryRepository = activationHistoryRepository;
        this.userRepository = userRepository;
        this.deviceListState = deviceListState;
        this.session = session;
        this.databaseRecoveryService = databaseRecoveryService;
    }

    /**
     * Builds the admin device table from saved records and current transient
     * validation results without changing persistent device IDs.
     *
     * @return saved and transient devices sorted by display name
     */
    public List<DeviceManagementRow> findAllForAdmin() {
        List<DeviceSummary> deviceListSnapshot = deviceListState.snapshotDeviceItems();

        Map<String, DeviceSummary> summariesByCameraId = deviceListSnapshot.stream()
                .filter(summary -> summary.getCameraId() != null)
                .collect(Collectors.toMap(DeviceSummary::getCameraId, Function.identity(),
                        DeviceManagementService::firstNonNullSummary));

        List<DeviceManagementRow> savedRows = validatedDeviceRepository.findAll().stream()
                .map(device -> DeviceManagementRow.fromSavedDevice(device,
                        summariesByCameraId.get(device.getCameraId())))
                .toList();

        var savedCameraIds = savedRows.stream()
                .map(DeviceManagementRow::getCameraId)
                .collect(Collectors.toSet());

        List<DeviceManagementRow> transientRows = deviceListSnapshot.stream()
                .filter(DeviceSummary::isUnvalidated)
                .filter(summary -> !savedCameraIds.contains(summary.getCameraId()))
                .map(DeviceManagementRow::fromTransient)
                .toList();

        return Stream.concat(savedRows.stream(), transientRows.stream())
                .sorted(Comparator.comparing(DeviceManagementService::sortName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Optional<DeviceActivationHistory> findLatestDeactivation(Long deviceId) {
        return activationHistoryRepository.findLatestDeactivation(deviceId);
    }

    /**
     * Resolves the admin who deactivated a device when it differs from the current
     * admin so confirmation dialogs can show who made the change.
     *
     * @param history deactivation history row
     * @return username of the other admin, or empty for current/unknown user
     */
    public Optional<String> findOtherDeactivationAdmin(DeviceActivationHistory history) {
        if (history == null || history.getChangedBy() == null
                || history.getChangedBy().equals(session.getCurrentUserId())) {
            return Optional.empty();
        }
        return userRepository.findById(history.getChangedBy())
                .map(User::getUsername)
                .filter(username -> !username.isBlank());
    }

    public boolean isActive(String cameraId) {
        return validatedDeviceRepository.findByCameraId(cameraId)
                .map(ValidatedDevice::isActive)
                .orElse(true);
    }

    @Transactional
    public void renameDevice(Long deviceId, String displayName) {
        requireAdmin();
        if (deviceId == null) {
            throw new ValidationException("Device is not saved");
        }
        String normalized = normalizeDeviceName(displayName);
        validatedDeviceRepository.updateDeviceName(deviceId, normalized);
        databaseRecoveryService.backupSourceToBackup();
        log.info("Renamed device id={} to '{}'", deviceId, normalized);
    }

    @Transactional
    public void deactivate(Long deviceId) {
        requireAdmin();
        requireSavedDevice(deviceId);
        Long currentUserId = requireCurrentUserId();
        validatedDeviceRepository.deactivate(deviceId);
        activationHistoryRepository.recordChange(deviceId, false, currentUserId);
        databaseRecoveryService.backupSourceToBackup();
        log.info("Deactivated device id={} by user={}", deviceId, currentUserId);
    }

    @Transactional
    public void reactivate(Long deviceId) {
        requireAdmin();
        requireSavedDevice(deviceId);
        Long currentUserId = requireCurrentUserId();
        validatedDeviceRepository.reactivate(deviceId);
        activationHistoryRepository.recordChange(deviceId, true, currentUserId);
        databaseRecoveryService.backupSourceToBackup();
        log.info("Reactivated device id={} by user={}", deviceId, currentUserId);
    }

    private void requireAdmin() {
        if (!session.isAdmin()) {
            throw new ValidationException("Only admins can manage devices");
        }
    }

    /**
     * Device activation history requires a concrete admin ID for audit integrity.
     * Fail before changing device state when the session has no persisted user.
     *
     * @return current admin user ID
     */
    private Long requireCurrentUserId() {
        Long currentUserId = session.getCurrentUserId();
        if (currentUserId == null) {
            throw new ValidationException("Current admin user is required");
        }
        return currentUserId;
    }

    private void requireSavedDevice(Long deviceId) {
        if (deviceId == null) {
            throw new ValidationException("Device is not saved");
        }
    }

    private String normalizeDeviceName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new ValidationException("Device name is required");
        }
        return displayName.trim();
    }

    private static DeviceSummary firstNonNullSummary(DeviceSummary first, DeviceSummary second) {
        return first != null ? first : second;
    }

    private static String sortName(DeviceManagementRow row) {
        if (row.getDeviceName() != null && !row.getDeviceName().isBlank()) {
            return row.getDeviceName();
        }
        if (row.getCameraId() != null) {
            return row.getCameraId();
        }
        return "";
    }
}
