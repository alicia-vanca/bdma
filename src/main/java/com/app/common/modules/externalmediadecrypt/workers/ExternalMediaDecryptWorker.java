package com.app.common.modules.externalmediadecrypt.workers;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;
import com.app.common.exceptions.DiskFullException;
import com.app.common.exceptions.DriveUnavailableException;
import com.app.common.modules.crypto.constants.CryptoConstants;
import com.app.common.modules.crypto.exceptions.CryptoException;
import com.app.common.modules.crypto.helpers.KeyDerivationHelper;
import com.app.common.modules.crypto.helpers.KeyMaterialHelper;
import com.app.common.modules.crypto.services.AesCtrCryptoService;
import com.app.common.modules.externalmediadecrypt.callbacks.ProgressCallback;
import com.app.common.modules.externalmediadecrypt.services.ExternalMediaDecryptService.DecryptionResult;
import com.app.common.modules.foldermanager.events.StorageUnavailableEvent;
import com.app.common.modules.i18n.I18n;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * JavaFX task that owns one external-media decrypt run.
 *
 * <p>
 * The service owns lifecycle and single-task guarding; this worker owns file
 * processing, progress reporting, storage failure mapping, and temp cleanup.
 */
public class ExternalMediaDecryptWorker extends Task<List<DecryptionResult>> {

    private static final Logger log = LoggerFactory.getLogger(ExternalMediaDecryptWorker.class);
    private static final String ERROR_OUTPUT_DRIVE_UNAVAILABLE = "externalMediaDecrypt.error.outputDriveUnavailable";
    private static final String ERROR_OUTPUT_DISK_FULL = "externalMediaDecrypt.error.outputDiskFull";
    private static final String ERROR_DECRYPT_FAILED = "externalMediaDecrypt.error.decryptFailed";
    private static final String ERROR_FILE_UNAVAILABLE = "externalMediaDecrypt.error.fileUnavailable";
    private static final String ERROR_INVALID_CONFIGURATION = "externalMediaDecrypt.error.invalidConfiguration";
    private static final String ERROR_CANCELLED = "externalMediaDecrypt.error.cancelled";
    private static final String ERROR_INVALID_FILE_FORMAT = "externalMediaDecrypt.invalid.fileFormat";

    private final AesCtrCryptoService aesCtrCryptoService;
    private final ApplicationEventPublisher eventPublisher;
    private final String password;
    private final List<Path> inputFilePaths;
    private Path outputFolderPath;
    private final ProgressStateUpdater progressStateUpdater;
    private final WorkerLifecycleHooks lifecycleHooks;
    private final List<DecryptionResult> results = new ArrayList<>();
    private final Object resultsLock = new Object();
    private final Object storageRecoveryLock = new Object();

    private volatile boolean storageRecoveryDeferred;
    private volatile boolean storageRecoveryStopped;
    private volatile boolean cancellationRequested;
    private final AtomicReference<Path> recoveredOutputFolderPath = new AtomicReference<>();
    private Path tempOutputFilePath;

    public ExternalMediaDecryptWorker(
            AesCtrCryptoService aesCtrCryptoService,
            ApplicationEventPublisher eventPublisher,
            String password,
            List<Path> inputFilePaths,
            Path outputFolderPath,
            ProgressStateUpdater progressStateUpdater,
            WorkerLifecycleHooks lifecycleHooks) {
        this.aesCtrCryptoService = aesCtrCryptoService;
        this.eventPublisher = eventPublisher;
        this.password = password;
        this.inputFilePaths = List.copyOf(inputFilePaths);
        this.outputFolderPath = outputFolderPath;
        this.progressStateUpdater = progressStateUpdater;
        this.lifecycleHooks = lifecycleHooks;
    }

    public record ProgressStateUpdater(
            Consumer<String> currentFileNameUpdater,
            Consumer<String> currentFileCountUpdater,
            Consumer<Path> currentOutputFolderPathUpdater) {
    }

    public record WorkerLifecycleHooks(
            BooleanSupplier shutdownRequested,
            Consumer<Task<List<DecryptionResult>>> finishedCallback) {
    }

