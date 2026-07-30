package com.app.common.modules.foldermanager.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.AppDataPaths;
import com.app.common.helpers.AlertHelper;
import com.app.common.modules.i18n.I18n;
import com.app.common.services.WindowsCommandService;
import com.app.common.services.WindowsCommandService.CommandResult;
import com.sun.jna.LastErrorException;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import javafx.stage.WindowEvent;

/**
 * Static utility service for protecting paths inside bdma folders.
 * <p>
 * Strategy:
 * - bdma root folder: deny delete (D)
 * - child folders created by ensureBdmaDir: lock with the same delete-deny rule
 */
public class FolderSecurityService {

    private static final Logger log = LoggerFactory.getLogger(FolderSecurityService.class);
    private static final WindowsCommandService WINDOWS_COMMAND_SERVICE = new WindowsCommandService();

    private static final String CMD_ICACLS = "icacls";
    private static final String PROPERTY_USER_NAME = "user.name";
    private static final String WINDOWS_AUTHENTICATED_USERS_SID = "*S-1-5-11";
    private static final String WINDOWS_EVERYONE_SID = "*S-1-1-0";
    private static final String ICACLS_DENY = "/deny";
    private static final String ICACLS_GRANT_REPLACE = "/grant:r";
    private static final String ICACLS_REMOVE_DENY = "/remove:d";
    private static final String ICACLS_PRIVATE_DIRECTORY_ACCESS = "(OI)(CI)(M)";
    private static final int ACL_REPAIR_CANCELLED = 10;
    private static final int ACL_REPAIR_INVALID_PATH = 11;
    private static final int ACL_REPAIR_OWNERSHIP_FAILED = 12;
    private static final int ACL_REPAIR_ACL_FAILED = 13;
    private static final int ACL_REPAIR_VERIFICATION_FAILED = 14;
    private static final int ACL_REPAIR_INTERRUPTED = 15;
    private static final int ACL_REPAIR_SCRIPT_FAILED = 16;
    private static final int ACL_REPAIR_REPARSE_POINT = 18;
    private static final int ACL_REPAIR_PROGRESS_FAILED = 19;
    private static final int ACL_REPAIR_DIALOG_DISMISS_TIMEOUT_SECONDS = 5;
    private static final String CHECKSUM_EXT = ".sha256";
    private static final String CHECKSUM_BACKUP_EXT = ".sha256.backup";
    private static final Pattern SHA256_PATTERN = Pattern.compile("(?i)\\b[a-f0-9]{64}\\b");
    private static final int HASH_BUFFER_SIZE = 8192;

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private FolderSecurityService() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Ensure directory path exists and is accessible, with optional bdma folder
     * protection.
     * <p>
     * @param dirPath           absolute path to the directory
     * @param protectionEnabled true to lock bdma folder if found, false to unlock
     *                          it
     * @throws IOException if path exists but is not a directory, or directory
     *                     creation fails
     */
    public static void ensureDirAccessible(String dirPath, boolean protectionEnabled) throws IOException {
        Path target = Path.of(dirPath);
        if (Files.exists(target) && !Files.isDirectory(target)) {
            throw new IOException("Path is not a directory: " + dirPath);
        }

        Path bdmaFolder = findDeepestBdmaFolder(target);
        boolean repairEnabled = bdmaFolder != null;

        if (bdmaFolder != null) {
            repairStorageRootIfInaccessible(bdmaFolder);
        }

        // Walk from drive root to target, creating each missing directory.
        Path root = target.getRoot();
        if (root != null) {
            Path current = root;
            for (Path segment : root.relativize(target)) {
                current = current.resolve(segment);
                if (Files.notExists(current)) {
                    createDirectoryWithRepair(current, repairEnabled);
                }
            }
        }

        // Apply protection only if path is inside a bdma folder
        if (bdmaFolder != null && !protectionEnabled) {
            unlockSinglePathWithRepair(bdmaFolder);
        }
        // Unlock any non-bdma parent folders to avoid unintended access issues, but
        // keep the bdma folder protected
        if (!target.equals(bdmaFolder)) {
            unlockSinglePathWithRepair(target);
        }
        if (bdmaFolder != null && protectionEnabled) {
            lockSinglePathWithRepair(bdmaFolder);
        }
    }

    // ── Internal Implementation ──────────────────────────────────────────────

    /**
     * Lock a single directory path by denying delete.
     * <p>
     * @param path the path to lock
     * @throws IOException if lock operations fail
     */
    public static void lockSinglePath(Path path) throws IOException {
        lockSinglePathWithRepair(path);
    }

