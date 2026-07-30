package com.app.common.modules.datasync.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.app.common.modules.device.services.AdbClient;
import com.app.common.modules.device.configs.DeviceMediaLayout;
import com.app.common.services.MassStorageService;

@Service
public class DcamMediaIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(DcamMediaIntegrityService.class);
    private static final Pattern MD5_SIDECAR_PATTERN = Pattern.compile("^([0-9a-fA-F]{32})(?:\\s+.*)?$");

    private final AdbClient adbClient;
    private final MassStorageService massStorageService;
    private final DeviceMediaLayout deviceMediaLayout;

    public DcamMediaIntegrityService(@Lazy AdbClient adbClient, MassStorageService massStorageService,
            DeviceMediaLayout deviceMediaLayout) {
        this.adbClient = adbClient;
        this.massStorageService = massStorageService;
        this.deviceMediaLayout = deviceMediaLayout;
    }

    public boolean requiresMd5(String remotePath) {
        if (remotePath == null) {
            return false;
        }

        String normalized = remotePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        boolean videoFolder = normalized.contains("/video/") || normalized.contains("/imp/");
        return deviceMediaLayout.isDcamMediaPath(remotePath) && videoFolder && normalized.endsWith(".mp4");
    }

    public String qualifiedMd5(String hardwareId, String remotePath, String sidecarPath, String driveLetter) {
        if (sidecarPath == null || sidecarPath.isBlank()) {
            log.warn("DCAM video excluded: missing MD5 sidecar: {}", remotePath);
            return null;
        }

        String expected = extractMd5(readExpectedDigest(hardwareId, sidecarPath, driveLetter));
        if (expected == null) {
            log.warn("DCAM video excluded: missing or invalid MD5 sidecar: {}", remotePath);
            return null;
        }

        String actual = calculateDigest(hardwareId, remotePath, driveLetter);
        boolean matches = matches(expected, actual);
        if (!matches) {
            log.warn("DCAM video excluded: MD5 mismatch: {}", remotePath);
            return null;
        }
        return expected.toLowerCase(Locale.ROOT);
    }

    public boolean matchesLocalFile(Path file, String expected) {
        return matches(expected, calculateMd5(file));
    }

    private String readExpectedDigest(String hardwareId, String sidecarPath, String driveLetter) {
        if (sidecarPath.startsWith(MassStorageService.MASS_STORAGE_PREFIX)) {
            return driveLetter == null ? "" : massStorageService.readTextFile(driveLetter, sidecarPath).trim();
        }
        return adbClient.readTextFile(hardwareId, sidecarPath).trim();
    }

    private String calculateDigest(String hardwareId, String remotePath, String driveLetter) {
        if (remotePath.startsWith(MassStorageService.MASS_STORAGE_PREFIX)) {
            return driveLetter == null ? "" : massStorageService.calculateMd5(driveLetter, remotePath);
        }
        return adbClient.calculateMd5(hardwareId, remotePath);
    }

    private static boolean isMd5(String digest) {
        return digest != null && digest.matches("[0-9a-fA-F]{32}");
    }

    private static String extractMd5(String sidecarContent) {
        if (sidecarContent == null) {
            return null;
        }
        Matcher matcher = MD5_SIDECAR_PATTERN.matcher(sidecarContent.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static boolean matches(String expected, String actual) {
        return isMd5(expected) && isMd5(actual) && MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private static String calculateMd5(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("Local MD5 calculation failed: {}: {}", file, e.getMessage());
            return "";
        }
    }
}
