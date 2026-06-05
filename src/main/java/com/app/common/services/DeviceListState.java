package com.app.common.services;

import com.app.common.dtos.DeviceSummary;
import com.app.common.dtos.DeviceValidationResult;
import com.app.common.events.DeviceEvent;
import com.app.common.models.ValidatedDevice;
import com.app.common.repositories.ValidatedDeviceRepository;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.Getter;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DeviceListState {

    @Getter
    private final ObservableList<DeviceSummary> deviceItems = FXCollections.observableArrayList();

    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final DeviceMiniStatus deviceMiniStatus;

    private boolean deviceStateInitialized;

    private static final Comparator<DeviceSummary> DEVICE_NAME_COMPARATOR = Comparator.comparing(
                    DeviceListState::sortName,
                    String.CASE_INSENSITIVE_ORDER)
            .thenComparing(summary -> summary.getHardwareId() == null ? "" : summary.getHardwareId(),
                    String.CASE_INSENSITIVE_ORDER);

    public DeviceListState(ValidatedDeviceRepository validatedDeviceRepository,
                           DeviceMiniStatus deviceMiniStatus) {
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.deviceMiniStatus = deviceMiniStatus;
    }

    public void loadSavedDevicesIfNeeded() {
        if (deviceStateInitialized) {
            return;
        }

        var savedSummaries = validatedDeviceRepository.findAll().stream()
                .map(this::toSavedSummary)
                .toList();
        Set<String> savedCameraIds = savedSummaries.stream()
                .map(DeviceSummary::getCameraId)
                .collect(Collectors.toSet());

        var transientItems = deviceItems.stream()
                .filter(summary -> summary.isUnvalidated() || !savedCameraIds.contains(summary.getCameraId()))
                .toList();

        deviceItems.setAll(savedSummaries);
        deviceItems.addAll(transientItems);
        sortDeviceItems();
        deviceStateInitialized = true;
    }

    public void resetState() {
        deviceItems.clear();
        deviceStateInitialized = false;
    }

    @EventListener
    public void handleDeviceEvent(DeviceEvent event) {
        if (event == null) {
            return;
        }
        Platform.runLater(() -> processDeviceEvent(event));
    }

    public void markDeviceSaved(DeviceValidationResult result, String savedDeviceName) {
        if (result == null) {
            return;
        }

        findByCameraId(result.getCameraId())
                .filter(summary -> summary.getStatus() == DeviceSummary.Status.UNVALIDATED)
                .ifPresent(summary -> {
                    summary.setDeviceName(savedDeviceName);
                    summary.setStatus(DeviceSummary.Status.CONNECTED);
                    summary.setSyncProgress(resolveSyncProgress(summary));
                    summary.setValidationResult(null);
                });
        sortDeviceItems();
    }

    public void mergeSavedDevices() {
        var dbDevices = validatedDeviceRepository.findAll();

        Set<String> dbCameraIds = dbDevices.stream()
                .map(ValidatedDevice::getCameraId)
                .collect(Collectors.toSet());

        for (ValidatedDevice device : dbDevices) {
            boolean alreadyInList = deviceItems.stream()
                    .anyMatch(s -> device.getCameraId().equals(s.getCameraId()));
            if (!alreadyInList) {
                deviceItems.add(toSavedSummary(device));
            }
        }

        deviceItems.removeIf(summary -> summary.getStatus() == DeviceSummary.Status.OFFLINE
                && !dbCameraIds.contains(summary.getCameraId()));

        sortDeviceItems();
    }

    private void processDeviceEvent(DeviceEvent event) {
        if (event.type() == DeviceEvent.EventType.CONNECTED) {
            handleConnected(event);
        } else if (event.type() == DeviceEvent.EventType.DISCONNECTED) {
            handleDisconnectedEvent(event);
        } else if (event.type() == DeviceEvent.EventType.UNVALIDATED) {
            handleUnvalidated(event);
        }
    }

    private void handleDisconnectedEvent(DeviceEvent event) {
        resolveCameraId(event).ifPresent(this::handleDisconnected);
    }

    private void handleUnvalidated(DeviceEvent event) {
        DeviceValidationResult result = event.validationResult();
        if (result == null) {
            return;
        }

        if (findByCameraId(result.getCameraId()).isPresent()) {
            return;
        }

        addTransientDevice(result);
        sortDeviceItems();
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
            sortDeviceItems();
        }
    }

    private void updateSavedDeviceAsConnected(DeviceValidationResult result) {
        findByCameraId(result.getCameraId()).ifPresent(summary -> {
            summary.setHardwareId(result.getHardwareId());
            summary.setStatus(DeviceSummary.Status.CONNECTED);
            summary.setSyncProgress(resolveSyncProgress(summary));
        });
    }

    private void addTransientDevice(DeviceValidationResult result) {
        DeviceSummary summary = new DeviceSummary(
                result.getHardwareId(),
                result.getCameraId(),
                result.getCameraId(),
                DeviceSummary.Status.UNVALIDATED,
                DeviceMiniStatus.SyncProgress.idle(),
                result);
        deviceItems.add(summary);
    }

    private void handleDisconnected(String cameraId) {
        findByCameraId(cameraId).ifPresent(summary -> {
            if (summary.getStatus() == DeviceSummary.Status.UNVALIDATED) {
                deviceItems.remove(summary);
            } else {
                summary.setStatus(DeviceSummary.Status.OFFLINE);
                summary.setSyncProgress(DeviceMiniStatus.SyncProgress.idle());
            }
            sortDeviceItems();
        });
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
        return deviceItems.stream()
                .filter(summary -> cameraId.equals(summary.getCameraId()))
                .findFirst();
    }

    private DeviceSummary toSavedSummary(ValidatedDevice device) {
        DeviceSummary summary = new DeviceSummary(
                device.getHardwareId(),
                device.getDeviceName(),
                device.getCameraId(),
                DeviceSummary.Status.OFFLINE,
                DeviceMiniStatus.SyncProgress.idle(),
                null);
        summary.setSyncProgress(resolveSyncProgress(summary));
        return summary;
    }

    private DeviceMiniStatus.SyncProgress resolveSyncProgress(DeviceSummary summary) {
        if (summary.getCameraId() == null) {
            return DeviceMiniStatus.SyncProgress.idle();
        }
        return deviceMiniStatus.getProgress(summary.getCameraId());
    }

    private void sortDeviceItems() {
        FXCollections.sort(deviceItems, DEVICE_NAME_COMPARATOR);
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