    @Override
    protected List<DecryptionResult> call() throws Exception {
        try {
            storageRecoveryStopped = false;
            updateProgress(0, 100);
            updateMessage("0%");
            progressStateUpdater.currentFileCountUpdater().accept("0 / " + inputFilePaths.size());
            progressStateUpdater.currentOutputFolderPathUpdater().accept(outputFolderPath);
            AtomicLong totalBytesAllFiles = calculateTotalBytes(inputFilePaths);
            if (!validateOutputFolderWithRecovery(inputFilePaths.size())) {
                return results;
            }
            boolean singleFile = inputFilePaths.size() == 1;
            AtomicLong processedBytesGlobal = new AtomicLong(0);
            for (int index = 0; index < inputFilePaths.size(); index++) {
                progressStateUpdater.currentFileCountUpdater().accept((index + 1) + " / " + inputFilePaths.size());
                int resultCountBefore = results.size();
                processFile(inputFilePaths.get(index), index, singleFile, processedBytesGlobal, totalBytesAllFiles);
                if (storageRecoveryStopped) {
                    return results;
                }
                if (isStopRequested()) {
                    int firstUnfinishedIndex = results.size() == resultCountBefore ? index : index + 1;
                    failPendingFilesFrom(firstUnfinishedIndex, I18n.get(ERROR_CANCELLED));
                    return results;
                }
            }
            updateProgress(1, 1);
            updateMessage("100%");
            return results;
        } finally {
            cleanupIncompleteFile(tempOutputFilePath);
            lifecycleHooks.finishedCallback().accept(this);
        }
    }

    private void processFile(
            Path inputFilePath,
            int fileIndex,
            boolean singleFile,
            AtomicLong processedBytesGlobal,
            AtomicLong totalBytesAllFiles) throws IOException {
        FileProcessContext context = createFileProcessContext(
                inputFilePath, fileIndex, singleFile, processedBytesGlobal, totalBytesAllFiles);

        while (!isStopRequested()) {
            tempOutputFilePath = outputFolderPath.resolve(context.tempFileName());
            try {
                processFileAttempt(context);
                return;
            } catch (Exception e) {
                if (handleFileAttemptFailure(context, e)) {
                    continue;
                }
                return;
            }
        }
    }

    private FileProcessContext createFileProcessContext(
            Path inputFilePath,
            int fileIndex,
            boolean singleFile,
            AtomicLong processedBytesGlobal,
            AtomicLong totalBytesAllFiles) throws IOException {
        String currentName = getFileName(inputFilePath.toString());
        String decryptedFileName = getDecryptedFileName(inputFilePath.toString());
        long fileSize = Files.size(inputFilePath);
        AtomicLong completedBeforeCurrentFile = new AtomicLong(processedBytesGlobal.get());
        ProgressCallback progressCallback = (processedBytes, totalBytes) -> {
            ProgressInfo info = calculateProgress(singleFile, processedBytes, totalBytes,
                    completedBeforeCurrentFile, totalBytesAllFiles);
            updateProgress(info.current(), info.total());
            updateMessage(info.percent() + "%");
        };
        progressStateUpdater.currentFileNameUpdater().accept(currentName);
        return new FileProcessContext(
                inputFilePath,
                currentName,
                decryptedFileName,
                decryptedFileName + AppConstants.TMP_EXTENSION,
                fileIndex,
                fileSize,
                processedBytesGlobal,
                progressCallback);
    }

    private void processFileAttempt(FileProcessContext context) throws IOException {
        ensureOutputFolderWritable(outputFolderPath, context.fileSize());
        if (looksLikeMp4(context.inputFilePath())) {
            Path unencryptedOutputPath = outputFolderPath.resolve(stripEncryptedSuffix(context.currentName()));
            processAlreadyVideoFile(context, unencryptedOutputPath);
            return;
        }
        Path decryptedOutputPath = outputFolderPath.resolve(context.decryptedFileName());
        processEncryptedVideoFile(context, decryptedOutputPath);
    }

