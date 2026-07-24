package com.app.common.modules.foldermanager.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.modules.foldermanager.dtos.StorageVolume;
import com.app.common.services.WindowsCommandService;

/**
 * Resolves default storage roots for a new BDMA configuration.
 *
 * <p>The Windows inventory keeps physical-disk identity alongside each mounted
 * volume. This lets the selector prefer a separate physical disk before
 * considering another partition on the operating-system disk.</p>
 */
@Service
public class DefaultStorageLocationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultStorageLocationService.class);
    private static final Set<String> INTERNAL_BUS_TYPES = Set.of(
            "ATA", "SATA", "SAS", "SCSI", "NVME", "RAID", "SCM", "UFS");
    private static final Set<String> REMOVABLE_BUS_TYPES = Set.of("USB", "1394", "SD", "MMC");
    private static final String STORAGE_INVENTORY_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $rows = @(
                Get-Partition | Where-Object { $_.DriveLetter } | ForEach-Object {
                    $partition = $_
                    $disk = Get-Disk -Number $partition.DiskNumber
                    $volume = Get-Volume -DriveLetter $partition.DriveLetter
                    [pscustomobject]@{
                        DiskNumber = [int]$partition.DiskNumber
                        DriveLetter = [string]$partition.DriveLetter
                        FreeBytes = [long]$volume.SizeRemaining
                        BusType = [string]$disk.BusType
                        DriveType = [string]$volume.DriveType
                        IsOffline = [bool]$disk.IsOffline
                        IsReadOnly = [bool]$disk.IsReadOnly
                        OperationalStatus = [string]($disk.OperationalStatus -join ',')
                    }
                }
            )
            ConvertTo-Json -InputObject $rows -Compress
            """;

    private final WindowsCommandService windowsCommandService;
    private volatile List<StorageVolume> cachedVolumes = List.of();
    private CompletableFuture<List<StorageVolume>> volumeRefresh;

    public DefaultStorageLocationService(WindowsCommandService windowsCommandService) {
        this.windowsCommandService = windowsCommandService;
    }

    /**
     * Refreshes Windows volume metadata without blocking the caller. Repeated
     * requests share the current refresh instead of launching more PowerShell
     * processes.
     */
    public synchronized void refreshVolumesAsync() {
        if (volumeRefresh != null && !volumeRefresh.isDone()) {
            return;
        }
        volumeRefresh = CompletableFuture.supplyAsync(this::discoverVolumes);
    }

    public List<StorageVolume> discoverVolumes() {
        try {
            String output = windowsCommandService.runPowerShell(STORAGE_INVENTORY_SCRIPT);
            List<StorageVolume> volumes = parseVolumes(output);
            if (!volumes.isEmpty()) {
                cachedVolumes = List.copyOf(volumes);
            }
            return volumes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Storage drive discovery was interrupted");
            return List.of();
        } catch (IOException | JSONException e) {
            log.warn("Storage drive discovery failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns whether the selected path is hosted by removable storage.
     *
     * <p>The filesystem flag is checked inline, while physical-disk bus metadata
     * comes from the background-refreshed cache. This still catches USB disks that
     * Windows reports as {@code Fixed} without starting PowerShell on the UI
     * thread. Validation waits for that refresh only when it is still running
     * after the chooser closes. If neither source can classify the path, it is
     * left eligible so a temporary discovery failure does not lock users out of
     * their settings.</p>
     */
    public boolean isOnRemovableVolume(Path path) {
        if (isReportedRemovableByFileSystem(path)) {
            return true;
        }
        awaitVolumeRefresh();
        return isOnRemovableVolume(path, cachedVolumes);
    }

    private void awaitVolumeRefresh() {
        CompletableFuture<List<StorageVolume>> refresh;
        synchronized (this) {
            refresh = volumeRefresh;
        }
        if (refresh == null) {
            return;
        }

        try {
            refresh.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for storage drive discovery");
        } catch (ExecutionException e) {
            log.warn("Storage drive discovery failed while validating a folder", e.getCause());
        }
    }

    boolean isOnRemovableVolume(Path path, List<StorageVolume> volumes) {
        if (path == null || volumes == null || volumes.isEmpty()) {
            return false;
        }

        String selectedDrive = driveLetterOf(path.toAbsolutePath().getRoot());
        if (selectedDrive == null) {
            return false;
        }

        return volumes.stream()
                .filter(volume -> volume.driveLetter().equalsIgnoreCase(selectedDrive))
                .findFirst()
                .map(this::isRemovableVolume)
                .orElse(false);
    }

    /**
     * Returns the operating-system drive root, detected dynamically.
     */
    public Path getOperatingSystemRoot() {
        Path root = rootOf(System.getenv("SystemRoot"));
        if (root == null) {
            root = rootOf(System.getenv("SystemDrive"));
        }
        if (root == null) {
            root = rootOf(System.getProperty("user.home"));
        }
        if (root == null) {
            throw new IllegalStateException("Unable to determine the operating-system drive");
        }
        return root;
    }

    public Optional<Path> findBackupRoot(Path operatingSystemRoot) {
        String operatingSystemDrive = driveLetterOf(operatingSystemRoot);
        if (operatingSystemDrive == null) {
            log.warn("Cannot select a backup drive because the OS root is invalid: {}", operatingSystemRoot);
            return Optional.empty();
        }

        List<StorageVolume> volumes = getVolumesForSelection();
        if (volumes.isEmpty()) {
            return Optional.empty();
        }

        return selectBackupRoot(volumes, operatingSystemDrive).map(DefaultStorageLocationService::toRoot);
    }

    private List<StorageVolume> getVolumesForSelection() {
        awaitVolumeRefresh();
        List<StorageVolume> volumes = cachedVolumes;
        return volumes.isEmpty() ? discoverVolumes() : volumes;
    }

    Optional<String> selectBackupRoot(List<StorageVolume> volumes, String operatingSystemDrive) {
        Optional<StorageVolume> operatingSystemVolume = volumes.stream()
                .filter(volume -> volume.driveLetter().equalsIgnoreCase(operatingSystemDrive))
                .findFirst();
        if (operatingSystemVolume.isEmpty()) {
            log.warn("Storage inventory did not contain the OS drive {}", operatingSystemDrive);
            return Optional.empty();
        }

        StorageVolume osVolume = operatingSystemVolume.get();
        Comparator<StorageVolume> byFreeSpace = Comparator.comparingLong(StorageVolume::freeBytes).reversed()
                .thenComparing(StorageVolume::driveLetter, String.CASE_INSENSITIVE_ORDER);
        List<StorageVolume> eligibleVolumes = volumes.stream()
                .filter(volume -> !volume.driveLetter().equalsIgnoreCase(operatingSystemDrive))
                .filter(this::isEligibleInternalVolume)
                .toList();

        Optional<String> separatePhysicalDisk = eligibleVolumes.stream()
                .filter(volume -> volume.diskNumber() != osVolume.diskNumber())
                .sorted(byFreeSpace)
                .map(StorageVolume::driveLetter)
                .findFirst();
        if (separatePhysicalDisk.isPresent()) {
            return separatePhysicalDisk;
        }

        return eligibleVolumes.stream()
                .filter(volume -> volume.diskNumber() == osVolume.diskNumber())
                .sorted(byFreeSpace)
                .map(StorageVolume::driveLetter)
                .findFirst();
    }

    private boolean isEligibleInternalVolume(StorageVolume volume) {
        return !volume.offline()
                && !volume.readOnly()
                && volume.freeBytes() >= 0
                && "FIXED".equals(normalize(volume.driveType()))
                && INTERNAL_BUS_TYPES.contains(normalize(volume.busType()))
                && normalize(volume.operationalStatus()).contains("ONLINE");
    }

    private boolean isRemovableVolume(StorageVolume volume) {
        return "REMOVABLE".equals(normalize(volume.driveType()))
                || REMOVABLE_BUS_TYPES.contains(normalize(volume.busType()));
    }

    private boolean isReportedRemovableByFileSystem(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(
                    Files.getFileStore(path).getAttribute("volume:isRemovable"));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.debug("Fast removable-volume check unavailable for {}: {}", path, e.getMessage());
            return false;
        }
    }

    private List<StorageVolume> parseVolumes(String output) {
        List<StorageVolume> volumes = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return volumes;
        }

        JSONArray array = new JSONArray(output);
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            String driveLetter = item.optString("DriveLetter", "").trim();
            if (!driveLetter.matches("(?i)[A-Z]")) {
                log.debug("Ignoring storage volume with invalid drive letter: {}", driveLetter);
                continue;
            }
            volumes.add(new StorageVolume(
                    item.getInt("DiskNumber"),
                    driveLetter.toUpperCase(Locale.ROOT),
                    item.getLong("FreeBytes"),
                    item.optString("BusType", ""),
                    item.optString("DriveType", ""),
                    item.optBoolean("IsOffline", true),
                    item.optBoolean("IsReadOnly", true),
                    item.optString("OperationalStatus", "")));
        }
        return volumes;
    }

    private static Path rootOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.matches("(?i)^[A-Z]:\\\\?$")) {
            return Path.of(trimmed.substring(0, 2) + "\\");
        }
        return Path.of(trimmed).toAbsolutePath().getRoot();
    }

    private static String driveLetterOf(Path root) {
        if (root == null) {
            return null;
        }
        String value = root.toAbsolutePath().toString();
        return value.matches("(?i)^[A-Z]:\\\\$") ? value.substring(0, 1).toUpperCase(Locale.ROOT) : null;
    }

    private static Path toRoot(String driveLetter) {
        return Path.of(driveLetter.toUpperCase(Locale.ROOT) + ":\\");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

}
