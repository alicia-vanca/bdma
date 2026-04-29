package com.app.common.modules.foldermanager.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.services.WindowsCommandService;

@Service
public class VssService {

    private static final Logger log = LoggerFactory.getLogger(VssService.class);
    private static final String VSS_SYMLINK_NAME = ".vss_snapshot";

    private final WindowsCommandService windowsCommandService;

    public VssService(WindowsCommandService windowsCommandService) {
        this.windowsCommandService = windowsCommandService;
    }

    /**
     * Copies a file from the locked data directory to the target location using a VSS snapshot.
     * The data_bdma directory remains locked throughout the process.
     *
     * @param sourceAbsolutePath absolute path of the file in data_bdma (locked)
     * @param targetAbsolutePath absolute destination path (inside .backup_tmp)
     */
    public void copyViaSnapshot(Path sourceAbsolutePath, Path targetAbsolutePath) throws IOException {
        Path volumeRoot = sourceAbsolutePath.getRoot();
        if (volumeRoot == null) {
            throw new IOException("Cannot resolve volume root from source path: " + sourceAbsolutePath);
        }

        Path symlinkPath = volumeRoot.resolve(VSS_SYMLINK_NAME);
        String shadowId = null;

        try {
            String deviceObject = windowsCommandService.createVssSnapshot(volumeRoot.toString());
            if (deviceObject == null) {
                throw new IOException("Failed to create VSS snapshot for volume: " + volumeRoot);
            }

            log.info("VSS snapshot created for volume: {}", volumeRoot);
            shadowId = resolveShadowId(deviceObject);

            boolean linked = windowsCommandService.createSymlink(symlinkPath.toString(), deviceObject);
            if (!linked) {
                throw new IOException("Failed to create VSS symlink: " + symlinkPath);
            }

            Path relativeFromRoot = volumeRoot.relativize(sourceAbsolutePath);
            Path snapshotFilePath = symlinkPath.resolve(relativeFromRoot);

            if (!Files.exists(snapshotFilePath)) {
                throw new IOException("File not found in VSS snapshot: " + snapshotFilePath);
            }

            Files.createDirectories(targetAbsolutePath.getParent());
            Files.copy(snapshotFilePath, targetAbsolutePath, StandardCopyOption.REPLACE_EXISTING);

            log.info("Copied via VSS: {} -> {}", sourceAbsolutePath.getFileName(), targetAbsolutePath);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while copying via VSS", e);
        } finally {
            cleanupSymlink(symlinkPath);
            cleanupSnapshot(shadowId);
        }
    }

    private String resolveShadowId(String deviceObject) {
        try {
            String escaped = deviceObject.replace("\\", "\\\\");
            String script = String.format(
                    "Get-WmiObject Win32_ShadowCopy | Where-Object { $_.DeviceObject -eq '%s' } " +
                            "| Select-Object -ExpandProperty ID",
                    escaped);

            String result = windowsCommandService.runPowerShell(script).trim();
            if (result.isBlank()) {
                log.warn("Could not resolve shadow ID for device object: {}", deviceObject);
                return null;
            }

            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while resolving shadow ID for device object: {}", deviceObject);
            return null;
        } catch (Exception e) {
            log.warn("Failed to resolve shadow ID: {}", e.getMessage());
            return null;
        }
    }

    private void cleanupSymlink(Path symlinkPath) {
        try {
            if (Files.exists(symlinkPath)) {
                windowsCommandService.deleteSymlink(symlinkPath.toString());
                log.info("VSS symlink deleted: {}", symlinkPath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while deleting VSS symlink: {}", symlinkPath);
        } catch (Exception e) {
            log.warn("Failed to delete VSS symlink: {}", symlinkPath);
        }
    }

    private void cleanupSnapshot(String shadowId) {
        if (shadowId == null) return;

        try {
            windowsCommandService.deleteVssSnapshot(shadowId);
            log.info("VSS snapshot deleted: {}", shadowId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while deleting VSS snapshot: {}", shadowId);
        } catch (Exception e) {
            log.warn("Failed to delete VSS snapshot: {}", shadowId);
        }
    }
}
