package com.app.common.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class WindowsCommandService {

    private static final Logger log = LoggerFactory.getLogger(WindowsCommandService.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int ELEVATED_COMMAND_TIMEOUT_SECONDS = 360;
    private static final int INSTALLER_LAUNCH_ATTEMPTS = 3;
    private static final int INSTALLER_LAUNCH_RETRY_DELAY_SECONDS = 2;
    private static final int PROCESS_TIMEOUT_EXIT_CODE = 124;
    private static final String ATTRIB_CMD = "attrib";
    private static final String DEFAULT_WINDOWS_ROOT = "C:\\Windows";
    private static final String POWERSHELL_RELATIVE_PATH = "WindowsPowerShell\\v1.0\\powershell.exe";
    private static final Pattern WINDOWS_ACCOUNT_NAME_PATTERN = Pattern.compile("[A-Za-z0-9 ._@\\\\-]+");

    // ── PowerShell ───────────────────────────────────────────────────────────

    public String runPowerShell(String script) throws IOException, InterruptedException {
        return runPowerShell(script, DEFAULT_TIMEOUT_SECONDS);
    }

    @SuppressWarnings("java:S4036")
    public String runPowerShell(String script, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                resolvePowerShell(), "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true);
        Process process = pb.start();
        CompletableFuture<String> outputFuture = readOutputAsync(process);

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("PowerShell timed out after {}s", timeoutSeconds);
                process.destroyForcibly();
                return "";
            }

            String output = waitForOutput(outputFuture);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("PowerShell exited with code {}: {}", exitCode, output);
                return "";
            }

            return output;
        } finally {
            if (process.isAlive())
                process.destroyForcibly();
        }
    }

    /**
     * Starts PowerShell without waiting for command completion. Use this for
     * hand-off workflows where the launched process must outlive the Java app.
     *
     * @param script PowerShell script to start
     * @throws IOException when PowerShell cannot be started
     */
    @SuppressWarnings("java:S4036")
    public void startPowerShell(String script) throws IOException {
        new ProcessBuilder(resolvePowerShell(), "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static String resolvePowerShell() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            systemRoot = DEFAULT_WINDOWS_ROOT;
        }

        File sysnative = new File(systemRoot, "Sysnative\\" + POWERSHELL_RELATIVE_PATH);
        File system32 = new File(systemRoot, "System32\\" + POWERSHELL_RELATIVE_PATH);

        if (sysnative.exists()) {
            return sysnative.getAbsolutePath();
        }
        return system32.getAbsolutePath();
    }

    // ── CMD ──────────────────────────────────────────────────────────────────

    public int runCmd(File workingDir, String... args) throws IOException, InterruptedException {
        CommandResult result = runCmdWithOutput(workingDir, args);
        if (!result.output().isEmpty()) {
            log.debug("[cmd] {}", result.output());
        }
        return result.exitCode();
    }

    public CommandResult runCmdWithOutput(File workingDir, String... args) throws IOException, InterruptedException {
        return runCmdWithTimeout(DEFAULT_COMMAND_TIMEOUT_SECONDS, workingDir, args);
    }

    private CommandResult runCmdWithTimeout(int timeoutSeconds, File workingDir, String... args)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(args)
                .directory(workingDir)
                .redirectErrorStream(true);
        Process process = pb.start();
        CompletableFuture<String> outputFuture = readOutputAsync(process);

        try {
            boolean finished = timeoutSeconds <= 0
                    ? process.waitFor(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
                    : process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                terminateProcessTree(process);
                return new CommandResult(PROCESS_TIMEOUT_EXIT_CODE,
                        "Process timed out after " + timeoutSeconds + " seconds");
            }

            return new CommandResult(process.exitValue(), waitForOutput(outputFuture));
        } catch (InterruptedException e) {
            terminateProcessTree(process);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process.isAlive()) {
                terminateProcessTree(process);
            }
        }
    }

    public CommandResult runElevatedPowerShell(String script) throws IOException, InterruptedException {
        String encodedScript = Base64.getEncoder()
                .encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        String escapedPowerShell = escapePowerShellSingleQuotedValue(resolvePowerShell());
        String escapedScript = escapePowerShellSingleQuotedValue(encodedScript);
        String wrapper = "$encoded = '" + escapedScript + "'; "
                + "try { "
                + "$child = Start-Process -FilePath '" + escapedPowerShell + "' "
                + "-ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-EncodedCommand',$encoded) "
                + "-Verb RunAs -WindowStyle Hidden -Wait -PassThru -ErrorAction Stop; "
                + "exit $child.ExitCode "
                + "} catch { "
                + "$exception = $_.Exception; "
                + "$uacDenied = $false; "
                + "while ($null -ne $exception) { "
                + "$nativeCode = $exception.NativeErrorCode; "
                + "$hresult = $exception.HResult; "
                + "$message = [string]$exception.Message; "
                + "if ($nativeCode -eq 1223 -or $hresult -eq -2147023673 "
                + "-or $message -match '(?i)cancelled|canceled|1223|800704c7') { "
                + "$uacDenied = $true; break "
                + "}; "
                + "$exception = $exception.InnerException "
                + "}; "
                + "if ($uacDenied) { exit 10 }; "
                + "exit 17 "
                + "}";

        return runCmdWithTimeout(
                ELEVATED_COMMAND_TIMEOUT_SECONDS,
                null,
                resolvePowerShell(), "-NoProfile", "-NonInteractive", "-Command", wrapper);
    }

    private void terminateProcessTree(Process process) {
        if (!process.isAlive()) {
            return;
        }

        try {
            Process killer = new ProcessBuilder(
                    "taskkill", "/PID", Long.toString(process.pid()), "/T", "/F")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!killer.waitFor(5, TimeUnit.SECONDS)) {
                killer.destroyForcibly();
            }
        } catch (IOException e) {
            log.debug("Failed to terminate process tree for PID {}", process.pid(), e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    public record CommandResult(int exitCode, String output) {
    }

    /**
     * Starts a hidden PowerShell watcher that waits for a process to exit, then
     * launches an installer with elevation. Use for update handoff flows that must
     * not show a command window while the Java process shuts down.
     *
     * @param processId         process ID to wait for before installer launch
     * @param installer         installer executable to launch
     * @param installerArgument optional installer argument
     * @throws IOException when PowerShell cannot be started
     */
    public void startInstallerAfterProcessExit(long processId, File installer, String installerArgument)
            throws IOException {
        String installerPath = escapePowerShellSingleQuotedValue(installer.getAbsolutePath());
        String argument = escapePowerShellSingleQuotedValue(installerArgument == null ? "" : installerArgument);
        String script = "$parentPid = " + processId + "; "
                + "$installerPath = '" + installerPath + "'; "
                + "$installerArgument = '" + argument + "'; "
                + "Wait-Process -Id $parentPid -ErrorAction SilentlyContinue; "
                + "for ($attempt = 1; $attempt -le " + INSTALLER_LAUNCH_ATTEMPTS + "; $attempt++) { "
                + "try { "
                + "Start-Process -FilePath $installerPath -ArgumentList $installerArgument -Verb RunAs -ErrorAction Stop; "
                + "break "
                + "} catch { "
                + "if ($attempt -ge " + INSTALLER_LAUNCH_ATTEMPTS + ") { throw }; "
                + "Start-Sleep -Seconds " + INSTALLER_LAUNCH_RETRY_DELAY_SECONDS + " "
                + "} "
                + "}";

        startPowerShell(script);
        log.info(
                "Queued installer launch after process exit. processId={}, installer={}, attempts={}, retryDelaySeconds={}",
                processId, installer.getAbsolutePath(), INSTALLER_LAUNCH_ATTEMPTS,
                INSTALLER_LAUNCH_RETRY_DELAY_SECONDS);
    }

    private String escapePowerShellSingleQuotedValue(String value) {
        return value.replace("'", "''");
    }

    private CompletableFuture<String> readOutputAsync(Process process) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!output.isEmpty())
                        output.append('\n');
                    output.append(line);
                }
            } catch (IOException e) {
                log.warn("Failed to read command output", e);
            }
            return output.toString().trim();
        });
    }

    private String waitForOutput(CompletableFuture<String> outputFuture) throws InterruptedException {
        try {
            return outputFuture.get();
        } catch (ExecutionException e) {
            log.warn("Failed to collect command output", e);
            return "";
        }
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
        String user = resolveCurrentWindowsAccountName();
        int code = runCmd(null, "icacls", path, "/deny", user + ":(D)");
        if (code != 0)
            log.warn("Delete protection failed for: {}", path);
    }

    public void removeDeleteProtection(String path) throws IOException, InterruptedException {
        String user = resolveCurrentWindowsAccountName();
        int code = runCmd(null, "icacls", path, "/remove:d", user);
        if (code != 0)
            log.warn("Remove delete protection failed for: {}", path);
    }

    private String resolveCurrentWindowsAccountName() {
        String user = System.getProperty("user.name");
        if (user == null || user.isBlank() || !WINDOWS_ACCOUNT_NAME_PATTERN.matcher(user).matches()) {
            throw new IllegalStateException("Current Windows account name contains unsupported characters");
        }
        return user;
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
     * Returns the device object path (e.g.
     * \\?\GLOBALROOT\Device\HarddiskVolumeShadowCopy1)
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
        if (code != 0)
            log.warn("Failed to delete symlink: {}", linkPath);
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
