package com.app.common.modules.appupdate.services;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    // ── Reminder ─────────────────────────────────────────────────────────────

    /**
     * Stores the version and date chosen by the user for weekly update reminder
     * suppression.
     *
     * @param version update version to suppress for the current ISO week
     */
    public void saveRemindNextWeekVersion(String version) {
        try {
            appConfigService.saveConfigValue(AppConstants.KEY_REMIND_UPDATE_VERSION, version);
            appConfigService.saveConfigValue(AppConstants.KEY_REMIND_UPDATE_DATE,
                    DateTimeUtil.currentLocalDate(AppConstants.DATE_FORMATTER));
        } catch (Exception e) {
            log.warn("Failed to save update reminder", e);
        }
    }

    /**
     * Suppresses only the same update version during the same ISO week. A newer
     * version still notifies immediately.
     *
     * @param version latest update version
     * @return true when the update dialog should stay hidden
     */
    public boolean shouldSuppressReminderThisWeek(String version) {
        try {
            String remindedVersion = appConfigService.getConfigValue(AppConstants.KEY_REMIND_UPDATE_VERSION);
            if (!version.equals(remindedVersion))
                return false;

            String remindDate = appConfigService.getConfigValue(AppConstants.KEY_REMIND_UPDATE_DATE);
            if (remindDate == null || remindDate.isBlank())
                return false;

            LocalDate remindedAt = LocalDate.parse(remindDate, AppConstants.DATE_FORMATTER);
            return DateTimeUtil.isSameIsoWeek(remindedAt, DateTimeUtil.currentLocalDate());
        } catch (Exception e) {
            log.warn("Failed to read update reminder", e);
            return false;
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
            Request request = buildRequest(AppConstants.GITHUB_API, "application/vnd.github.v3+json").build();

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

                String fallbackDownloadUrl = "";
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && asset.optString("name").endsWith(".exe")) {
                        fallbackDownloadUrl = asset.optString("url", "");
                        break;
                    }
                }

                boolean hasUpdate = !latestVersion.equals(currentVersion);
                log.info("Version check — current: {}, latest: {}, hasUpdate: {}",
                        currentVersion, latestVersion, hasUpdate);

                return new AppUpdateInfo(latestVersion, AppConstants.R2_DOWNLOAD_BASE_URL, fallbackDownloadUrl, hasUpdate);
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
        List<String> candidateUrls = buildDownloadCandidateUrls(info.fallbackDownloadUrl());
        List<IOException> failures = new ArrayList<>();
        Path dest = Path.of(
                System.getProperty("user.home"), "Downloads",
                "BDMA-" + info.latestVersion() + ".exe");

        for (int i = 0; i < candidateUrls.size(); i++) {
            String candidateUrl = candidateUrls.get(i);
            log.info("Downloading installer from URL {}/{}: {}", i + 1, candidateUrls.size(), candidateUrl);
            try {
                return downloadInstallerFromUrl(candidateUrl, dest, progressConsumer);
            } catch (IOException e) {
                failures.add(e);
                log.warn("Installer download attempt {}/{} failed for URL {}. {}",
                        i + 1, candidateUrls.size(), candidateUrl, e.getMessage());
            }
        }

        IOException combinedFailure = new IOException("All installer download attempts failed. Last error: "
                + failures.getLast().getMessage());
        combinedFailure.addSuppressed(failures.getLast());
        throw combinedFailure;
    }

    // ── Private ──────────────────────────────────────────────────────────────

    List<String> buildDownloadCandidateUrls(String fallbackDownloadUrl) {
        List<String> candidateUrls = new ArrayList<>();
        candidateUrls.add(AppConstants.R2_DOWNLOAD_BASE_URL);
        candidateUrls.add(AppConstants.DOWNLOAD_HOMEPAGE_URL);
        if (fallbackDownloadUrl != null && !fallbackDownloadUrl.isBlank()) {
            candidateUrls.add(fallbackDownloadUrl);
        }
        return candidateUrls;
    }

    private File downloadInstallerFromUrl(String downloadUrl, Path dest, IntConsumer progressConsumer) throws IOException {
        Request request = buildRequest(downloadUrl, "application/octet-stream").build();

        int attempt = 1;
        while (true) {
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    int code = response.code();
                    IOException failure = new IOException("Download failed with HTTP " + code + " for " + downloadUrl);
                    if (isTransientHttpError(code) && attempt < 3) {
                        log.warn("Installer download attempt {}/3 failed with HTTP {}. Retrying. URL: {}",
                                attempt, code, downloadUrl);
                        waitBeforeRetry(attempt);
                        attempt++;
                        continue;
                    }
                    throw failure;
                }

                try (InputStream in = body.byteStream();
                        OutputStream out = Files.newOutputStream(dest)) {
                    copyWithProgress(in, out, body.contentLength(), progressConsumer);
                }

                log.info("Installer downloaded from: {} to: {}", downloadUrl, dest);
                return dest.toFile();
            } catch (IOException e) {
                if (attempt >= 3) {
                    throw e;
                }

                log.warn("Installer download attempt {}/3 failed. Retrying. URL: {}",
                        attempt, downloadUrl, e);
                waitBeforeRetry(attempt);
                attempt++;
            }
        }
    }

    private Request.Builder buildRequest(String url, String acceptHeader) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", acceptHeader)
                .header("User-Agent", "BDMA-App-Updater");

        if (!githubToken.isBlank() && "api.github.com".equals(builder.build().url().host())) {
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