    private void processAlreadyVideoFile(FileProcessContext context, Path outputPath) throws IOException {
        if (!copyAlreadyVideoFile(context.inputFilePath(), tempOutputFilePath, outputPath, context.currentName(),
                context.fileIndex(), context.progressCallback())) {
            cleanupIncompleteFile(tempOutputFilePath);
        }
        context.processedBytesGlobal().addAndGet(context.fileSize());
    }

    private void processEncryptedVideoFile(FileProcessContext context, Path outputPath) throws IOException {
        log.info("Input file is not a readable MP4. Trying decryption: {}", context.inputFilePath());
        decryptFile(context.inputFilePath(), tempOutputFilePath, configuredPassword(), context.progressCallback(),
                this::isStopRequested);
        if (isStopRequested()) {
            cleanupIncompleteFile(tempOutputFilePath);
            context.processedBytesGlobal().addAndGet(context.fileSize());
            return;
        }
        validateMp4File(tempOutputFilePath);
        Path resolvedOutputPath = resolveOutputPathForContent(outputPath, tempOutputFilePath);
        if (resolvedOutputPath == null) {
            logFileOutcome("Skipped, decrypted output already exists", context.fileIndex(), context.inputFilePath());
            cleanupIncompleteFile(tempOutputFilePath);
            addResult(new DecryptionResult(context.currentName(), context.inputFilePath(), true, null));
            tempOutputFilePath = null;
            context.processedBytesGlobal().addAndGet(context.fileSize());
            return;
        }
        Files.move(tempOutputFilePath, resolvedOutputPath, StandardCopyOption.REPLACE_EXISTING);
        logFileOutcome("Decrypted", context.fileIndex(), context.inputFilePath());
        addResult(new DecryptionResult(context.currentName(), context.inputFilePath(), true, null));
        tempOutputFilePath = null;
        context.processedBytesGlobal().addAndGet(context.fileSize());
    }

    private boolean handleFileAttemptFailure(FileProcessContext context, Exception e) {
        IOException storageException = findStorageException(e);
        if (storageException != null) {
            return handleStorageFailure(context, storageException, e);
        }
        if (isStopRequested()) {
            cleanupIncompleteFile(tempOutputFilePath);
            context.processedBytesGlobal().addAndGet(context.fileSize());
            return false;
        }
        String errorMessage = getErrorMessage(e);
        logFileOutcome("Decrypt failed", context.fileIndex(), context.inputFilePath());
        handleFileError(context.currentName(), context.inputFilePath(), errorMessage, e, tempOutputFilePath);
        context.processedBytesGlobal().addAndGet(context.fileSize());
        return false;
    }

    private boolean handleStorageFailure(FileProcessContext context, IOException storageException, Exception original) {
        if (pauseForStorageRecovery(storageException, outputFolderPath, context.fileSize(),
                inputFilePaths.size() - context.fileIndex())) {
            cleanupIncompleteFile(tempOutputFilePath);
            tempOutputFilePath = null;
            return true;
        }
        if (!isStopRequested()) {
            String errorMessage = storageErrorMessage(storageException);
            handleFileError(context.currentName(), context.inputFilePath(), errorMessage, original, tempOutputFilePath);
            failPendingFilesFrom(context.fileIndex() + 1, errorMessage);
            storageRecoveryStopped = true;
        }
        context.processedBytesGlobal().addAndGet(context.fileSize());
        return false;
    }

    private void decryptFile(
            Path encryptedFile,
            Path outputFile,
            String password,
            ProgressCallback progressCallback,
            BooleanSupplier isCancelled) {

        byte[] key = null;
        byte[] iv = null;

        try {
            key = KeyDerivationHelper.sha256Password(password);
            iv = CryptoConstants.fixedZeroIv();
            aesCtrCryptoService.decrypt(encryptedFile, outputFile, key, iv, progressCallback, isCancelled);
        } finally {
            if (key != null) {
                KeyMaterialHelper.clear(key);
            }
            if (iv != null) {
                KeyMaterialHelper.clear(iv);
            }
        }
    }

