package com.app.common.modules.device.services;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.app.common.modules.device.dtos.DeviceSpec;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.common.definitions.enums.DeviceEventType;
import com.app.common.definitions.enums.DeviceStatus;
import com.app.common.modules.device.dtos.DeviceSummary;
import com.app.common.modules.device.dtos.DeviceValidationResult;
import com.app.common.modules.device.events.DeviceEvent;
import com.app.common.modules.device.events.DeviceSpecUpdatedEvent;
import com.app.common.models.ValidatedDevice;
import com.app.common.repositories.ValidatedDeviceRepository;
import com.app.common.utils.DateTimeUtil;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.Getter;

@Component
public class DeviceListState {

    private static final Comparator<DeviceSummary> DEVICE_NAME_COMPARATOR = Comparator.comparing(
            DeviceListState::sortName,
            String.CASE_INSENSITIVE_ORDER)
            .thenComparing(summary -> summary.getHardwareId() == null ? "" : summary.getHardwareId(),
                    String.CASE_INSENSITIVE_ORDER);

    private final Object stateLock = new Object();

    /**
     * Backend source of truth for every known device, keyed by camera ID.
     * JavaFX views receive copies via {@link #deviceFxItems}.
     */
    private final Map<String, DeviceSummary> devicesByCameraId = new LinkedHashMap<>();

    /**
     * JavaFX-only mirror of backend device state. Controllers bind to this stable
     * list instance; background services must use snapshot/query methods instead.
     */
    @Getter
    private final ObservableList<DeviceSummary> deviceFxItems = FXCollections.observableArrayList();

    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final DeviceMiniStatus deviceMiniStatus;

    private boolean deviceStateInitialized;

    public DeviceListState(@Lazy ValidatedDeviceRepository validatedDeviceRepository,
            DeviceMiniStatus deviceMiniStatus) {
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.deviceMiniStatus = deviceMiniStatus;
        this.deviceMiniStatus.setOnProgressChanged(this::refreshSyncProgress);
    }

    /**
     * Loads persisted devices into the backend state once and keeps any transient
     * device rows discovered before database state was loaded.
     */
    public void loadSavedDevicesIfNeeded() {
        synchronized (stateLock) {
            if (deviceStateInitialized) {
                return;
            }
        }

        List<DeviceSummary> savedSummaries = validatedDeviceRepository.findAll().stream()
                .map(this::toSavedSummary)
                .toList();

        synchronized (stateLock) {
            if (deviceStateInitialized) {
                return;
            }

            Set<String> savedCameraIds = savedSummaries.stream()
                    .map(DeviceSummary::getCameraId)
                    .collect(Collectors.toSet());

            List<DeviceSummary> transientItems = devicesByCameraId.values().stream()
                    .filter(summary -> summary.isUnvalidated() || !savedCameraIds.contains(summary.getCameraId()))
                    .toList();

            devicesByCameraId.clear();
            savedSummaries.forEach(this::putSummary);
            transientItems.forEach(this::putSummary);
            deviceStateInitialized = true;
        }
        refreshFxItems();
    }

    @EventListener
    public void handleDeviceEvent(DeviceEvent event) {
        if (event == null) {
            return;
        }
        processDeviceEvent(event);
    }

    @EventListener
    public void handleDeviceSpecificationUpdated(DeviceSpecUpdatedEvent event) {
        if (event == null || event.cameraId() == null) {
            return;
        }

        synchronized (stateLock) {
            findByCameraId(event.cameraId())
                    .filter(d -> d.isConnected() || d.isUnvalidated())
                    .ifPresent(summary -> summary.setDeviceSpec(event.deviceSpec()));
        }
        refreshFxItems();
    }

    public void markDeviceSaved(DeviceValidationResult result, String savedDeviceName) {
        if (result == null) {
            return;
        }

        synchronized (stateLock) {
            findByCameraId(result.getCameraId())
                    .filter(summary -> summary.getStatus() == DeviceStatus.UNVALIDATED)
                    .ifPresent(summary -> {
                        summary.setDeviceName(savedDeviceName);
                        summary.setStatus(DeviceStatus.CONNECTED);
                        summary.setSyncProgress(resolveSyncProgress(summary));
                        summary.setValidationResult(result);
                        summary.setWhitelistId(result.getMatchedWhitelistId());
                    });
        }
        refreshFxItems();
    }

