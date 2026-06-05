package com.app.common.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class WindowsCommandService {

    private static final Logger log = LoggerFactory.getLogger(WindowsCommandService.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final String ATTRIB_CMD = "attrib";

    // ── PowerShell ───────────────────────────────────────────────────────────

    public String runPowerShell(String script) throws IOException, InterruptedException {
        return runPowerShell(script, DEFAULT_TIMEOUT_SECONDS);
    }

    public String runPowerShell(String script, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true);
        Process process = pb.start();

        try {
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("PowerShell timed out after {}s", timeoutSeconds);
                return "";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("PowerShell exited with code {}: {}", exitCode, output);
                return "";
            }

            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    // ── CMD ──────────────────────────────────────────────────────────────────

    public int runCmd(File workingDir, String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(args)
                .directory(workingDir)
                .redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
        }

        String output = sb.toString().trim();
        int code = process.waitFor();
        if (!output.isEmpty()) log.debug("[cmd] {}", output);
        return code;
    }

    // ── Folder attributes ────────────────────────────────────────────────────

    public int renameFolder(File workingDir, String from, String to) throws IOException, InterruptedException {
        return runCmd(workingDir, "cmd", "/c", "ren", from, to);
    }

    public void applyHiddenSystem(String path) throws IOException, InterruptedException {
        runCmd(null, "cmd", "/c", ATTRIB_CMD, "+h", "+s", path);
    }

    public void removeHiddenSystem(String path) throws IOException, InterruptedException {
        runCmd(null, "cmd", "/c", ATTRIB_CMD, "-h", "-s", path);
    }

    public void applyDeleteProtection(String path) throws IOException, InterruptedException {
        String user = System.getProperty("user.name");
        int code = runCmd(null, "icacls", path, "/deny", user + ":(D)");
        if (code != 0) log.warn("Delete protection failed for: {}", path);
    }

    public void removeDeleteProtection(String path) throws IOException, InterruptedException {
        String user = System.getProperty("user.name");
        int code = runCmd(null, "icacls", path, "/remove:d", user);
        if (code != 0) log.warn("Remove delete protection failed for: {}", path);
    }

    public void setHidden(String path) throws IOException, InterruptedException {
        runCmd(null, "cmd", "/c", ATTRIB_CMD, "+h", path);
    }

    // ── Drive resolution ─────────────────────────────────────────────────────

    public String resolveDiskNumber(String serial) throws IOException, InterruptedException {
        String script = String.format(
                "Get-Disk | Where-Object { $_.SerialNumber -ne $null -and $_.SerialNumber.Trim() -eq '%s' } " +
                        "| Select-Object -ExpandProperty Number",
                serial);
        return runPowerShell(script).trim();
    }

    public String resolveDriveLetter(String diskNumber) throws IOException, InterruptedException {
        String script = String.format(
                "Get-Partition -DiskNumber %s -ErrorAction SilentlyContinue " +
                        "| Get-Volume -ErrorAction SilentlyContinue " +
                        "| Where-Object { $_.DriveLetter -ne $null } " +
                        "| Select-Object -ExpandProperty DriveLetter -First 1",
                diskNumber);
        return runPowerShell(script).trim();
    }

    // ── VSS ──────────────────────────────────────────────────────────────────

    /**
     * Creates a VSS snapshot for the specified volume.
     * Returns the device object path (e.g. \\?\GLOBALROOT\Device\HarddiskVolumeShadowCopy1)
     * or null if the operation fails.
     */
    public String createVssSnapshot(String volumeRoot) throws IOException, InterruptedException {
        String script = String.format(
                "$s = (Get-WmiObject -List Win32_ShadowCopy).Create('%s', 'ClientAccessible'); " +
                        "if ($s.ReturnValue -ne 0) { exit 1 }; " +
                        "Get-WmiObject Win32_ShadowCopy | Where-Object { $_.ID -eq $s.ShadowID } " +
                        "| Select-Object -ExpandProperty DeviceObject",
                volumeRoot);

        String result = runPowerShell(script).trim();
        if (result.isBlank()) {
            log.warn("VSS snapshot creation failed for volume: {}", volumeRoot);
            return null;
        }

        log.info("VSS snapshot created: {}", result);
        return result;
    }

    /**
     * Creates a symbolic link pointing to the snapshot device path.
     * Returns true if successful.
     */
    public boolean createSymlink(String linkPath, String targetDevicePath) throws IOException, InterruptedException {
        int code = runCmd(null, "cmd", "/c", "mklink", "/d", linkPath, targetDevicePath + "\\");
        if (code != 0) {
            log.warn("Failed to create symlink: {} -> {}", linkPath, targetDevicePath);
            return false;
        }
        log.info("Symlink created: {} -> {}", linkPath, targetDevicePath);
        return true;
    }

    /**
     * Deletes the symbolic link.
     */
    public void deleteSymlink(String linkPath) throws IOException, InterruptedException {
        int code = runCmd(null, "cmd", "/c", "rmdir", linkPath);
        if (code != 0) log.warn("Failed to delete symlink: {}", linkPath);
    }

    /**
     * Deletes the VSS snapshot by its ID.
     */
    public void deleteVssSnapshot(String shadowId) throws IOException, InterruptedException {
        String script = String.format(
                "Get-WmiObject Win32_ShadowCopy | Where-Object { $_.ID -eq '%s' } " +
                        "| ForEach-Object { $_.Delete() }",
                shadowId);
        runPowerShell(script);
        log.info("VSS snapshot deleted: {}", shadowId);
    }
}