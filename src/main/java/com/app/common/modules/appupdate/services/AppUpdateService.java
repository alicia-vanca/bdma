package com.app.common.modules.appupdate.services;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.modules.appupdate.models.AppUpdateInfo;
import com.app.common.definitions.AppConstants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.concurrent.TimeUnit;

@Service
public class AppUpdateService {

    private static final Logger log = LoggerFactory.getLogger(AppUpdateService.class);

    private static final String GITHUB_API = "https://api.github.com/repos/DucVietTech/bdma/releases";
    private static final String KEY_LAST_CHECK_DATE = AppConstants.KEY_LAST_CHECK_DATE;
    private static final String KEY_SKIPPED_VERSION = AppConstants.KEY_SKIPPED_VERSION;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(AppConstants.DATE_FORMAT);

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    private final com.app.common.services.AppConfigService appConfigService;

    public AppUpdateService(com.app.common.services.AppConfigService appConfigService) {
        this.appConfigService = appConfigService;
    }

    // ── Check schedule ───────────────────────────────────────────────────────

    public boolean shouldCheckThisWeek() {
        try {
            String lastCheckDate = appConfigService.getConfigValue(KEY_LAST_CHECK_DATE);
            if (lastCheckDate == null || lastCheckDate.isBlank())
                return true;
            LocalDate lastCheck = LocalDate.parse(lastCheckDate, FORMATTER);
            return !isSameWeek(lastCheck, LocalDate.now());
        } catch (Exception e) {
            return true;
        }
    }

    public void saveCheckDate() {
        try {
            appConfigService.saveConfigValue(KEY_LAST_CHECK_DATE, LocalDate.now().format(FORMATTER));
        } catch (Exception e) {
            log.warn("Failed to save check date", e);
        }
    }

    // ── Skipped version ──────────────────────────────────────────────────────

    public void saveSkippedVersion(String version) {
        try {
            appConfigService.saveConfigValue(KEY_SKIPPED_VERSION, version);
        } catch (Exception e) {
            log.warn("Failed to save skipped version", e);
        }
    }

    public String getSkippedVersion() {
        try {
            String skipped = appConfigService.getConfigValue(KEY_SKIPPED_VERSION);
            return skipped != null ? skipped : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ── Version check ────────────────────────────────────────────────────────

    public AppUpdateInfo checkLatestVersion() {
        String currentVersion = resolveCurrentVersion();
        try {
            Request request = new Request.Builder()
                    .url(GITHUB_API)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return new AppUpdateInfo(currentVersion, "", false);
                }

                JSONArray releases = new JSONArray(response.body().string());
                if (releases.isEmpty())
                    return new AppUpdateInfo(currentVersion, "", false);

                JSONObject latest = releases.getJSONObject(0);
                String latestVersion = latest.getString("tag_name");
                String downloadUrl = "";

                JSONArray assets = latest.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (asset.getString("name").endsWith(".exe")) {
                        downloadUrl = asset.getString("browser_download_url");
                        break;
                    }
                }

                boolean hasUpdate = !latestVersion.equals(currentVersion);
                log.info("Version check — current: {}, latest: {}, hasUpdate: {}",
                        currentVersion, latestVersion, hasUpdate);

                return new AppUpdateInfo(latestVersion, downloadUrl, hasUpdate);
            }

        } catch (Exception e) {
            log.warn("Failed to check latest version", e);
            return new AppUpdateInfo(currentVersion, "", false);
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────

    public File downloadInstaller(AppUpdateInfo info) throws IOException {
        URL url = URI.create(info.downloadUrl()).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);

        Path dest = Path.of(
                System.getProperty("user.home"), "Downloads",
                "BDMA-" + info.latestVersion() + ".exe");

        try (InputStream in = conn.getInputStream();
                OutputStream out = Files.newOutputStream(dest)) {
            in.transferTo(out);
        } finally {
            conn.disconnect();
        }

        log.info("Installer downloaded to: {}", dest);
        return dest.toFile();
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private String resolveCurrentVersion() {
        String version = AppUpdateService.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = System.getProperty("app.version", "dev");
        }

        version = version.trim();
        if ("dev".equalsIgnoreCase(version))
            return "dev";

        return version.startsWith("v") ? version : "v" + version;
    }

    private boolean isSameWeek(LocalDate d1, LocalDate d2) {
        WeekFields wf = WeekFields.ISO;
        return d1.get(wf.weekOfWeekBasedYear()) == d2.get(wf.weekOfWeekBasedYear())
                && d1.getYear() == d2.getYear();
    }
}