    public void mergeSavedDevices() {
        List<ValidatedDevice> dbDevices = validatedDeviceRepository.findAll();
        Set<String> dbCameraIds = dbDevices.stream()
                .map(ValidatedDevice::getCameraId)
                .collect(Collectors.toSet());

        synchronized (stateLock) {
            for (ValidatedDevice device : dbDevices) {
                if (!devicesByCameraId.containsKey(device.getCameraId())) {
                    putSummary(toSavedSummary(device));
                }
            }

            devicesByCameraId.values().removeIf(summary -> summary.getStatus() == DeviceStatus.OFFLINE
                    && !dbCameraIds.contains(summary.getCameraId()));
        }
        refreshFxItems();
    }

    /**
     * Returns immutable snapshot copies for background services that must not touch
     * JavaFX collections.
     *
     * @return current device summaries sorted for display
     */
    public List<DeviceSummary> snapshotDeviceItems() {
        synchronized (stateLock) {
            return getSortedSnapshot();
        }
    }

    public void updateDevicesSpec(Map<String, DeviceSpec> updatedDeviceListState) {
        synchronized (stateLock) {
            updatedDeviceListState.forEach((cameraId, deviceSpec) -> findByCameraId(cameraId)
                    .filter(d -> d.isConnected() || d.isUnvalidated())
                    .ifPresent(summary -> summary.setDeviceSpec(deviceSpec)));
        }
        refreshFxItems();
    }

    private void processDeviceEvent(DeviceEvent event) {
        synchronized (stateLock) {
            resolveCameraId(event).ifPresent(cameraId -> {
                DeviceSummary summary = findByCameraId(cameraId).orElse(null);

                if (event.type() == DeviceEventType.CONNECTED) {
                    handleConnected(event);
                } else if (event.type() == DeviceEventType.DISCONNECTED && summary != null) {
                    handleDisconnected(summary);
                }
            });
        }
        refreshFxItems();
    }

    public void setDeviceActive(String cameraId, boolean active) {
        synchronized (stateLock) {
            findByCameraId(cameraId).ifPresent(summary -> summary.setActive(active));
        }
        refreshFxItems();
    }

    /**
     * Pulls current sync progress into backend device state so every open view
     * receives the same progress snapshot from the central device list.
     */
    public void refreshSyncProgress() {
        synchronized (stateLock) {
            devicesByCameraId.values().stream()
                    .filter(this::isPersistedDeviceState)
                    .forEach(summary -> summary.setSyncProgress(resolveSyncProgress(summary)));
        }
        refreshFxItems();
    }

    /**
     * Updates shared device display name so open dashboard and management views
     * stay
     * in sync after an admin rename.
     *
     * @param cameraId   persisted device camera ID
     * @param deviceName new display name
     */
    public void setDeviceName(String cameraId, String deviceName) {
        synchronized (stateLock) {
            findByCameraId(cameraId).ifPresent(summary -> summary.setDeviceName(deviceName));
        }
        refreshFxItems();
    }

    public boolean isConnected(String cameraId) {
        synchronized (stateLock) {
            return findByCameraId(cameraId)
                    .map(DeviceSummary::isConnected)
                    .orElse(false);
        }
    }

    public Optional<Long> findWhitelistIdByHardwareId(String hardwareId) {
        if (hardwareId == null || hardwareId.isBlank()) {
            return Optional.empty();
        }
        synchronized (stateLock) {
            return devicesByCameraId.values().stream()
                    .filter(summary -> hardwareId.equals(summary.getHardwareId()))
                    .map(DeviceSummary::getWhitelistId)
                    .filter(Objects::nonNull)
                    .findFirst();
        }
    }

    /**
     * Updates the in-memory device row after device-level sync completes so open
     * views redraw without requiring navigation.
     *
     * @param cameraId persisted device camera ID
     */
    public void markDeviceLastSyncNow(String cameraId) {
        if (cameraId == null || cameraId.isBlank()) {
            return;
        }
        String lastSyncAt = currentLocalDateTime();
        synchronized (stateLock) {
            findByCameraId(cameraId).ifPresent(summary -> summary.setLastSyncAt(lastSyncAt));
        }
        refreshFxItems();
    }