    private static void lockSinglePathWithRepair(Path path) throws IOException {
        runWithAclRepair(path, () -> lockSinglePathRaw(path));
    }

    private static void lockSinglePathRaw(Path path) throws IOException {
        try {
            grantSharedFolderAccess(path);
            hideSinglePath(path);
            runRequiredCommand(path, "Lock folder",
                    CMD_ICACLS,
                    path.toString(),
                    ICACLS_DENY,
                    WINDOWS_AUTHENTICATED_USERS_SID + ":(D)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Lock interrupted for: " + path, e);
        }
    }

    /**
     * Hide a single path from normal Explorer views without changing ACLs.
     * <p>
     * @param path the path to hide
     * @throws IOException if the hide operation fails
     */
    public static void hideSinglePath(Path path) throws IOException {
        updateFileAttributes(path,
                WinNT.FILE_ATTRIBUTE_HIDDEN | WinNT.FILE_ATTRIBUTE_SYSTEM,
                0,
                "Apply hidden attributes");
    }

    /**
     * Hide the private database backup directory and retry once after granting
     * Everyone inheritable modify access required to create and replace backups.
     * <p>
     * The ACL repair is limited to the configured application database backup
     * directory, does not alter existing descendant ACLs, and does not apply shared
     * storage delete protection.
     *
     * @param path application database backup directory
     * @throws IOException if validation, ACL grant, or the hide operation fails
     */
    public static void hidePrivateAppDirectory(Path path) throws IOException {
        Path privateDirectory = validatePrivateAppDirectory(path);
        try {
            hideSinglePath(privateDirectory);
        } catch (IOException firstFailure) {
            if (!isPermissionFailure(firstFailure)) {
                throw firstFailure;
            }

            try {
                grantEveryonePrivateDirectoryAccess(privateDirectory);
                hideSinglePath(privateDirectory);
            } catch (IOException repairFailure) {
                firstFailure.addSuppressed(repairFailure);
                throw firstFailure;
            }
        }
    }

    /**
     * Unlock a single bdma folder path by removing delete deny ACL from the user.
     * Existing files/subdirectories retain inherited ACL updates from the root.
     */
    public static void unlockSinglePath(Path path) throws IOException {
        unlockSinglePathWithRepair(path);
    }

    private static void unlockSinglePathWithRepair(Path path) throws IOException {
        runWithAclRepair(path, () -> unlockSinglePathRaw(path));
    }

    private static void unlockSinglePathRaw(Path path) throws IOException {
        try {
            grantSharedFolderAccess(path);
            runRequiredCommand(path, "Unlock folder",
                    CMD_ICACLS,
                    path.toString(),
                    ICACLS_REMOVE_DENY,
                    WINDOWS_AUTHENTICATED_USERS_SID);
            runOptionalCommand(path, "Remove legacy user deny ACL",
                    CMD_ICACLS,
                    path.toString(),
                    ICACLS_REMOVE_DENY,
                    buildLegacyCurrentUserTrustee());
            runOptionalCommand(path, "Remove legacy Everyone deny ACL",
                    CMD_ICACLS,
                    path.toString(),
                    ICACLS_REMOVE_DENY,
                    WINDOWS_EVERYONE_SID);
            updateFileAttributes(path,
                    0,
                    WinNT.FILE_ATTRIBUTE_HIDDEN | WinNT.FILE_ATTRIBUTE_SYSTEM,
                    "Remove hidden attributes");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Unlock interrupted for: " + path, e);
        }
    }

    private static void updateFileAttributes(Path path, int attributesToSet,
                                             int attributesToClear, String operation) throws IOException {
        Path absolutePath = path.toAbsolutePath().normalize();
        String nativePath = absolutePath.toString();
        int currentAttributes = readFileAttributes(absolutePath, operation);

        int updatedAttributes = (currentAttributes | attributesToSet) & ~attributesToClear;

        if (updatedAttributes == currentAttributes) {
            return;
        }

        boolean success = Kernel32.INSTANCE.SetFileAttributes(nativePath, new WinDef.DWORD(updatedAttributes));

        if (!success) {
            int errorCode = Native.getLastError();
            throw win32IOException(operation, absolutePath, errorCode);
        }
    }

    private static IOException win32IOException(String operation, Path path, int errorCode) {
        String errorMessage;
        try {
            errorMessage = Kernel32Util.formatMessageFromLastErrorCode(errorCode).trim();
        } catch (RuntimeException e) {
            errorMessage = "Unknown Windows error";
        }
        return new IOException(operation + " failed for: " + path
                + " (Win32 error=" + errorCode + ": " + errorMessage + ")",
                new LastErrorException(errorCode));
    }

