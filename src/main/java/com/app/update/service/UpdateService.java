package com.app.update.service;

import com.app.common.config.AppPaths;
import com.app.update.model.UpdateInfo;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);

    private static final String GITHUB_API = "https://api.github.com/repos/hoantrandanh-wq/testversion/releases";
    private static final String LAST_CHECK_DATE = "lastCheckDate";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Path PREFS_FILE = Path.of(AppPaths.configDir(), "update-prefs.json");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    // ── Check schedule ───────────────────────────────────────────────────────

    public boolean shouldCheckThisWeek() {
        try {
            if (!Files.exists(PREFS_FILE)) return true;
            JSONObject prefs = new JSONObject(Files.readString(PREFS_FILE));
            if (!prefs.has(LAST_CHECK_DATE)) return true;

            LocalDate lastCheck = LocalDate.parse(prefs.getString(LAST_CHECK_DATE), FORMATTER);
            return !isSameWeek(lastCheck, LocalDate.now());
        } catch (Exception e) {
            return true;
        }
    }

    public void saveCheckDate() {
        try {
            JSONObject prefs = loadPrefs();
            prefs.put(LAST_CHECK_DATE, LocalDate.now().format(FORMATTER));
            savePrefs(prefs);
        } catch (Exception e) {
            log.warn("Failed to save check date", e);
        }
    }

    // ── Skipped version ──────────────────────────────────────────────────────

    public void saveSkippedVersion(String version) {
        try {
            JSONObject prefs = loadPrefs();
            prefs.put("skippedVersion", version);
            savePrefs(prefs);
        } catch (Exception e) {
            log.warn("Failed to save skipped version", e);
        }
    }

    public String getSkippedVersion() {
        try {
            return loadPrefs().optString("skippedVersion", "");
        } catch (Exception e) {
            return "";
        }
    }

    // ── Version check ────────────────────────────────────────────────────────

    public UpdateInfo checkLatestVersion() {
        String currentVersion = resolveCurrentVersion();
        try {
            Request request = new Request.Builder()
                    .url(GITHUB_API)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return new UpdateInfo(currentVersion, "", false);
                }

                JSONArray releases = new JSONArray(response.body().string());
                if (releases.isEmpty()) return new UpdateInfo(currentVersion, "", false);

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

                return new UpdateInfo(latestVersion, downloadUrl, hasUpdate);
            }

        } catch (Exception e) {
            log.warn("Failed to check latest version", e);
            return new UpdateInfo(currentVersion, "", false);
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────

    public File downloadInstaller(UpdateInfo info) throws IOException {
        URL url = URI.create(info.downloadUrl()).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);

        Path dest = Path.of(
                System.getProperty("user.home"), "Desktop",
                "HelloWorldApp-update-" + info.latestVersion() + ".exe"
        );

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(dest)) {
            in.transferTo(out);
        }

        log.info("Installer downloaded to: {}", dest);
        return dest.toFile();
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private JSONObject loadPrefs() {
        try {
            if (!Files.exists(PREFS_FILE)) return new JSONObject();
            return new JSONObject(Files.readString(PREFS_FILE));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void savePrefs(JSONObject prefs) throws IOException {
        Files.createDirectories(PREFS_FILE.getParent());
        Files.writeString(PREFS_FILE, prefs.toString());
    }

    private String resolveCurrentVersion() {
        String version = UpdateService.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = System.getProperty("app.version", "dev");
        }

        version = version.trim();
        if ("dev".equalsIgnoreCase(version)) return "dev";

        return version.startsWith("v") ? version : "v" + version;
    }

    private boolean isSameWeek(LocalDate d1, LocalDate d2) {
        WeekFields wf = WeekFields.ISO;
        return d1.get(wf.weekOfWeekBasedYear()) == d2.get(wf.weekOfWeekBasedYear())
                && d1.getYear() == d2.getYear();
    }
}