    private void handleConnected(DeviceEvent event) {
        DeviceValidationResult result = event.validationResult();
        if (result == null || !result.isValid()) {
            return;
        }

        if (result.isAlreadySaved()) {
            updateSavedDeviceAsConnected(result);
        } else {
            addTransientDevice(result);
        }
    }

    private void handleDisconnected(DeviceSummary summary) {
        if (summary.getStatus() == DeviceStatus.UNVALIDATED) {
            devicesByCameraId.remove(summary.getCameraId());
        } else {
            summary.setStatus(DeviceStatus.OFFLINE);
            summary.setSyncProgress(DeviceMiniStatus.SyncProgress.idle());
            summary.setDeviceSpec(null);
        }
    }

    private void updateSavedDeviceAsConnected(DeviceValidationResult result) {
        findByCameraId(result.getCameraId()).ifPresent(summary -> {
            summary.setHardwareId(result.getHardwareId());
            summary.setStatus(DeviceStatus.CONNECTED);
            summary.setActive(result.isActive());
            summary.setValidationResult(result);
            summary.setWhitelistId(result.getMatchedWhitelistId());
            summary.setSyncProgress(resolveSyncProgress(summary));
            summary.setDeviceSpec(result.getDeviceSpec());
        });
    }

    private void addTransientDevice(DeviceValidationResult result) {
        DeviceSummary summary = new DeviceSummary(
                result.getHardwareId(),
                result.getDeviceName(),
                result.getCameraId(),
                DeviceStatus.UNVALIDATED,
                currentLocalDateTime(),
                null,
                DeviceMiniStatus.SyncProgress.idle(),
                result,
                result.getMatchedWhitelistId(),
                result.isActive(),
                result.getDeviceSpec());
        putSummary(summary);
    }

    private Optional<String> resolveCameraId(DeviceEvent event) {
        DeviceValidationResult result = event.validationResult();
        if (result == null || result.getCameraId() == null) {
            return Optional.empty();
        }
        return Optional.of(result.getCameraId());
    }

    private Optional<DeviceSummary> findByCameraId(String cameraId) {
        if (cameraId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(devicesByCameraId.get(cameraId));
    }

    private void putSummary(DeviceSummary summary) {
        if (summary.getCameraId() != null) {
            devicesByCameraId.put(summary.getCameraId(), summary);
        }
    }

    private List<DeviceSummary> getSortedSnapshot() {
        return devicesByCameraId.values().stream()
                .map(this::createSnapshotSummary)
                .sorted(DEVICE_NAME_COMPARATOR)
                .toList();
    }

    private void refreshFxItems() {
        List<DeviceSummary> snapshot;
        synchronized (stateLock) {
            snapshot = getSortedSnapshot();
        }

        Runnable update = () -> deviceFxItems.setAll(snapshot);
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private DeviceSummary toSavedSummary(ValidatedDevice device) {
        DeviceSummary summary = new DeviceSummary(
                device.getHardwareId(),
                device.getDeviceName(),
                device.getCameraId(),
                DeviceStatus.OFFLINE,
                device.getLastSeenAt(),
                device.getLastSyncAt(),
                DeviceMiniStatus.SyncProgress.idle(),
                null,
                device.getWhitelistId(),
                device.isActive(),
                null);
        summary.setSyncProgress(resolveSyncProgress(summary));
        return summary;
    }

    private DeviceSummary createSnapshotSummary(DeviceSummary source) {
        return new DeviceSummary(source);
    }

    private String currentLocalDateTime() {
        return DateTimeUtil.currentSqliteDateTime();
    }

    private DeviceMiniStatus.SyncProgress resolveSyncProgress(DeviceSummary summary) {
        if (summary.getCameraId() == null) {
            return DeviceMiniStatus.SyncProgress.idle();
        }
        return deviceMiniStatus.getProgress(summary.getCameraId());
    }

    private boolean isPersistedDeviceState(DeviceSummary summary) {
        return summary.getStatus() == DeviceStatus.CONNECTED
                || summary.getStatus() == DeviceStatus.OFFLINE;
    }

    private static String sortName(DeviceSummary summary) {
        if (summary.getDeviceName() != null && !summary.getDeviceName().isBlank()) {
            return summary.getDeviceName();
        }
        if (summary.getHardwareId() != null) {
            return summary.getHardwareId();
        }
        return "";
    }
}