    private static int readFileAttributes(Path path, String operation) throws IOException {
        int attributes = Kernel32.INSTANCE.GetFileAttributes(path.toString());
        if (attributes == WinBase.INVALID_FILE_ATTRIBUTES) {
            int errorCode = Native.getLastError();
            throw win32IOException(operation, path, errorCode);
        }
        return attributes;
    }

    private static void grantSharedFolderAccess(Path path) throws IOException, InterruptedException {
        runRequiredCommand(path, "Grant shared folder access",
                CMD_ICACLS,
                path.toString(),
                ICACLS_GRANT_REPLACE,
                WINDOWS_AUTHENTICATED_USERS_SID + ":(OI)(CI)(M,WDAC)");
    }

    private static void grantEveryonePrivateDirectoryAccess(Path path) throws IOException {
        RepairResult result = runPrivateDirectoryAclRepairWithPrompt(path);

        if (result.exitCode() != 0) {
            log.warn("[private-acl-repair] Failed for {} with exit code {}: {}",
                    path, result.exitCode(), result.output());
            throw new IOException(
                    "Elevated private directory ACL repair failed for " + path
                            + " (exit " + result.exitCode() + ")");
        }
    }

    private static Path validatePrivateAppDirectory(Path path) throws IOException {
        if (path == null) {
            throw new IOException("Private application directory path is null");
        }

        Path privateDirectory = path.toAbsolutePath().normalize();
        Path expectedDirectory = AppDataPaths.dataBackupDir().toAbsolutePath().normalize();
        if (!privateDirectory.equals(expectedDirectory)) {
            throw new IOException("Refusing to modify ACL for unrelated private directory: " + privateDirectory);
        }

        validateDirectoryWithoutReparsePoint(Path.of(AppDataPaths.appDir()).toAbsolutePath().normalize());
        validateDirectoryWithoutReparsePoint(privateDirectory);
        return privateDirectory;
    }

    private static void validateDirectoryWithoutReparsePoint(Path path) throws IOException {
        int attributes = readFileAttributes(path, "Validate private application directory");
        if ((attributes & WinNT.FILE_ATTRIBUTE_DIRECTORY) == 0) {
            throw new IOException("Private application path is not a directory: " + path);
        }
        if ((attributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            throw new IOException("Refusing to modify ACL through reparse point: " + path);
        }
    }

    private static boolean isPermissionFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {

            if (current instanceof AccessDeniedException) {
                return true;
            }
            if (current instanceof LastErrorException lastError
                    && lastError.getErrorCode() == WinError.ERROR_ACCESS_DENIED) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("access is denied")
                        || normalized.contains("access denied")
                        || normalized.contains("permission denied")
                        || normalized.contains("exit 5")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Generate checksum sidecar files for a backup file.
     * <p>
     * Creates two files in the same directory as the input file:
     * - {@code <name>.sha256}
     * - {@code <name>.sha256.backup}
     * <p>
     * @param backupPath the backup file path
     * @throws IOException if input file is missing/not a regular file or checksum
     *                     files cannot be written
     */
    public static void generateBackupHashFiles(Path backupPath) throws IOException {
        if (backupPath == null) {
            throw new IOException("Backup file path is null");
        }
        if (!Files.exists(backupPath) || !Files.isRegularFile(backupPath)) {
            throw new IOException("Backup file not found or not a regular file: " + backupPath);
        }

        String checksum = calculateSha256Hex(backupPath);
        String payload = checksum + System.lineSeparator();
        Files.writeString(primaryChecksumPath(backupPath), payload, StandardCharsets.UTF_8);
        Files.writeString(backupChecksumPath(backupPath), payload, StandardCharsets.UTF_8);
    }

    /**
     * Check whether a path is a checksum sidecar file created for a backup file.
     * <p>
     * @param path candidate file path
     * @return true when the filename ends with .sha256 or .sha256.backup
     */
    public static boolean isChecksumSidecarFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(CHECKSUM_EXT) || name.endsWith(CHECKSUM_BACKUP_EXT);
    }

    private static String buildLegacyCurrentUserTrustee() {
        String user = System.getProperty(PROPERTY_USER_NAME);
        String domain = System.getenv("USERDOMAIN");

        return (domain != null && !domain.isBlank())
                ? domain + "\\" + user
                : user;
    }

    /**
     * Find the deepest bdma folder (data or backup) in the given path hierarchy.
     * <p>
     * @param path absolute path to search within
     * @return the deepest bdma folder path, or null if not found
     * @throws IllegalArgumentException if path is not absolute
     */
    private static Path findDeepestBdmaFolder(Path path) {
        // Ensure we're working with an absolute path
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Path must be absolute: " + path);
        }

        Path current = path;
        while (current != null) {
            if (current.getFileName() != null) {
                String name = current.getFileName().toString();
                if (name.equals(AppConstants.SYNC_FOLDER_NAME) || name.equals(AppConstants.BACKUP_FOLDER_NAME)) {
                    return current;
                }
            }
            current = current.getParent();
        }
        return null;
    }