    private String configuredPassword() {
        if (password == null || password.isBlank()) {
            throw new CryptoException(I18n.get(ERROR_INVALID_CONFIGURATION));
        }
        return password;
    }

    private boolean validateOutputFolderWithRecovery(int remainingCount) {
        while (!isStopRequested()) {
            try {
                ensureOutputFolderWritable(outputFolderPath, 0L);
                return true;
            } catch (IOException e) {
                IOException storageException = findStorageException(e);
                if (storageException == null) {
                    failPendingFiles(getErrorMessage(e));
                    return false;
                }
                if (!pauseForStorageRecovery(storageException, outputFolderPath, 0L, remainingCount)) {
                    failPendingFiles(storageErrorMessage(storageException));
                    return false;
                }
            }
        }
        return false;
    }

    private void failPendingFiles(String errorMessage) {
        failPendingFilesFrom(0, errorMessage);
    }

    private void failPendingFilesFrom(int startIndex, String errorMessage) {
        synchronized (resultsLock) {
            int firstUnfinishedIndex = Math.max(Math.max(startIndex, 0), results.size());
            for (int index = firstUnfinishedIndex; index < inputFilePaths.size(); index++) {
                Path inputFilePath = inputFilePaths.get(index);
                results.add(new DecryptionResult(getFileName(inputFilePath.toString()), inputFilePath, false,
                        errorMessage));
            }
        }
    }

    public List<DecryptionResult> getResultsSnapshot() {
        synchronized (resultsLock) {
            return List.copyOf(results);
        }
    }

    public void requestCancellation() {
        cancellationRequested = true;
        failPendingFilesFrom(0, I18n.get(ERROR_CANCELLED));
        synchronized (storageRecoveryLock) {
            storageRecoveryLock.notifyAll();
        }
    }

    private void addResult(DecryptionResult result) {
        synchronized (resultsLock) {
            boolean resultAlreadyRecorded = results.stream()
                    .anyMatch(existing -> existing.inputFilePath().equals(result.inputFilePath()));
            if (!resultAlreadyRecorded) {
                results.add(result);
            }
        }
    }

    private boolean isStopRequested() {
        return cancellationRequested || isCancelled() || lifecycleHooks.shutdownRequested().getAsBoolean();
    }

    private boolean pauseForStorageRecovery(IOException storageException, Path failingDir, long requiredBytes,
            int remainingCount) {
        cleanupIncompleteFile(tempOutputFilePath);
        tempOutputFilePath = null;
        synchronized (storageRecoveryLock) {
            storageRecoveryDeferred = false;
            recoveredOutputFolderPath.set(null);
        }

        StorageIssueReason reason = storageIssueReason(storageException);
        log.warn("Decrypt output folder unavailable. reason={} path={} remaining={}", reason, failingDir,
                remainingCount);
        eventPublisher.publishEvent(new StorageUnavailableEvent(
                FolderType.DECRYPT,
                reason,
                requiredBytes,
                failingDir,
                remainingCount));

        Path recoveredPath = waitForStorageRecovery();
        if (recoveredPath == null) {
            return false;
        }
        outputFolderPath = recoveredPath;
        progressStateUpdater.currentOutputFolderPathUpdater().accept(outputFolderPath);
        return true;
    }

