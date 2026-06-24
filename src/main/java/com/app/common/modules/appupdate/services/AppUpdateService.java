package com.app.common.modules.appupdate.services;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.modules.appupdate.models.AppUpdateInfo;
import com.app.common.services.AppConfigService;
import com.app.common.utils.DateTimeUtil;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Service
public class AppUpdateService {

    private static final Logger log = LoggerFactory.getLogger(AppUpdateService.class);

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build();

    private final AppConfigService appConfigService;
    private final String githubToken;

    public AppUpdateService(
            AppConfigService appConfigService,
            @Value("${app.update.github.token:}") String githubToken) {
        this.appConfigService = appConfigService;
        this.githubToken = githubToken == null ? "" : githubToken.trim();
    }

    // ── Check schedule ───────────────────────────────────────────────────────

    public boolean shouldCheckThisWeek() {
        try {
            String lastCheckDate = appConfigService.getConfigValue(AppConstants.KEY_LAST_CHECK_DATE);
            if (lastCheckDate == null || lastCheckDate.isBlank())
                return true;
            LocalDate lastCheck = LocalDate.parse(lastCheckDate, AppConstants.DATE_FORMATTER);
            return !DateTimeUtil.isSameIsoWeek(lastCheck, DateTimeUtil.currentLocalDate());
        } catch (Exception e) {
            log.warn("Failed to read last update check date", e);
            return true;
        }
    }

    public void saveCheckDate() {
        try {
            appConfigService.saveConfigValue(AppConstants.KEY_LAST_CHECK_DATE,
                    DateTimeUtil.currentLocalDate(AppConstants.DATE_FORMATTER));
        } catch (Exception e) {
            log.warn("Failed to save check date", e);
        }
    }

    // ── Skipped version ──────────────────────────────────────────────────────

    public void saveSkippedVersion(String version) {
        try {
            appConfigService.saveConfigValue(AppConstants.KEY_SKIPPED_VERSION, version);
        } catch (Exception e) {
            log.warn("Failed to save skipped version", e);
        }
    }

    public String getSkippedVersion() {
        try {
            String skipped = appConfigService.getConfigValue(AppConstants.KEY_SKIPPED_VERSION);
            return skipped != null ? skipped : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ── Version check ────────────────────────────────────────────────────────

    public AppUpdateInfo checkLatestVersion() {
        if (githubToken.isBlank()) {
            log.warn("Skipping update check because APP_UPDATE_GITHUB_TOKEN is not configured.");
            return null;
        }

        String currentVersion = resolveCurrentVersion();
        try {
            Request request = githubRequest(AppConstants.GITHUB_API, "application/vnd.github.v3+json").build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logUpdateCheckHttpFailure(response.code());
                    return null;
                }

                JSONArray releases = new JSONArray(response.body().string());
                if (releases.isEmpty()) {
                    log.warn("Update check returned no releases");
                    return null;
                }

                JSONObject latest = releases.optJSONObject(0);
                if (latest == null || !latest.has("tag_name") || !latest.has("assets")) {
                    log.warn("Latest release JSON missing expected fields");
                    return null;
                }

                String latestVersion = latest.optString("tag_name", "");
                JSONArray assets = latest.optJSONArray("assets");
                if (latestVersion.isBlank() || assets == null) {
                    log.warn("Latest release JSON contains invalid update metadata");
                    return null;
                }

                String downloadUrl = "";
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && asset.optString("name").endsWith(".exe")) {
                        downloadUrl = asset.optString("url", "");
                        break;
                    }
                }

                boolean hasUpdate = !latestVersion.equals(currentVersion);
                log.info("Version check — current: {}, latest: {}, hasUpdate: {}",
                        currentVersion, latestVersion, hasUpdate);

                return new AppUpdateInfo(latestVersion, downloadUrl, hasUpdate);
            }

        } catch (JSONException e) {
            log.warn("Failed to parse latest release JSON", e);
            return null;
        } catch (Exception e) {
            log.warn("Failed to check latest version", e);
            return null;
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────

    /**
     * Downloads installer and reports progress when response size is known.
     *
     * @param info             update metadata containing download URL and target
     *                         version
     * @param progressConsumer receives progress percentage from 20 to 80
     * @return downloaded installer file
     * @throws IOException when download or file write fails
     */
    public File downloadInstaller(AppUpdateInfo info, IntConsumer progressConsumer) throws IOException {
        Request request = githubRequest(info.downloadUrl(), "application/octet-stream").build();

        Path dest = Path.of(
                System.getProperty("user.home"), "Downloads",
                "BDMA-" + info.latestVersion() + ".exe");

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    int code = response.code();
                    IOException failure = new IOException("Download failed with HTTP " + code);
                    if (isTransientHttpError(code) && attempt < 3) {
                        lastFailure = failure;
                        log.warn("Installer download attempt {}/3 failed with HTTP {}. Retrying. URL: {}",
                                attempt, code, info.downloadUrl());
                        waitBeforeRetry(attempt);
                        continue;
                    }
                    throw failure;
                }

                try (InputStream in = body.byteStream();
                        OutputStream out = Files.newOutputStream(dest)) {
                    copyWithProgress(in, out, body.contentLength(), progressConsumer);
                }

                log.info("Installer downloaded to: {}", dest);
                return dest.toFile();
            } catch (IOException e) {
                if (attempt >= 3) {
                    throw e;
                }
                lastFailure = e;
                log.warn("Installer download attempt {}/3 failed. Retrying. URL: {}",
                        attempt, info.downloadUrl(), e);
                waitBeforeRetry(attempt);
            }
        }

        throw lastFailure != null ? lastFailure : new IOException("Download failed");
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private Request.Builder githubRequest(String url, String acceptHeader) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", acceptHeader)
                .header("User-Agent", "BDMA-App-Updater");

        if (!githubToken.isBlank()) {
            builder.header("Authorization", "Bearer " + githubToken);
        }

        return builder;
    }

    private void logUpdateCheckHttpFailure(int code) {
        if (code == 401 || code == 403 || code == 404) {
            log.warn("Update check failed with HTTP {}. Private repository requires Repo access token.", code);
            return;
        }

        log.warn("Update check failed with HTTP {}", code);
    }

    private boolean isTransientHttpError(int code) {
        return code == 408 || code == 429 || code >= 500;
    }

    private void waitBeforeRetry(int attempt) throws IOException {
        try {
            TimeUnit.SECONDS.sleep(attempt * 2L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to retry download", e);
        }
    }

    private void copyWithProgress(
            InputStream in,
            OutputStream out,
            long totalBytes,
            IntConsumer progressConsumer) throws IOException {
        byte[] buffer = new byte[8192];
        long downloadedBytes = 0;
        int nextNoticePercent = 20;
        int read;

        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            downloadedBytes += read;

            if (progressConsumer == null || totalBytes <= 0) {
                continue;
            }

            int percent = (int) Math.min(100, (downloadedBytes * 100) / totalBytes);
            while (percent >= nextNoticePercent && nextNoticePercent < 100) {
                progressConsumer.accept(nextNoticePercent);
                nextNoticePercent += 20;
            }
        }
    }

    private String resolveCurrentVersion() {
        String version = AppUpdateService.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = System.getProperty("app.version", AppConstants.VERSION_DEV);
        }

        version = version.trim();
        if (AppConstants.VERSION_DEV.equalsIgnoreCase(version))
            return AppConstants.VERSION_DEV;

        return version.startsWith("v") ? version : "v" + version;
    }
}
