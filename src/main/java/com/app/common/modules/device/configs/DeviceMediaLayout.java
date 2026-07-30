package com.app.common.modules.device.configs;

import java.io.File;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeviceMediaLayout {

    private static final String ANDROID_DATA_SEGMENT = "/Android/data/";
    private static final String DCAM_MEDIA_SUFFIX = "/files/Media";
    private static final String LEGACY_MEDIA_SUFFIX = "/cache";

    private final String legacyDeviceDataFolder;
    private final String dcamDeviceDataFolder;
    private final String legacyInternalMediaRoot;
    private final String legacyExternalMediaSuffix;
    private final String dcamInternalMediaRoot;
    private final String dcamExternalMediaSuffix;
    private final String legacyMassStorageMediaPath;
    private final String dcamMassStorageMediaPath;

    public DeviceMediaLayout(
            @Value("${device.validation.required-device-data-folder}") String legacyDeviceDataFolder,
            @Value("${device.validation.alternative-device-data-folder}") String dcamDeviceDataFolder) {
        this.legacyDeviceDataFolder = validateDeviceDataFolder(legacyDeviceDataFolder, "legacy");
        this.dcamDeviceDataFolder = validateDeviceDataFolder(dcamDeviceDataFolder, "DCAM");
        this.legacyInternalMediaRoot = normalizeRoot(
                System.getProperty("app.internal.root", "/storage/emulated/0/DCIM"));
        this.legacyExternalMediaSuffix = suffix(this.legacyDeviceDataFolder, LEGACY_MEDIA_SUFFIX);
        this.dcamInternalMediaRoot = this.dcamDeviceDataFolder + DCAM_MEDIA_SUFFIX;
        this.dcamExternalMediaSuffix = suffix(this.dcamDeviceDataFolder, DCAM_MEDIA_SUFFIX);
        this.legacyMassStorageMediaPath = toMassStoragePath(legacyExternalMediaSuffix);
        this.dcamMassStorageMediaPath = toMassStoragePath(dcamExternalMediaSuffix);
    }

    public String legacyDeviceDataFolder() {
        return legacyDeviceDataFolder;
    }

    public String dcamDeviceDataFolder() {
        return dcamDeviceDataFolder;
    }

    public String legacyInternalMediaRoot() {
        return legacyInternalMediaRoot;
    }

    public String legacyExternalMediaRoot(String externalStorageRoot) {
        return normalizeRoot(externalStorageRoot) + legacyExternalMediaSuffix;
    }

    public String dcamInternalMediaRoot() {
        return dcamInternalMediaRoot;
    }

    public String dcamExternalMediaRoot(String externalStorageRoot) {
        return normalizeRoot(externalStorageRoot) + dcamExternalMediaSuffix;
    }

    public String legacyMassStorageMediaPath() {
        return legacyMassStorageMediaPath;
    }

    public String dcamMassStorageMediaPath() {
        return dcamMassStorageMediaPath;
    }

    public boolean isDcamMediaPath(String path) {
        if (path == null) {
            return false;
        }

        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String configuredMediaSuffix = dcamExternalMediaSuffix.toLowerCase(Locale.ROOT);
        return normalized.startsWith("/mass_storage/dcam/")
                || normalized.endsWith(configuredMediaSuffix)
                || normalized.contains(configuredMediaSuffix + "/");
    }

    private static String validateDeviceDataFolder(String path, String label) {
        String normalized = normalizeRoot(path);
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        int androidDataIndex = indexOfIgnoreCase(normalized, ANDROID_DATA_SEGMENT);
        if (androidDataIndex < 0) {
            throw new IllegalArgumentException(
                    label + " device data folder must contain " + ANDROID_DATA_SEGMENT + ": " + path);
        }

        String packageName = normalized.substring(androidDataIndex + ANDROID_DATA_SEGMENT.length());
        if (packageName.isBlank() || packageName.contains("/")) {
            throw new IllegalArgumentException(
                    label + " device data folder must end with application package: " + path);
        }
        return normalized;
    }

    private static String suffix(String deviceDataFolder, String mediaSuffix) {
        int androidDataIndex = indexOfIgnoreCase(deviceDataFolder, ANDROID_DATA_SEGMENT);
        return deviceDataFolder.substring(androidDataIndex) + mediaSuffix;
    }

    private static String toMassStoragePath(String mediaSuffix) {
        return mediaSuffix.substring(1).replace('/', File.separatorChar);
    }

    private static String normalizeRoot(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Device media path is required");
        }

        String normalized = path.trim().replace('\\', '/');
        int end = normalized.length();
        while (end > 1 && normalized.charAt(end - 1) == '/') {
            end--;
        }
        return normalized.substring(0, end);
    }

    private static int indexOfIgnoreCase(String value, String target) {
        return value.toLowerCase(Locale.ROOT).indexOf(target.toLowerCase(Locale.ROOT));
    }
}
