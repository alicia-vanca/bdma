package com.app.common.modules.loggly;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

import com.app.common.configs.AppContext;
import com.app.common.definitions.AppDataPaths;
import com.app.common.exceptions.AppException;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Queue-first Logback appender for reliable, ordered Loggly delivery.
 * <p>
 * Every logging event is converted to JSON, wrapped in a signed envelope, and
 * appended to {@code loggly-queue.ndjson}; delivery never bypasses the local
 * queue. The signing key is derived in memory from the device ID and the
 * app-specific {@code bdma-log-event-signing-v1} context so the sender can
 * later tell whether each queued log line still matches what the app originally
 * wrote.
 * <p>
 * A drain cycle atomically renames the active queue to
 * {@code loggly-queue.sending} and creates a new empty active queue so
 * application threads can keep logging while the sender posts the stable
 * sending file to Loggly in FIFO order. Before sending, the appender verifies
 * the signed payload and adds {@code logIntegrityVerified=true}; modified,
 * unsigned legacy, or invalid local queue data is still sent but marked with
 * {@code logIntegrityVerified=false}.
 * <p>
 * The sender stops at the first failed POST. It then streams the failed line,
 * the rest of {@code loggly-queue.sending}, and any newer lines from
 * {@code loggly-queue.ndjson} into {@code loggly-queue.merged}; the merged file
 * replaces the active queue so older unsent events remain ahead of newer
 * events.
 * If the app exits while {@code loggly-queue.sending} exists, startup recovery
 * merges it back before the active queue for at-least-once, ordered retry.
 * <p>
 * Internal failures use Logback status methods such as {@link #addWarn(String)}
 * and also write to {@code loggly-internal.log} directly instead of normal
 * SLF4J logging to avoid recursively enqueueing Loggly sender errors as new
 * application log events.
 */
public class LogglyQueuedAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
    private static final String QUEUE_FILE_NAME = "loggly-queue.ndjson";
    private static final String SENDING_FILE_NAME = "loggly-queue.sending";
    private static final String MERGED_FILE_NAME = "loggly-queue.merged";
    private static final String INTERNAL_LOG_FILE_NAME = "loggly-internal.log";
    private static final String LOG_SIGNING_CONTEXT = "bdma-log-event-signing-v1";
    private static final String LOG_INTEGRITY_VERIFIED_FIELD = "logIntegrityVerified";
    private static final String ENVELOPE_PAYLOAD_FIELD = "payload";
    private static final String ENVELOPE_SIGNATURE_FIELD = "signature";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final ZoneId LOG_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter INTERNAL_LOG_TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final long DEFAULT_RETRY_INTERVAL_MILLIS = 5_000L;
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_READ_TIMEOUT_SECONDS = 10;

    private final Object fileLock = new Object();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicBoolean drainRequested = new AtomicBoolean(false);

    private String endpointUrl;
    private long retryIntervalMillis = DEFAULT_RETRY_INTERVAL_MILLIS;
    private LogglyJsonLayout layout;
    private OkHttpClient httpClient;
    private ScheduledExecutorService retryExecutor;
    private Path queueFile;
    private Path sendingFile;
    private Path mergedFile;
    private Path internalLogFile;
    private byte[] signingKey;
    private volatile boolean signingKeyDerived = false;
    private volatile boolean stopping = false;

    private enum DrainResult {
        EMPTY,
        SENT_ALL,
        FAILED
    }

    @Override
    public void start() {
        stopping = false;
        if (endpointUrl == null || endpointUrl.isBlank()) {
            addError("endpointUrl must be configured for LogglyQueuedAppender");
            return;
        }

        try {
            initializeFiles();
        } catch (IOException e) {
            addError("Failed to initialize Loggly queue files", e);
            return;
        }

        layout = new LogglyJsonLayout();
        layout.setContext(getContext());
        layout.start();

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();

        retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "loggly-queue-sender");
            thread.setDaemon(true);
            return thread;
        });
        retryExecutor.scheduleWithFixedDelay(this::drainQueueSafely, retryIntervalMillis, retryIntervalMillis,
                TimeUnit.MILLISECONDS);

        super.start();
        requestDrain();
    }

    /**
     * Lazy-derives the signing key on first use so it's available even if
     * AppContext.deviceId is set later during startup. A missing device ID is
     * retryable; otherwise one early log event can leave the appender unsigned
     * for the rest of the process.
     */
    private void ensureSigningKeyDerived() {
        if (signingKeyDerived) {
            return;
        }
        synchronized (this) {
            if (signingKeyDerived) {
                return;
            }
            try {
                signingKey = deriveSigningKey();
                signingKeyDerived = true;
            } catch (GeneralSecurityException | AppException e) {
                addWarn("Signing key could not be derived, logs will be queued unsigned: " + e.getMessage());
            }
        }
    }

    @Override
    public void stop() {
        if (retryExecutor != null) {
            stopping = true;
            // Do not accept new drain requests and stop the scheduled sender
            retryExecutor.shutdown();
            // Let processing queue finish, then attempt to drain any remaining events, stop
            // on failure or all finished
            drainUntilEmptyOrFailed();
        }
        if (layout != null) {
            layout.stop();
        }
        if (signingKey != null) {
            Arrays.fill(signingKey, (byte) 0);
        }
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (!isStarted()) {
            return;
        }

        String eventJson = layout.doLayout(eventObject);
        ensureSigningKeyDerived();
        try {
            enqueue(createSignedEnvelope(eventJson));
        } catch (IOException e) {
            addError("Failed to enqueue Loggly event", e);
            return;
        } catch (GeneralSecurityException e) {
            addError("Failed to sign Loggly event", e);
            return;
        }

        requestDrain();
    }

    private void requestDrain() {
        if (stopping || retryExecutor == null || retryExecutor.isShutdown()) {
            return;
        }
        if (!drainRequested.compareAndSet(false, true)) {
            return;
        }

        try {
            retryExecutor.execute(() -> {
                try {
                    drainQueueSafely();
                } finally {
                    drainRequested.set(false);
                }
            });
        } catch (RuntimeException e) {
            drainRequested.set(false);
            addWarn("Loggly queue drain request was rejected: " + e.getMessage());
        }
    }

    // Called reflectively by Logback from logback-spring.xml.
    @SuppressWarnings("unused")
    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    // Called reflectively by Logback from logback-spring.xml.
    @SuppressWarnings("unused")
    public void setRetryIntervalMillis(long retryIntervalMillis) {
        if (retryIntervalMillis > 0) {
            this.retryIntervalMillis = retryIntervalMillis;
        }
    }

    @Override
    public void addWarn(String msg) {
        super.addWarn(msg);
        writeInternalDiagnostic("WARN", msg, null);
    }

    @Override
    public void addWarn(String msg, Throwable ex) {
        super.addWarn(msg, ex);
        writeInternalDiagnostic("WARN", msg, ex);
    }

    @Override
    public void addError(String msg) {
        super.addError(msg);
        writeInternalDiagnostic("ERROR", msg, null);
    }

    @Override
    public void addError(String msg, Throwable ex) {
        super.addError(msg, ex);
        writeInternalDiagnostic("ERROR", msg, ex);
    }

    private void initializeFiles() throws IOException {
        Path logDir = Path.of(AppDataPaths.logsDir());
        Files.createDirectories(logDir);
        queueFile = logDir.resolve(QUEUE_FILE_NAME);
        sendingFile = logDir.resolve(SENDING_FILE_NAME);
        mergedFile = logDir.resolve(MERGED_FILE_NAME);
        internalLogFile = logDir.resolve(INTERNAL_LOG_FILE_NAME);
        if (!Files.exists(queueFile)) {
            Files.createFile(queueFile);
        }
        if (!Files.exists(internalLogFile)) {
            Files.createFile(internalLogFile);
        }
        recoverInterruptedDrain();
    }

    private void recoverInterruptedDrain() throws IOException {
        if (Files.exists(sendingFile)) {
            mergeFilesPreservingOrder(sendingFile, queueFile, mergedFile);
            Files.move(mergedFile, queueFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(sendingFile);
        }
    }

    private void enqueue(String eventJson) throws IOException {
        synchronized (fileLock) {
            Files.writeString(queueFile, eventJson + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private void drainQueueSafely() {
        if (!isStarted() || !draining.compareAndSet(false, true)) {
            return;
        }

        try {
            drainQueue();
        } catch (Exception e) {
            addWarn("Loggly queue drain failed", e);
        } finally {
            draining.set(false);
        }
    }

    private void drainUntilEmptyOrFailed() {
        if (!isStarted()) {
            return;
        }

        while (true) {
            if (!draining.compareAndSet(false, true)) {
                Thread.yield();
            } else {
                DrainResult result = DrainResult.FAILED;
                try {
                    result = drainQueue();
                } catch (Exception e) {
                    addWarn("Final Loggly queue drain failed", e);
                } finally {
                    draining.set(false);
                }

                if (result == DrainResult.EMPTY || result == DrainResult.FAILED) {
                    return;
                }
            }
        }
    }

    private DrainResult drainQueue() throws IOException {
        synchronized (fileLock) {
            if (!hasContent(queueFile)) {
                return DrainResult.EMPTY;
            }
            Files.move(queueFile, sendingFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.createFile(queueFile);
        }

        boolean fullySent = sendQueuedLines();
        if (fullySent) {
            Files.deleteIfExists(sendingFile);
            return DrainResult.SENT_ALL;
        }
        return DrainResult.FAILED;
    }

    /**
     * Sends queued lines until the first failure. On failure, streams the failed
     * line and the remaining lines back ahead of newly queued events.
     */
    private boolean sendQueuedLines() throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(sendingFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                if (!sendToLoggly(line)) {
                    restoreFailedAndRemainingLines(line, reader);
                    return false;
                }
            }
        }
        return true;
    }

    private void restoreFailedAndRemainingLines(String failedLine, BufferedReader reader) throws IOException {
        synchronized (fileLock) {
            Files.deleteIfExists(mergedFile);
            try (BufferedWriter writer = Files.newBufferedWriter(mergedFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write(failedLine);
                writer.newLine();
                copyRemainingLines(reader, writer);
                copyFileLines(queueFile, writer);
            }
            Files.move(mergedFile, queueFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(sendingFile);
        }
    }

    private void copyRemainingLines(BufferedReader reader, BufferedWriter writer) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            writer.write(line);
            writer.newLine();
        }
    }

    private void copyFileLines(Path sourceFile, BufferedWriter writer) throws IOException {
        if (!hasContent(sourceFile)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(sourceFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    private boolean sendToLoggly(String queuedLine) {
        String eventJson = prepareLogglyEvent(queuedLine);
        RequestBody body = RequestBody.create(eventJson, JSON_MEDIA_TYPE);
        Request request = new Request.Builder()
                .url(endpointUrl)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return true;
            }
            addWarn("Loggly send failed with HTTP " + response.code() + " log:" + eventJson);
            return false;
        } catch (IOException e) {
            addWarn("Loggly send failed: " + e.getMessage() + " log:" + eventJson);
            return false;
        }
    }

    private byte[] deriveSigningKey() throws GeneralSecurityException {
        String deviceId = AppContext.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            throw new AppException("Device ID is required for Loggly log signing");
        }

        byte[] deviceIdBytes = deviceId.getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(deviceIdBytes, HMAC_ALGORITHM));
            return mac.doFinal(LOG_SIGNING_CONTEXT.getBytes(StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(deviceIdBytes, (byte) 0);
        }
    }

    /**
     * Stores a signed envelope in the local queue so later sends can mark whether
     * each log event still matches what the app originally wrote. If the signing
     * key is not yet available, stores the raw JSON unsigned.
     */
    private String createSignedEnvelope(String payloadJson) throws GeneralSecurityException {
        if (signingKey == null) {
            return payloadJson;
        }
        JSONObject envelope = new JSONObject();
        envelope.put(ENVELOPE_PAYLOAD_FIELD, payloadJson);
        envelope.put(ENVELOPE_SIGNATURE_FIELD, sign(payloadJson));
        return envelope.toString();
    }

    private String prepareLogglyEvent(String queuedLine) {
        try {
            ensureSigningKeyDerived();
            JSONObject queuedJson = new JSONObject(queuedLine);
            if (queuedJson.has(ENVELOPE_PAYLOAD_FIELD) && queuedJson.has(ENVELOPE_SIGNATURE_FIELD)) {
                return prepareSignedLogglyEvent(queuedJson);
            }
            return markIntegrity(queuedJson, false).toString();
        } catch (Exception e) {
            addWarn("Invalid Loggly queue line detected: " + e.getMessage() + " line:" + queuedLine);
            JSONObject fallback = new JSONObject();
            fallback.put(LOG_INTEGRITY_VERIFIED_FIELD, false);
            return fallback.toString();
        }
    }

    private String prepareSignedLogglyEvent(JSONObject envelope) {
        String payloadJson = envelope.optString(ENVELOPE_PAYLOAD_FIELD, "");
        String expectedSignature = envelope.optString(ENVELOPE_SIGNATURE_FIELD, "");

        JSONObject payload;
        try {
            payload = new JSONObject(payloadJson);
        } catch (Exception e) {
            addWarn("Invalid signed Loggly queue payload JSON: " + e.getMessage() + " envelope:" + envelope);
            JSONObject fallback = new JSONObject();
            fallback.put(LOG_INTEGRITY_VERIFIED_FIELD, false);
            return fallback.toString();
        }

        try {
            if (signingKey == null) {
                return markIntegrity(payload, false).toString();
            }
            boolean verified = MessageDigest.isEqual(sign(payloadJson).getBytes(StandardCharsets.UTF_8),
                    expectedSignature.getBytes(StandardCharsets.UTF_8));
            return markIntegrity(payload, verified).toString();
        } catch (Exception e) {
            addWarn("Loggly queue signature verification failed: " + e.getMessage() + " envelope:"
                    + envelope);
            return markIntegrity(payload, false).toString();
        }
    }

    private JSONObject markIntegrity(JSONObject payload, boolean verified) {
        payload.put(LOG_INTEGRITY_VERIFIED_FIELD, verified);
        return payload;
    }

    private String sign(String payloadJson) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
        return Base64.getEncoder().encodeToString(mac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean hasContent(Path file) throws IOException {
        return Files.exists(file) && Files.size(file) > 0;
    }

    /**
     * Writes appender diagnostics without going through SLF4J, preventing Loggly
     * sender failures from recursively creating more Loggly queue events.
     */
    private void writeInternalDiagnostic(String level, String message, Throwable throwable) {
        if (internalLogFile == null) {
            return;
        }

        String timestamp = OffsetDateTime.now(LOG_TIME_ZONE).format(INTERNAL_LOG_TIMESTAMP_FORMATTER);
        StringBuilder entry = new StringBuilder()
                .append(timestamp)
                .append(' ')
                .append(level)
                .append(' ')
                .append(message);
        if (throwable != null) {
            entry.append(" - ")
                    .append(throwable.getClass().getName())
                    .append(": ")
                    .append(throwable.getMessage());
        }
        entry.append(System.lineSeparator());

        try {
            Files.writeString(internalLogFile, entry.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Do not report diagnostic-file failures through logging; that can recurse.
        }
    }

    private void mergeFilesPreservingOrder(Path firstFile, Path secondFile, Path targetFile) throws IOException {
        Files.deleteIfExists(targetFile);
        try (BufferedWriter writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            copyFileLines(firstFile, writer);
            copyFileLines(secondFile, writer);
        }
    }
}
