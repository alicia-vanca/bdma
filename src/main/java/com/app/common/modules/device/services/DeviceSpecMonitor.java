package com.app.common.modules.device.services;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.app.common.definitions.enums.DeviceStatus;
import com.app.common.exceptions.DeviceDisconnectedException;
import com.app.common.modules.device.dtos.DeviceSpec;
import com.app.common.modules.device.dtos.DeviceSummary;
import com.app.common.services.MassStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

@Component
public class DeviceSpecMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeviceSpecMonitor.class);

    private final AdbClient adbClient;
    private final MassStorageService massStorageService;
    private final DeviceDriveLetterResolver deviceDriveLetterResolver;
    private final DeviceListState deviceListState;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "device-specification-monitor");
        thread.setDaemon(true);
        return thread;
    });

    public DeviceSpecMonitor(@Lazy AdbClient adbClient,
            MassStorageService massStorageService,
            DeviceDriveLetterResolver deviceDriveLetterResolver,
            DeviceListState deviceListState) {
        this.adbClient = adbClient;
        this.massStorageService = massStorageService;
        this.deviceDriveLetterResolver = deviceDriveLetterResolver;
        this.deviceListState = deviceListState;
        executor.scheduleAtFixedRate(this::refreshSpecificationSafely, 0, 60, TimeUnit.SECONDS);
    }

    private void refreshSpecificationSafely() {
        try {
            refreshSpecification();
        } catch (RuntimeException e) {
            log.warn("Scheduled device specification refresh failed", e);
        }
    }

    public void refreshSpecification() {
        List<DeviceSummary> connectedDevices = deviceListState.snapshotDeviceItems()
                .stream()
                .filter(deviceSummary -> deviceSummary.getStatus() == DeviceStatus.CONNECTED
                        || deviceSummary.getStatus() == DeviceStatus.UNVALIDATED)
                .toList();
        if (connectedDevices.isEmpty()) {
            return;
        }
        refreshBatteryInfo(connectedDevices);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void refreshBatteryInfo(List<DeviceSummary> deviceSummaries) {
        Map<String, DeviceSpec> updatedDeviceListState = new HashMap<>();
        for (DeviceSummary deviceSummary : deviceSummaries) {
            DeviceSpec deviceSpec = deviceSummary.getDeviceSpec();
            if (deviceSpec == null) {
                continue;
            }
            applyBattery(deviceSpec, adbClient.getBatteryInfo(deviceSummary.getHardwareId()));
            updatedDeviceListState.put(deviceSummary.getCameraId(), deviceSpec);
        }
        deviceListState.updateDevicesSpec(updatedDeviceListState);
    }

    public DeviceSpec getDeviceSpecInfo(String hardwareId) {
        DeviceSpec info = new DeviceSpec();
        try {
            applyBattery(info, adbClient.getBatteryInfo(hardwareId));
            Map<String, String> extStorageInfo = adbClient.getExtStorageInfo(hardwareId);
            Map<String, Long> massStorageInfo = extStorageInfo.isEmpty()
                    ? getMassStorageInfo(hardwareId)
                    : Map.of();
            applyStorage(
                    info,
                    adbClient.getIntStorageInfo(hardwareId),
                    extStorageInfo,
                    massStorageInfo);
        } catch (DeviceDisconnectedException e) {
            log.debug("Skip reading device specification because device is disconnected: hardwareId={}", hardwareId);
        } catch (Exception e) {
            log.warn("Failed to read device specification: hardwareId={}", hardwareId, e);
        }
        return info;
    }

    private Map<String, Long> getMassStorageInfo(String hardwareId) {
        String driveLetter = deviceDriveLetterResolver.resolve(hardwareId);
        return driveLetter == null || driveLetter.isBlank()
                ? Map.of()
                : massStorageService.getStorageSize(driveLetter);
    }

    private void applyBattery(DeviceSpec info, Map<String, String> batteryInfo) {
        if (batteryInfo == null || batteryInfo.isEmpty()) {
            return;
        }
        info.setBatteryLevel(normalizePercent(batteryInfo.get("level")));
        info.setBatteryStatus(normalizeBatteryStatus(batteryInfo.get("status")));
    }

    private void applyStorage(DeviceSpec info,
            Map<String, String> intStorageInfo,
            Map<String, String> extStorageInfo,
            Map<String, Long> massStorageInfo) {
        if (intStorageInfo == null) {
            intStorageInfo = Map.of();
        }
        if (extStorageInfo == null) {
            extStorageInfo = Map.of();
        }
        if (massStorageInfo == null) {
            massStorageInfo = Map.of();
        }

        long intTotal = parseKiB(firstValue(intStorageInfo, "1K-blocks", "1K-block", "Size"));
        long intUsed = parseKiB(intStorageInfo.get("Used"));

        long extTotal;
        long extUsed;

        if (!extStorageInfo.isEmpty()) {
            extTotal = parseKiB(firstValue(extStorageInfo, "1K-blocks", "1K-block", "Size"));
            extUsed = parseKiB(extStorageInfo.get("Used"));
        } else {
            extTotal = parseBytesToKiB(massStorageInfo.get("Size"));
            extUsed = parseBytesToKiB(massStorageInfo.get("Used"));
        }

        long totalStorage = intTotal + extTotal;
        long totalUsed = intUsed + extUsed;

        double usedPercent = totalStorage == 0 ? 0 : (totalUsed * 100.0 / totalStorage);

        info.setStorageTotal(formatKiB(totalStorage));
        info.setStorageUsed(formatKiB(totalUsed));
        info.setStorageUsePercent(String.format(Locale.US, "%.1f%%", usedPercent));
    }

    private String firstValue(Map<String, String> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            String value = values.get(key);
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String normalizePercent(String value) {
        if (isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.endsWith("%") ? trimmed : trimmed + "%";
    }

    private long parseKiB(String value) {
        if (isBlank(value)) {
            return 0;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseBytesToKiB(Long value) {
        return value == null ? 0 : value / 1024;
    }

    private String formatKiB(long kib) {
        double gib = kib / 1024.0 / 1024.0;

        if (gib >= 1) {
            return String.format(Locale.US, "%.1f GB", gib);
        }

        double mib = kib / 1024.0;
        return String.format(Locale.US, "%.1f MB", mib);
    }

    private String normalizeBatteryStatus(String value) {
        return switch (blankToEmpty(value)) {
            case "1" -> "unknown";
            case "2" -> "charging";
            case "5" -> "full";
            default -> value == null ? null : value.trim();
        };
    }

    private String blankToEmpty(String value) {
        return isBlank(value) ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