    private Path waitForStorageRecovery() {
        synchronized (storageRecoveryLock) {
            while (recoveredOutputFolderPath.get() == null && !storageRecoveryDeferred && !isStopRequested()) {
                try {
                    storageRecoveryLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return storageRecoveryDeferred || isStopRequested() ? null : recoveredOutputFolderPath.get();
        }
    }

    public void notifyStorageRestored(Path configuredOutputFolderPath) {
        synchronized (storageRecoveryLock) {
            if (configuredOutputFolderPath == null) {
                storageRecoveryDeferred = true;
            } else {
                recoveredOutputFolderPath.set(configuredOutputFolderPath);
            }
            storageRecoveryLock.notifyAll();
        }
    }

    public void notifyStorageRecoveryDeferred() {
        synchronized (storageRecoveryLock) {
            storageRecoveryDeferred = true;
            storageRecoveryLock.notifyAll();
        }
    }

    private StorageIssueReason storageIssueReason(IOException storageException) {
        return storageException instanceof DiskFullException
                ? StorageIssueReason.LOW_SPACE
                : StorageIssueReason.DRIVE_UNAVAILABLE;
    }

    private void ensureOutputFolderWritable(Path outputFolderPath, long requiredBytes) throws IOException {
        try {
            Files.createDirectories(outputFolderPath);
        } catch (IOException e) {
            rethrowAsStorageException(e);
            throw e;
        }

        if (!Files.isDirectory(outputFolderPath)) {
            throw new DriveUnavailableException("Output path is not a directory: " + outputFolderPath);
        }

        long usableSpace = outputFolderPath.toFile().getUsableSpace();
        if (usableSpace > 0 && usableSpace < requiredBytes) {
            throw new DiskFullException("Output folder does not have enough free space");
        }
    }

    private static String getDecryptedFileName(String inputFilePath) {
        if (inputFilePath == null || inputFilePath.isBlank()) {
            return null;
        }
        File file = new File(inputFilePath);
        return buildDecryptedFileName(file.getName());
    }

    private static String getFileName(String inputFilePath) {
        if (inputFilePath == null || inputFilePath.isBlank()) {
            return null;
        }
        File file = new File(inputFilePath);
        return file.getName();
    }

    private static String stripEncryptedSuffix(String fileName) {
        return buildOutputFileName(fileName, false);
    }

    private static String buildDecryptedFileName(String fileName) {
        return buildOutputFileName(fileName, true);
    }

    private static String buildOutputFileName(String fileName, boolean appendDecryptedSuffix) {
        if (fileName == null || fileName.isBlank()) {
            return fileName;
        }

        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        String extension = extensionIndex > 0 ? fileName.substring(extensionIndex) : "";
        if (baseName.endsWith("_enc")) {
            baseName = baseName.substring(0, baseName.length() - "_enc".length());
        }
        if (appendDecryptedSuffix) {
            baseName = baseName + "_decrypted";
        }
        return baseName + extension;
    }

    private static boolean looksLikeMp4(Path file) {
        try {
            if (!Files.exists(file) || Files.size(file) < 32) {
                return false;
            }

            return hasRequiredMp4Boxes(file);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasRequiredMp4Boxes(Path file) throws IOException {
        long fileSize = Files.size(file);
        Mp4ScanState state = new Mp4ScanState();

        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long position = 0L;
            while (position <= fileSize - 8) {
                Mp4Box box = readMp4Box(channel, position, fileSize);
                if (box == null) {
                    return false;
                }

                updateMp4ScanState(channel, box, position, state);
                position = box.start() + box.size();
            }
        }

        return state.isComplete();
    }

    private static void updateMp4ScanState(
            SeekableByteChannel channel,
            Mp4Box box,
            long position,
            Mp4ScanState state) throws IOException {
        if ("ftyp".equals(box.type())) {
            state.hasFtyp = position == 0L && hasValidFtypPayload(channel, box);
        } else if ("moov".equals(box.type())) {
            state.hasMoov = true;
            state.hasVideoTrack = containsVideoHandler(channel, box);
        } else if ("mdat".equals(box.type())) {
            state.hasMdat = true;
        }
    }

    private static boolean hasValidFtypPayload(SeekableByteChannel channel, Mp4Box ftypBox) throws IOException {
        long payloadSize = ftypBox.payloadSize();
        if (payloadSize < 8) {
            return false;
        }

        byte[] brandBytes = new byte[4];
        ByteBuffer buffer = ByteBuffer.wrap(brandBytes);
        channel.position(ftypBox.payloadStart());
        if (channel.read(buffer) != brandBytes.length) {
            return false;
        }

        String brand = new String(brandBytes, StandardCharsets.US_ASCII);
        return !brand.trim().isEmpty();
    }

    private static boolean containsVideoHandler(SeekableByteChannel channel, Mp4Box containerBox) throws IOException {
        long position = containerBox.payloadStart();
        long end = containerBox.start() + containerBox.size();
        while (position <= end - 8) {
            Mp4Box box = readMp4Box(channel, position, end);
            if (box == null) {
                return false;
            }

            if ("hdlr".equals(box.type())) {
                if (isVideoHandler(channel, box)) {
                    return true;
                }
            } else if (isMp4ContainerBox(box.type()) && containsVideoHandler(channel, box)) {
                return true;
            }

            if (box.size() <= 0 || box.start() + box.size() > end) {
                return false;
            }
            position = box.start() + box.size();
        }
        return false;
    }

    private static boolean isVideoHandler(SeekableByteChannel channel, Mp4Box hdlrBox) throws IOException {
        if (hdlrBox.payloadSize() < 12) {
            return false;
        }

        ByteBuffer buffer = ByteBuffer.allocate(12);
        channel.position(hdlrBox.payloadStart());
        if (channel.read(buffer) != buffer.capacity()) {
            return false;
        }

        byte[] handlerType = new byte[4];
        buffer.flip();
        buffer.position(8);
        buffer.get(handlerType);
        return "vide".equals(new String(handlerType, StandardCharsets.US_ASCII));
    }

    private static boolean isMp4ContainerBox(String type) {
        return "moov".equals(type) || "trak".equals(type) || "mdia".equals(type);
    }

    private static Mp4Box readMp4Box(SeekableByteChannel channel, long position, long limit) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        channel.position(position);
        if (channel.read(header) != header.capacity()) {
            return null;
        }

        header.flip();
        long size = Integer.toUnsignedLong(header.getInt());
        byte[] typeBytes = new byte[4];
        header.get(typeBytes);
        String type = new String(typeBytes, StandardCharsets.US_ASCII);
        int headerSize = 8;

        if (size == 1L) {
            ByteBuffer largeSizeBuffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            if (channel.read(largeSizeBuffer) != largeSizeBuffer.capacity()) {
                return null;
            }
            largeSizeBuffer.flip();
            size = largeSizeBuffer.getLong();
            headerSize = 16;
        } else if (size == 0L) {
            size = limit - position;
        }

        if (size < headerSize || position + size > limit) {
            return null;
        }
        return new Mp4Box(position, size, headerSize, type);
    }

    private boolean copyAlreadyVideoFile(Path inputFilePath, Path tempPath, Path outputPath, String currentName,
            int fileIndex, ProgressCallback progressCallback) throws IOException {
        Path resolvedOutputPath = resolveOutputPathForContent(outputPath, inputFilePath);
        if (resolvedOutputPath == null) {
            logFileOutcome("Skipped, normal MP4 already exists", fileIndex, inputFilePath);
            addResult(new DecryptionResult(currentName, inputFilePath, true, null));
            tempOutputFilePath = null;
            return true;
        }

        long totalBytes = Files.size(inputFilePath);
        try (InputStream inputStream = Files.newInputStream(inputFilePath);
                OutputStream outputStream = Files.newOutputStream(tempPath)) {
            byte[] buffer = new byte[8192];
            long copiedBytes = 0L;
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                if (isStopRequested()) {
                    return false;
                }
                outputStream.write(buffer, 0, bytesRead);
                copiedBytes += bytesRead;
                progressCallback.update(copiedBytes, totalBytes);
            }
        }
        if (isStopRequested()) {
            return false;
        }
        Files.move(tempPath, resolvedOutputPath, StandardCopyOption.REPLACE_EXISTING);
        logFileOutcome("Copied, input is normal MP4", fileIndex, inputFilePath);
        addResult(new DecryptionResult(currentName, inputFilePath, true, null));
        tempOutputFilePath = null;
        return true;
    }

    private void logFileOutcome(String outcome, int fileIndex, Path inputFilePath) {
        log.info("{} ({}/{}): {}", outcome, fileIndex + 1, inputFilePaths.size(), inputFilePath);
    }

    private Path resolveOutputPathForContent(Path requestedOutputPath, Path contentPath) throws IOException {
        if (!Files.exists(requestedOutputPath)) {
            return requestedOutputPath;
        }
        if (hasSameHash(requestedOutputPath, contentPath)) {
            return null;
        }

        int suffix = 2;
        Path candidateOutputPath;
        do {
            candidateOutputPath = outputPathWithSuffix(requestedOutputPath, suffix++);
            if (Files.exists(candidateOutputPath) && hasSameHash(candidateOutputPath, contentPath)) {
                return null;
            }
        } while (Files.exists(candidateOutputPath));

        log.info("Output file exists with different content. Writing renamed file: {}", candidateOutputPath);
        return candidateOutputPath;
    }

    private Path outputPathWithSuffix(Path outputPath, int suffix) {
        Path fileNamePath = outputPath.getFileName();
        String fileName = fileNamePath == null ? "output" : fileNamePath.toString();
        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        String extension = extensionIndex > 0 ? fileName.substring(extensionIndex) : "";
        Path parent = outputPath.getParent();
        String renamedFileName = baseName + " (" + suffix + ")" + extension;
        return parent == null ? Path.of(renamedFileName) : parent.resolve(renamedFileName);
    }

    private boolean hasSameHash(Path firstPath, Path secondPath) throws IOException {
        if (Files.size(firstPath) != Files.size(secondPath)) {
            return false;
        }
        return Arrays.equals(sha256(firstPath), sha256(secondPath));
    }

    private byte[] sha256(Path filePath) throws IOException {
        MessageDigest digest = newSha256Digest();
        byte[] buffer = new byte[8192];
        try (InputStream inputStream = Files.newInputStream(filePath);
                DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
            while (digestInputStream.read(buffer) != -1) {
                if (isStopRequested()) {
                    throw new IOException(I18n.get(ERROR_CANCELLED));
                }
            }
        }
        return digest.digest();
    }

    private MessageDigest newSha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 digest is not available", e);
        }
    }

    private void validateMp4File(Path file) {
        if (!looksLikeMp4(file)) {
            throw new CryptoException(I18n.get(ERROR_INVALID_FILE_FORMAT));
        }
    }

    private ProgressInfo calculateProgress(
            boolean singleFile,
            long processedBytes,
            long totalBytes,
            AtomicLong completedBeforeCurrentFile,
            AtomicLong totalBytesAllFiles) {

        long current = singleFile
                ? processedBytes
                : completedBeforeCurrentFile.get() + processedBytes;
        long total = singleFile
                ? totalBytes
                : totalBytesAllFiles.get();
        int percent = total == 0
                ? 100
                : (int) ((current * 100) / total);

        return new ProgressInfo(current, total, percent);
    }

    private AtomicLong calculateTotalBytes(List<Path> inputFilePaths) throws IOException {
        AtomicLong totalBytes = new AtomicLong(0);
        for (Path inputFilePath : inputFilePaths) {
            totalBytes.addAndGet(Files.size(inputFilePath));
        }
        return totalBytes;
    }

    private IOException findStorageException(Exception e) {
        if (e instanceof IOException ioException) {
            return classifyStorageException(ioException);
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof IOException ioException) {
                return classifyStorageException(ioException);
            }
            cause = cause.getCause();
        }
        return null;
    }

    private String getErrorMessage(Exception e) {
        if (e instanceof CryptoException cryptoException) {
            return cryptoErrorMessage(cryptoException);
        }
        if (e instanceof IOException ioException) {
            if (ioException instanceof AccessDeniedException) {
                return I18n.get("externalMediaDecrypt.access.denied");
            }
            IOException storageException = classifyStorageException(ioException);
            if (storageException != null) {
                return storageErrorMessage(storageException);
            }
            return I18n.get(ERROR_FILE_UNAVAILABLE);
        }
        return I18n.get(ERROR_DECRYPT_FAILED);
    }

    private String cryptoErrorMessage(CryptoException e) {
        String message = e.getMessage();
        if (I18n.get(ERROR_INVALID_FILE_FORMAT).equals(message)
                || I18n.get(ERROR_INVALID_CONFIGURATION).equals(message)) {
            return message;
        }
        return I18n.get(ERROR_DECRYPT_FAILED);
    }

    private IOException classifyStorageException(IOException e) {
        try {
            rethrowAsStorageException(e);
        } catch (DiskFullException | DriveUnavailableException storageException) {
            return storageException;
        } catch (IOException ignored) {
            // rethrowAsStorageException only re-throws known storage exceptions.
        }
        return null;
    }

    private String storageErrorMessage(IOException e) {
        return e instanceof DiskFullException
                ? I18n.get(ERROR_OUTPUT_DISK_FULL)
                : I18n.get(ERROR_OUTPUT_DRIVE_UNAVAILABLE);
    }

    private static void rethrowAsStorageException(IOException e) throws IOException {
        if (e instanceof DiskFullException || e instanceof DriveUnavailableException) {
            throw e;
        }
        if (e instanceof NoSuchFileException) {
            throw new DriveUnavailableException(e.getMessage());
        }
        if (e.getClass() == FileSystemException.class) {
            FileSystemException fileSystemException = (FileSystemException) e;
            String reason = fileSystemException.getReason();
            if (reason != null) {
                String normalizedReason = reason.toLowerCase();
                if (normalizedReason.contains("not enough space") || normalizedReason.contains("no space left")
                        || normalizedReason.contains("insufficient space")) {
                    throw new DiskFullException(fileSystemException.getMessage());
                }
                throw new DriveUnavailableException(fileSystemException.getMessage());
            }
        }

        String message = e.getMessage();
        if (message == null) {
            return;
        }
        String normalizedMessage = message.toLowerCase();
        if (normalizedMessage.contains("no space left") || normalizedMessage.contains("not enough space")
                || normalizedMessage.contains("insufficient space")) {
            throw new DiskFullException(message);
        }
        if (normalizedMessage.contains("device is not ready") || normalizedMessage.contains("cannot find")
                || normalizedMessage.contains("cannot access") || normalizedMessage.contains("unreachable")) {
            throw new DriveUnavailableException(message);
        }
    }

    private void handleFileError(String currentName, Path inputFilePath, String errorMessage, Exception e,
            Path tempPath) {
        if (e instanceof CryptoException || findStorageException(e) != null) {
            log.warn("Processing failed for file {}: {}", currentName, e.getMessage());
        } else {
            log.error("Processing failed for file {}: {}", currentName, e.getMessage(), e);
        }
        addResult(new DecryptionResult(currentName, inputFilePath, false, errorMessage));
        cleanupIncompleteFile(tempPath);
    }

    private void cleanupIncompleteFile(Path filePath) {
        boolean deleted = deleteExistingFile(filePath);

        if (log.isDebugEnabled()) {
            if (deleted) {
                log.debug("Cleaned up left-over file (or already absent): {}", filePath);
            } else {
                log.debug("Failed to clean up left-over file: {}", filePath);
            }
        }
    }

    private boolean deleteExistingFile(Path filePath) {
        if (filePath == null) {
            return false;
        }

        if (!Files.exists(filePath)) {
            return true;
        }

        try {
            Files.delete(filePath);
            return true;
        } catch (IOException e) {
            log.warn("Failed to delete file: {} message: {}", filePath, e.getMessage());
            return false;
        }
    }

    private record FileProcessContext(
            Path inputFilePath,
            String currentName,
            String decryptedFileName,
            String tempFileName,
            int fileIndex,
            long fileSize,
            AtomicLong processedBytesGlobal,
            ProgressCallback progressCallback) {
    }

    private static final class Mp4ScanState {
        private boolean hasFtyp;
        private boolean hasMoov;
        private boolean hasMdat;
        private boolean hasVideoTrack;

        private boolean isComplete() {
            return hasFtyp && hasMoov && hasMdat && hasVideoTrack;
        }
    }

    private record Mp4Box(long start, long size, int headerSize, String type) {

        private long payloadStart() {
            return start + headerSize;
        }

        private long payloadSize() {
            return size - headerSize;
        }
    }

    private record ProgressInfo(long current, long total, int percent) {
    }
}