    private static void createDirectoryWithRepair(Path directory, boolean repairEnabled) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (IOException e) {
            if (!repairEnabled || directory.getParent() == null || !isPermissionFailure(e)) {
                throw e;
            }
            repairAclFromNearestAncestor(
                    directory.getParent(),
                    directory,
                    findDeepestBdmaFolder(directory),
                    e);
            Files.createDirectory(directory);
        }
    }

    private static void repairStorageRootIfInaccessible(Path bdmaFolder) throws IOException {
        Path storageRoot = bdmaFolder.getParent();
        if (storageRoot == null || Files.notExists(storageRoot) || Files.isReadable(storageRoot)) {
            return;
        }

        repairAclFromNearestAncestor(
                storageRoot,
                bdmaFolder,
                bdmaFolder,
                new AccessDeniedException(storageRoot.toString()));
        if (!Files.isReadable(storageRoot)) {
            throw new AccessDeniedException(storageRoot.toString());
        }
    }

    private static void runWithAclRepair(Path path, AclOperation operation) throws IOException {
        try {
            operation.run();
        } catch (IOException firstFailure) {
            if (!isPermissionFailure(firstFailure)) {
                throw firstFailure;
            }
            Path protectedFolder = findDeepestBdmaFolder(path);
            if (protectedFolder == null) {
                throw firstFailure;
            }
            Path parent = path.getParent();
            if (parent == null) {
                throw firstFailure;
            }
            Path repairStart = path.equals(protectedFolder) || Files.exists(path) ? path : parent;
            repairAclFromNearestAncestor(repairStart, path, protectedFolder, firstFailure);
            operation.run();
        }
    }

    private static void repairAclFromNearestAncestor(
            Path repairStart,
            Path repairTarget,
            Path protectedFolder,
            IOException firstFailure) throws IOException {
        if (repairStart == null || protectedFolder == null) {
            throw firstFailure;
        }

        Path repairPath = repairStart.toAbsolutePath().normalize();
        Path protectedPath = protectedFolder.toAbsolutePath().normalize();
        Path protectedParent = protectedPath.getParent();
        boolean parentRepair = repairPath.equals(protectedParent)
                && repairTarget.toAbsolutePath().normalize().equals(protectedPath);
        boolean validRepairPath = repairPath.equals(protectedPath)
                || repairPath.startsWith(protectedPath)
                || parentRepair;
        if (!validRepairPath) {
            throw new IOException("Refusing to repair unrelated path: " + repairTarget, firstFailure);
        }

        RepairResult result = runAclRepairWithPrompt(repairPath, !parentRepair);

        if (result.exitCode() != 0) {
            log.warn("[acl-repair] Failed for {} with exit code {}: {}",
                    repairPath, result.exitCode(), result.output());
            throw new IOException(
                    "Elevated ACL repair failed for " + repairPath + " (exit " + result.exitCode() + ")",
                    firstFailure);
        }
    }

    private static RepairResult runAclRepairWithPrompt(Path repairPath, boolean recursiveRepair) throws IOException {
        return runAclRepairWithPrompt(
                repairPath,
                () -> executeAclRepair(repairPath, recursiveRepair),
                "[acl-repair]");
    }

    private static RepairResult runPrivateDirectoryAclRepairWithPrompt(Path repairPath) throws IOException {
        return runAclRepairWithPrompt(
                repairPath,
                () -> executePrivateDirectoryAclRepair(repairPath),
                "[private-acl-repair]");
    }

    private static RepairResult runAclRepairWithPrompt(
            Path repairPath,
            Supplier<RepairResult> repairOperation,
            String logPrefix) throws IOException {
        boolean javaFxAvailable = isJavaFxRuntimeAvailable();
        while (true) {
            if (javaFxAvailable) {
                showAclRepairRequiredNotice(repairPath);
            }

            RepairResult result = javaFxAvailable
                    ? runElevatedRepairWithProgress(repairOperation)
                    : repairOperation.get();
            if (result.exitCode() != ACL_REPAIR_CANCELLED) {
                return result;
            }

            log.info("{} UAC denied for {}; prompting again", logPrefix, repairPath);
        }
    }

    private static boolean isJavaFxRuntimeAvailable() {
        try {
            Platform.runLater(() -> {
            });
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static void showAclRepairRequiredNotice(Path repairPath) throws IOException {
        if (Platform.isFxApplicationThread()) {
            showAclRepairRequiredNoticeNow(repairPath);
            return;
        }

        CompletableFuture<Void> notice = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
                try {
                    showAclRepairRequiredNoticeNow(repairPath);
                    notice.complete(null);
                } catch (RuntimeException e) {
                    notice.completeExceptionally(e);
                }
            });
            notice.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ACL repair notice interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Unable to show ACL repair notice", e.getCause());
        }
    }

    private static void showAclRepairRequiredNoticeNow(Path repairPath) {
        Alert alert = AlertHelper.createInformation(
                I18n.get("storage.permission.repair.title"),
                I18n.get("storage.permission.repair.header"),
                I18n.get("storage.permission.repair.content"));

        alert.setOnShowing(event -> {
            var scene = alert.getDialogPane().getScene();
            if (scene != null && scene.getWindow() != null) {
                scene.getWindow().addEventFilter(
                        WindowEvent.WINDOW_CLOSE_REQUEST, closeEvent -> closeEvent.consume());
            }
        });
        alert.showAndWait();
    }

    private static RepairResult runElevatedRepairWithProgress(Supplier<RepairResult> repairOperation)
            throws IOException {
        if (Platform.isFxApplicationThread()) {
            Alert progress = createAclRepairProgressAlert();
            CompletableFuture<RepairResult> repair = CompletableFuture.supplyAsync(repairOperation);
            repair.whenComplete((result, error) -> dismissAclRepairProgress(progress));
            progress.showAndWait();
            return repair.join();
        }

        CompletableFuture<Alert> progressFuture = new CompletableFuture<>();
        AtomicBoolean progressAbandoned = new AtomicBoolean();
        Platform.runLater(() -> {
            if (progressAbandoned.get()) {
                return;
            }
            try {
                Alert progress = createAclRepairProgressAlert();
                progress.show();
                if (!progressFuture.complete(progress)) {
                    dismissAclRepairProgress(progress);
                }
            } catch (Throwable e) {
                progressFuture.completeExceptionally(e);
            }
        });

        Alert progress;
        try {
            progress = progressFuture.get();
        } catch (InterruptedException e) {
            progressAbandoned.set(true);
            progressFuture.thenAccept(FolderSecurityService::dismissAclRepairProgress);
            Thread.currentThread().interrupt();
            throw new IOException("ACL repair progress interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Unable to show ACL repair progress", e.getCause());
        }

        RepairResult result;
        boolean progressDismissed;
        try {
            result = repairOperation.get();
        } finally {
            progressDismissed = dismissAclRepairProgress(progress);
        }
        if (!progressDismissed) {
            return new RepairResult(ACL_REPAIR_PROGRESS_FAILED, "ACL repair progress dialog did not close");
        }
        return result;
    }

    private static Alert createAclRepairProgressAlert() {
        Alert progress = AlertHelper.createInformation(
                I18n.get("storage.permission.repair.progress.title"),
                I18n.get("storage.permission.repair.progress.header"),
                I18n.get("storage.permission.repair.progress.content"));
        var progressButton = progress.getDialogPane().lookupButton(ButtonType.OK);
        progressButton.setVisible(false);
        progressButton.setManaged(false);
        return progress;
    }

    private static boolean dismissAclRepairProgress(Alert progress) {
        if (Platform.isFxApplicationThread()) {
            return closeAclRepairProgress(progress);
        }

        CompletableFuture<Boolean> dismissed = new CompletableFuture<>();
        try {
            Platform.runLater(() -> dismissed.complete(closeAclRepairProgress(progress)));
            return dismissed.get(ACL_REPAIR_DIALOG_DISMISS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[acl-repair] Progress dismissal interrupted");
        } catch (ExecutionException | TimeoutException | IllegalStateException e) {
            log.warn("[acl-repair] Could not dismiss progress dialog: {}", e.getMessage());
        }
        return false;
    }

    private static boolean closeAclRepairProgress(Alert progress) {
        try {
            progress.setResult(ButtonType.OK);
            progress.hide();
        } catch (RuntimeException e) {
            log.warn("[acl-repair] Alert hide failed: {}", e.getMessage());
        }

        if (progress.isShowing()
                && progress.getDialogPane().getScene() != null
                && progress.getDialogPane().getScene().getWindow() != null) {
            progress.getDialogPane().getScene().getWindow().hide();
        }
        return !progress.isShowing();
    }

    private static RepairResult executeAclRepair(Path repairPath, boolean recursiveRepair) {
        log.info("[acl-repair] Starting elevated repair for {}", repairPath);
        try {
            CommandResult result = WINDOWS_COMMAND_SERVICE.runElevatedPowerShell(
                    buildAclRepairScript(repairPath, recursiveRepair));
            if (result.exitCode() == 0) {
                log.info("[acl-repair] Elevated repair succeeded for {}", repairPath);
            } else if (result.exitCode() == ACL_REPAIR_CANCELLED) {
                log.info("[acl-repair] UAC denied for {}", repairPath);
            } else {
                log.warn("[acl-repair] Elevated repair failed for {} with exit code {}: {}",
                        repairPath, result.exitCode(), result.output());
            }
            return new RepairResult(result.exitCode(), result.output());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[acl-repair] Elevated repair interrupted for {}", repairPath);
            return new RepairResult(ACL_REPAIR_INTERRUPTED, "ACL repair interrupted");
        } catch (IOException e) {
            log.warn("[acl-repair] Could not start elevated repair for {}: {}", repairPath, e.getMessage());
            return new RepairResult(ACL_REPAIR_ACL_FAILED, e.getMessage());
        }
    }

    private static RepairResult executePrivateDirectoryAclRepair(Path repairPath) {
        log.info("[private-acl-repair] Starting elevated repair for {}", repairPath);
        try {
            CommandResult result = WINDOWS_COMMAND_SERVICE.runElevatedPowerShell(
                    buildPrivateDirectoryAclRepairScript(repairPath));
            if (result.exitCode() == 0) {
                log.info("[private-acl-repair] Elevated repair succeeded for {}", repairPath);
            } else if (result.exitCode() == ACL_REPAIR_CANCELLED) {
                log.info("[private-acl-repair] UAC denied for {}", repairPath);
            } else {
                log.warn("[private-acl-repair] Elevated repair failed for {} with exit code {}: {}",
                        repairPath, result.exitCode(), result.output());
            }
            return new RepairResult(result.exitCode(), result.output());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[private-acl-repair] Elevated repair interrupted for {}", repairPath);
            return new RepairResult(ACL_REPAIR_INTERRUPTED, "Private ACL repair interrupted");
        } catch (IOException e) {
            log.warn("[private-acl-repair] Could not start elevated repair for {}: {}",
                    repairPath, e.getMessage());
            return new RepairResult(ACL_REPAIR_ACL_FAILED, e.getMessage());
        }
    }

    private static String buildPrivateDirectoryAclRepairScript(Path repairPath) {
        String target = repairPath.toAbsolutePath().normalize().toString().replace("'", "''");
        String appRoot = Path.of(AppDataPaths.appDir()).toAbsolutePath().normalize().toString()
                .replace("'", "''");
        return "$ErrorActionPreference = 'Stop'; "
                + "$target = '" + target + "'; "
                + "$appRoot = '" + appRoot + "'; "
                + "$expected = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($appRoot, 'db_backup')); "
                + "$actual = [System.IO.Path]::GetFullPath($target); "
                + "try { "
                + "if ($actual -ne $expected) { exit " + ACL_REPAIR_INVALID_PATH + " }; "
                + "foreach ($candidate in @($appRoot, $target)) { "
                + "$item = Get-Item -LiteralPath $candidate -Force; "
                + "if (-not $item.PSIsContainer) { exit " + ACL_REPAIR_INVALID_PATH + " }; "
                + "if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { exit "
                + ACL_REPAIR_REPARSE_POINT + " }; "
                + "}; "
                + "& icacls.exe $target '/grant' '" + WINDOWS_EVERYONE_SID + ":"
                + ICACLS_PRIVATE_DIRECTORY_ACCESS + "' | Out-Null; "
                + "if ($LASTEXITCODE -ne 0) { "
                + "& takeown.exe /F $target | Out-Null; "
                + "if ($LASTEXITCODE -ne 0) { exit " + ACL_REPAIR_OWNERSHIP_FAILED + " }; "
                + "& icacls.exe $target '/grant' '" + WINDOWS_EVERYONE_SID + ":"
                + ICACLS_PRIVATE_DIRECTORY_ACCESS + "' | Out-Null; "
                + "if ($LASTEXITCODE -ne 0) { exit " + ACL_REPAIR_ACL_FAILED + " } "
                + "}; "

                + "exit 0; "
                + "} catch { exit " + ACL_REPAIR_SCRIPT_FAILED + " }";
    }
    private static String buildAclRepairScript(Path repairPath, boolean recursiveRepair) {
        String target = repairPath.toString().replace("'", "''");
        String takeownScope = recursiveRepair ? " /R /D Y" : "";
        String icaclsScope = recursiveRepair ? " '/T' '/C'" : "";
        String attribScope = recursiveRepair ? " '/S' '/D'" : "";
        String sharedGrant = recursiveRepair ? "*S-1-5-11:(OI)(CI)(M,WDAC)" : "*S-1-5-11:(M,WDAC)";
        return "$ErrorActionPreference = 'Stop'; "
                + "$watchdog = Start-Job -ScriptBlock { "
                + "param($processId); "
                + "Start-Sleep -Seconds 290; "
                + "& taskkill.exe '/PID' $processId '/T' '/F' | Out-Null "
                + "} -ArgumentList $PID; "
                + "try { "
                + "$target = '" + target + "'; "
                + "if (-not (Test-Path -LiteralPath $target -PathType Container)) { exit "
                + ACL_REPAIR_INVALID_PATH + " }; "
                + "$cursor = $target; "
                + "while ($cursor) { "
                + "$cursorItem = Get-Item -LiteralPath $cursor -Force; "
                + "if (($cursorItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { exit "
                + ACL_REPAIR_REPARSE_POINT + " }; "
                + "$parentItem = $cursorItem.Parent; "
                + "if ($null -eq $parentItem) { break }; "
                + "$parent = $parentItem.FullName; "
                + "if ([string]::IsNullOrEmpty($parent) -or $parent -eq $cursor) { break }; "
                + "$cursor = $parent; "
                + "}; "
                + (recursiveRepair
                        ? "if (Get-ChildItem -LiteralPath $target -Force -Recurse -ErrorAction SilentlyContinue | Where-Object { ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 }) { exit "
                                + ACL_REPAIR_REPARSE_POINT + " }; "
                        : "")
                + "& icacls.exe $target '/reset'" + icaclsScope + " | Out-Null; "
                + "if ($LASTEXITCODE -ne 0) { "
                + "& takeown.exe /F $target" + takeownScope + " | Out-Null; "
                + "if ($LASTEXITCODE -ne 0) { exit " + ACL_REPAIR_OWNERSHIP_FAILED + " }; "
                + "& icacls.exe $target '/reset'" + icaclsScope + " | Out-Null; "
                + "if ($LASTEXITCODE -ne 0) { exit " + ACL_REPAIR_ACL_FAILED + " } "
                + "}; "
                + "& icacls.exe $target '/grant:r' '" + sharedGrant + "'" + icaclsScope + " | Out-Null; "
                + "if ($LASTEXITCODE -ne 0) { exit " + ACL_REPAIR_ACL_FAILED + " }; "
                + "& icacls.exe $target '/remove:d' '*S-1-5-11'" + icaclsScope + " | Out-Null; "
                + "if ($LASTEXITCODE -ne 0) { exit " + ACL_REPAIR_ACL_FAILED + " }; "
                + "& icacls.exe $target '/remove:d' '*S-1-1-0'" + icaclsScope + " | Out-Null; "
                + "if ($LASTEXITCODE -ne 0) { exit " + ACL_REPAIR_ACL_FAILED + " }; "
                + "& attrib.exe '-h' '-s' $target" + attribScope + " | Out-Null; "
                + "if ($LASTEXITCODE -ne 0) { exit " + ACL_REPAIR_ACL_FAILED + " }; "
                + "try { "
                + "$acl = Get-Acl -LiteralPath $target; "
                + "$hasAclControl = $false; "
                + "foreach ($rule in $acl.Access) { "
                + "try { $sid = $rule.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value } catch { continue }; "
                + "if ($sid -eq 'S-1-5-11' -and (($rule.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::ChangePermissions) -ne 0)) { $hasAclControl = $true; break } "
                + "}; "
                + "if (-not $hasAclControl) { exit " + ACL_REPAIR_VERIFICATION_FAILED + " }; "
                + "$hasDeny = $acl.Access | Where-Object { "
                + "try { $sid = $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value } catch { $sid = '' }; "
                + "($sid -eq 'S-1-5-11' -or $sid -eq 'S-1-1-0') -and $_.AccessControlType -eq 'Deny' "
                + "}; "
                + "if ($hasDeny) { exit " + ACL_REPAIR_VERIFICATION_FAILED + " } "
                + "} catch { exit " + ACL_REPAIR_VERIFICATION_FAILED + " }; "
                + "exit 0; "
                + "} catch { "
                + "exit " + ACL_REPAIR_SCRIPT_FAILED + " "
                + "} finally { "
                + "Stop-Job -Job $watchdog -ErrorAction SilentlyContinue; "
                + "Remove-Job -Job $watchdog -Force -ErrorAction SilentlyContinue "
                + "}";
    }


    private record RepairResult(int exitCode, String output) {
    }

    /**
     * Execute a system command and throw when it exits unsuccessfully.
     * <p>
     * @param path    path used for diagnostics
     * @param action  action name used for diagnostics
     * @param command command and arguments to execute
     * @throws IOException          if command execution fails
     * @throws InterruptedException if command is interrupted
     */
    private static void runRequiredCommand(Path path, String action, String... command)
            throws IOException, InterruptedException {
        CommandResult result = runCommand(command);
        if (result.exitCode() != 0) {
            log.warn("[cmd] {} failed with exit code {} for {}: {}",
                    action, result.exitCode(), path, result.output());
            throw new IOException(action + " failed for " + path + " (exit " + result.exitCode() + "): "
                    + result.output());
        }
    }

    private static void runOptionalCommand(Path path, String action, String... command)
            throws IOException, InterruptedException {
        CommandResult result = runCommand(command);
        if (result.exitCode() != 0 && log.isDebugEnabled()) {
            log.debug("[cmd] Optional operation '{}' failed for {} (exit {}): {}",
                    action, path, result.exitCode(), result.output());
        }
    }

    private static CommandResult runCommand(String... command) throws IOException, InterruptedException {
        return WINDOWS_COMMAND_SERVICE.runCmdWithOutput(null, command);
    }

    @FunctionalInterface
    private interface AclOperation {
        void run() throws IOException;
    }


    /**
     * Validate backup file integrity using SHA-256 sidecar files.
     * <p>
     * Validation order:
     * 1) Compare file hash with {@code <name>.sha256}
     * 2) If mismatch/missing, compare with {@code <name>.sha256.backup}
     * 3) Return false if neither sidecar matches
     * <p>
     * @param backupPath backup file path
     * @return true when checksum matches primary or backup sidecar, otherwise false
     */
    public static boolean isValidBackupFile(Path backupPath) {
        try {
            if (backupPath == null || !Files.exists(backupPath) || !Files.isRegularFile(backupPath)) {
                return false;
            }

            String actualChecksum = calculateSha256Hex(backupPath);
            String expectedPrimary = readChecksumValue(primaryChecksumPath(backupPath));
            String expectedBackup = readChecksumValue(backupChecksumPath(backupPath));

            boolean primaryValid = actualChecksum.equalsIgnoreCase(expectedPrimary);
            boolean backupValid = actualChecksum.equalsIgnoreCase(expectedBackup);

            // If at least one hash file is valid, regenerate both to ensure consistency
            if (primaryValid || backupValid) {
                if (!primaryValid || !backupValid) {
                    log.info("[checksum] Regenerating hash files for consistency: {}", backupPath);
                    generateBackupHashFiles(backupPath);
                }
                return true;
            }

            return false;
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("[checksum] Failed to validate backup file {}: {}", backupPath, e.getMessage());
            }
            return false;
        }
    }

    public static Path primaryChecksumPath(Path backupPath) {
        return backupPath.resolveSibling(backupPath.getFileName().toString() + CHECKSUM_EXT);
    }

    public static Path backupChecksumPath(Path backupPath) {
        return backupPath.resolveSibling(backupPath.getFileName().toString() + CHECKSUM_BACKUP_EXT);
    }

    private static String calculateSha256Hex(Path filePath) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }

        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(filePath)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static String readChecksumValue(Path checksumPath) throws IOException {
        if (!Files.exists(checksumPath) || !Files.isRegularFile(checksumPath)) {
            return null;
        }

        String content = Files.readString(checksumPath, StandardCharsets.UTF_8);
        Matcher matcher = SHA256_PATTERN.matcher(content);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : null;
    }
}
