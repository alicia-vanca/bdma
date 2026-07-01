package com.app.common.modules.media.controllers;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.app.common.definitions.AppConstants;
import com.app.common.dtos.FileView;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.media.dtos.GpsCoordinate;
import com.app.common.modules.media.dtos.GpsPoint;
import com.app.common.modules.media.services.MapTileCacheService;
import com.app.common.modules.theme.ThemeManager;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MediaGpsMapController {

    private static final Logger log = LoggerFactory.getLogger(MediaGpsMapController.class);

    private static final String MAP_HTML_RESOURCE = "/html/common/media/media-viewer-map.html";
    private static final String MAP_TILE_HOST = "tile.openstreetmap.org";
    private static final String UPDATE_CURRENT_MARKER_SCRIPT = "updateCurrentMarker(%f, %f, '%.6f, %.6f');";
    private static final int MAP_TILE_HTTPS_PORT = 443;
    private static final double LOGICAL_PATH_SAMPLE_SECONDS = 1.0;
    private static final double MIN_LOGICAL_POINT_DISTANCE_METERS = 20.0;
    private static final double SEEK_THRESHOLD = LOGICAL_PATH_SAMPLE_SECONDS + 1.0;
    private static final long NETWORK_MONITOR_START_LOG_COOLDOWN_NANOS = TimeUnit.MINUTES.toNanos(5);

    record Dependencies(
            WebView mapView,
            StackPane mapErrorPane,
            Label mapErrorLabel,
            VBox locationPane,
            Label locationGps,
            MapTileCacheService mapTileCacheService) {
    }

    private record TimedGpsCoordinate(double timeSeconds, GpsCoordinate coordinate) {
    }

    private final WebView mapView;
    private final StackPane mapErrorPane;
    private final Label mapErrorLabel;
    private final Label locationGps;
    private final MapTileCacheService mapTileCacheService;
    private final MapJsBridge mapJsBridge = new MapJsBridge();
    private final List<TimedGpsCoordinate> logicalGpsPath = new ArrayList<>();
    private final PauseTransition mapDebouncer = new PauseTransition(Duration.millis(250));
    private final PauseTransition mapErrorOverlayDelay = new PauseTransition(Duration.seconds(6));
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MediaViewer-NetworkMonitor");
        thread.setDaemon(true);
        return thread;
    });

    private FileView currentFile;
    private List<GpsPoint> gpsTimeline = new ArrayList<>();
    private boolean mapLoaded;
    private boolean gpsMetadataLoading;
    private GpsCoordinate currentGpsCoordinate;
    private double currentVideoSeconds;
    private long lastGpsIntervalIndex = -1;
    private long lastBuiltLogicalSampleIndex = -1;
    private int lastRenderedLogicalPointIndex = -1;
    private double lastVideoSeconds = -1;
    private ScheduledFuture<?> monitorTask;
    private long lastNetworkMonitorStartLogNanos;
    private boolean cleanedUp;

    MediaGpsMapController(Dependencies dependencies) {
        this.mapView = dependencies.mapView();
        this.mapErrorPane = dependencies.mapErrorPane();
        this.mapErrorLabel = dependencies.mapErrorLabel();
        this.locationGps = dependencies.locationGps();
        this.mapTileCacheService = dependencies.mapTileCacheService();
    }

    void initialize() {
        setupMapViewListener();
        mapDebouncer.setOnFinished(e -> updateMapDisplay());
        mapErrorOverlayDelay.setOnFinished(e -> showMapErrorOverlay());
        loadMapHtml();
    }

    void refreshLocalizedText() {
        updateMapControlsLocalizedText();
    }

    void refreshTheme(String theme) {
        if (!mapLoaded || mapView == null) {
            return;
        }
        boolean dark = AppConstants.THEME_DARK.equals(theme);
        Platform.runLater(() -> {
            try {
                mapView.getEngine().executeScript("updateTheme(" + dark + ");");
            } catch (Exception e) {
                log.error("Failed to update map theme", e);
            }
        });
    }

    void resetForFile(FileView file) {
        mapDebouncer.stop();
        currentFile = file;
        currentGpsCoordinate = null;
        currentVideoSeconds = 0;
        gpsTimeline = new ArrayList<>();
        gpsMetadataLoading = true;
        resetLogicalGpsPath();
        if (isLeafletReady()) {
            clearMapDisplay();
        }
    }

    GpsCoordinate setTimeline(List<GpsPoint> timeline) {
        gpsTimeline = normalizeGpsTimeline(timeline);
        resetLogicalGpsPath();
        gpsMetadataLoading = false;
        GpsCoordinate gps = gpsTimeline.isEmpty() ? null : gpsTimeline.getFirst().toCoordinate();
        if (mapLoaded) {
            requestMapUpdate();
        }
        return gps;
    }

    void setCurrentGps(GpsCoordinate gps) {
        currentGpsCoordinate = gps;
        gpsMetadataLoading = false;
        if (mapLoaded) {
            requestMapUpdate();
        }
    }

    int timelinePointCount() {
        return gpsTimeline == null ? 0 : gpsTimeline.size();
    }

    void updateForVideoTime(double currentSeconds) {
        if (gpsTimeline == null || gpsTimeline.isEmpty()) {
            return;
        }

        boolean seeked = isSeeked(currentSeconds);
        int intervalIndex = computeIntervalIndex(currentSeconds);
        this.currentVideoSeconds = currentSeconds;
        extendLogicalGpsPathThrough(intervalIndex);

        if (seeked || intervalIndex < lastGpsIntervalIndex) {
            redrawLogicalPath();
        } else if (intervalIndex != lastGpsIntervalIndex) {
            appendLogicalPathThrough(currentSeconds);
        }

        GpsPoint current = findGpsAtOrBefore(currentSeconds);
        GpsCoordinate fallback = current == null ? null : current.toCoordinate();
        GpsCoordinate displayedCoord = currentLogicalCoordinate(currentSeconds, fallback);
        lastVideoSeconds = currentSeconds;
        lastGpsIntervalIndex = intervalIndex;
        if (displayedCoord != null) {
            updateCurrentGpsMarker(displayedCoord);
        }
    }

    void resetLogicalPath() {
        resetLogicalGpsPath();
    }

    void refreshMapSize() {
        if (!mapLoaded) {
            log.warn("Skipping map size refresh because WebView map is not loaded.");
            return;
        }
        Platform.runLater(() -> {
            try {
                mapView.getEngine().executeScript("invalidateMapSize();");
            } catch (Exception e) {
                log.error("Failed to invalidate map size", e);
            }
        });
    }

    void cleanup() {
        cleanedUp = true;
        mapDebouncer.stop();
        mapErrorOverlayDelay.stop();
        stopNetworkMonitoring();
        scheduler.shutdownNow();
    }

    private void setupMapViewListener() {
        mapView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                onMapLoaded();
            } else if (newState == javafx.concurrent.Worker.State.FAILED) {
                mapLoaded = false;
                log.error("Map WebView failed to load.");
            }
        });
    }

    private void onMapLoaded() {
        if (cleanedUp) {
            return;
        }
        mapLoaded = true;

        Platform.runLater(() -> {
            JSObject win = (JSObject) mapView.getEngine().executeScript("window");
            win.setMember("javaApp", mapJsBridge);
        });

        configureMapTileSource();
        refreshMapSize();
        updateMapControlsLocalizedText();
        refreshTheme(ThemeManager.getTheme());
        if (currentGpsCoordinate != null || !gpsTimeline.isEmpty() || currentFile != null) {
            requestMapUpdate();
        }
    }

    public class MapJsBridge {
        public void onTileError() {
            Platform.runLater(() -> {
                if (cleanedUp) {
                    return;
                }
                log.trace("Map tile batch failed; delaying error overlay while retry checks run");
                scheduleMapErrorOverlay();
                startNetworkMonitoring();
            });
        }

        public void onTileRecovered() {
            Platform.runLater(() -> {
                if (cleanedUp) {
                    return;
                }
                hideMapErrorOverlay();
                stopNetworkMonitoring();
            });
        }
    }

    private void configureMapTileSource() {
        try {
            String template = toJsString(mapTileCacheService.tileUrlTemplate());
            mapView.getEngine().executeScript("setTileUrlTemplate(" + template + ");");
        } catch (Exception e) {
            log.error("Failed to configure the map tile session cache", e);
        }
    }

    private void loadMapHtml() {
        mapLoaded = false;
        URL mapHtml = getClass().getResource(MAP_HTML_RESOURCE);
        if (mapHtml == null) {
            log.error("Map HTML resource not found: {}", MAP_HTML_RESOURCE);
            showMapErrorOverlay();
            return;
        }
        mapView.getEngine().load(mapHtml.toExternalForm());
    }

    private void updateMapControlsLocalizedText() {
        if (!mapLoaded || mapView == null) {
            return;
        }
        String centerLabel = toJsString(I18n.get("media.viewer.location.center"));
        Platform.runLater(() -> {
            try {
                mapView.getEngine().executeScript("updateCenterButtonLabel(" + centerLabel + ");");
            } catch (Exception e) {
                log.error("Failed to update center button label", e);
            }
        });
    }

    private String toJsString(String value) {
        StringBuilder escaped = new StringBuilder("'");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '\'' -> escaped.append("\\'");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(ch);
            }
        }
        return escaped.append('\'').toString();
    }

    private void requestMapUpdate() {
        mapDebouncer.playFromStart();
    }

    private void updateMapDisplay() {
        if (!mapLoaded || mapView == null) {
            log.warn("Skipping updateMapDisplay: mapLoader is false or mapView is null");
            return;
        }

        Platform.runLater(this::updateMapDisplayOnFxThread);
    }

    private void updateMapDisplayOnFxThread() {
        if (!isLeafletReady()) {
            log.debug("Leaflet not ready; delaying error overlay while retry checks run");
            scheduleMapErrorOverlay();
            startNetworkMonitoring();
            return;
        }

        hideMapErrorOverlay();
        clearMapDisplay();
        renderMapContent();
        startMapTileLoading();
    }

    private void startMapTileLoading() {
        try {
            mapView.getEngine().executeScript("startMapTiles();");
        } catch (Exception e) {
            log.error("Failed to start map tile loading", e);
            scheduleMapErrorOverlay();
        }
    }

    private void hideMapErrorOverlay() {
        mapErrorOverlayDelay.stop();
        if (mapErrorPane != null) {
            mapErrorPane.setVisible(false);
            mapErrorPane.setManaged(false);
        }
    }

    private void scheduleMapErrorOverlay() {
        if (mapErrorPane != null && mapErrorPane.isVisible()) {
            return;
        }
        mapErrorOverlayDelay.playFromStart();
    }

    private void clearMapDisplay() {
        try {
            mapView.getEngine().executeScript("clearAll(); hideNoDataMessage();");
        } catch (Exception e) {
            log.error("Failed to clear map", e);
        }
    }

    private void renderMapContent() {
        try {
            if (hasTimeline()) {
                renderTimelineMarkers();
            } else if (currentGpsCoordinate != null) {
                renderSingleGpsPoint(currentGpsCoordinate);
            } else if (!gpsMetadataLoading) {
                renderDefaultMapView();
            }
        } catch (Exception e) {
            log.error("Error rendering map content", e);
            showMapErrorOverlay();
        }
    }

    private void showMapErrorOverlay() {
        if (mapErrorPane != null) {
            mapErrorPane.setVisible(true);
            mapErrorPane.setManaged(true);
            if (mapErrorLabel != null) {
                mapErrorLabel.setText(I18n.get("media.viewer.map.error"));
            }
        }
    }

    private boolean hasTimeline() {
        return gpsTimeline != null && !gpsTimeline.isEmpty();
    }

    private void renderTimelineMarkers() {
        if (!gpsTimeline.isEmpty()) {
            GpsCoordinate first = gpsTimeline.getFirst().toCoordinate();
            extendLogicalGpsPathThrough(computeIntervalIndex(currentVideoSeconds));
            redrawLogicalPath();
            renderStartMarker(first);
            renderCurrentMarkerForTimeline(first);
        }
    }

    private void renderCurrentMarkerForTimeline(GpsCoordinate defaultCoord) {
        if (defaultCoord == null) {
            log.warn("renderCurrentMarkerForTimeline called with null coordinate");
            return;
        }
        try {
            GpsPoint current = findGpsAtOrBefore(currentVideoSeconds);
            GpsCoordinate fallback = (current != null) ? current.toCoordinate() : defaultCoord;
            GpsCoordinate coord = currentLogicalCoordinate(currentVideoSeconds, fallback);
            renderCurrentMarker(coord);
            mapView.getEngine().executeScript(
                    String.format(Locale.US, "map.setView([%f, %f], 15);", coord.latitude(), coord.longitude()));
        } catch (Exception e) {
            log.error("Error in renderCurrentMarkerForTimeline", e);
        }
    }

    private void renderDefaultMapView() {
        try {
            String msg = I18n.get("media.viewer.map.no.gps");
            mapView.getEngine().executeScript("showNoDataMessage(" + toJsString(msg) + ");");
            mapView.getEngine().executeScript("map.setView([20, 0], 1);");
        } catch (Exception e) {
            log.error("Error executing renderDefaultMapView script", e);
        }
    }

    private void renderStartMarker(GpsCoordinate coord) {
        if (coord == null) {
            log.warn("renderStartMarker called with null coordinate");
            return;
        }
        try {
            String js = String.format(Locale.US, "addStartMarker(%f, %f, %s);",
                    coord.latitude(), coord.longitude(), toJsString("Start: " + coord));
            mapView.getEngine().executeScript(js);
        } catch (Exception e) {
            log.error("Error executing renderStartMarker script", e);
        }
    }

    private void renderCurrentMarker(GpsCoordinate coord) {
        if (coord == null) {
            log.warn("renderCurrentMarker called with null coordinate");
            return;
        }
        String js = String.format(Locale.US,
                UPDATE_CURRENT_MARKER_SCRIPT,
                coord.latitude(), coord.longitude(),
                coord.latitude(), coord.longitude());
        try {
            mapView.getEngine().executeScript(js);
        } catch (Exception e) {
            log.error("Error executing renderCurrentMarker script", e);
        }
    }

    private void renderSingleGpsPoint(GpsCoordinate coord) {
        if (coord == null) {
            log.warn("renderSingleGpsPoint called with null coordinate");
            return;
        }
        if (mapView == null) {
            log.warn("mapView is null, skipping renderSingleGpsPoint");
            return;
        }

        mapView.getEngine().executeScript(
                String.format(Locale.US, UPDATE_CURRENT_MARKER_SCRIPT,
                        coord.latitude(), coord.longitude(),
                        coord.latitude(), coord.longitude()));

        mapView.getEngine().executeScript(
                String.format(Locale.US, "map.setView([%f, %f], 15);",
                        coord.latitude(), coord.longitude()));
    }

    private String toJsPointsArray(int endIndex) {
        StringBuilder js = new StringBuilder("[");
        for (int i = 0; i <= endIndex; i++) {
            GpsCoordinate point = logicalGpsPath.get(i).coordinate();
            if (i > 0) {
                js.append(',');
            }
            js.append(String.format(Locale.US, "[%f,%f]", point.latitude(), point.longitude()));
        }
        return js.append(']').toString();
    }

    private GpsPoint findGpsAtOrBefore(double seconds) {
        if (gpsTimeline == null || gpsTimeline.isEmpty()) {
            return null;
        }

        GpsPoint first = gpsTimeline.getFirst();
        if (seconds <= first.timeSeconds()) {
            return first;
        }

        GpsPoint last = gpsTimeline.getLast();
        if (seconds >= last.timeSeconds()) {
            return last;
        }

        int low = 0;
        int high = gpsTimeline.size() - 1;
        int result = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (gpsTimeline.get(middle).timeSeconds() <= seconds) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return gpsTimeline.get(result);
    }

    private boolean isSeeked(double currentSeconds) {
        return lastVideoSeconds >= 0 && Math.abs(currentSeconds - lastVideoSeconds) > SEEK_THRESHOLD;
    }

    private int computeIntervalIndex(double currentSeconds) {
        return Math.max(0, (int) (currentSeconds / LOGICAL_PATH_SAMPLE_SECONDS));
    }

    private void redrawLogicalPath() {
        if (!mapLoaded || mapView == null) {
            log.warn("Skipping redrawLogicalPath: mapLoader is false or mapView is null");
            return;
        }
        if (!isLeafletReady()) {
            log.warn("Skipping redrawLogicalPath: Leaflet not ready");
            return;
        }
        try {
            int endIndex = logicalPointIndexAt(currentVideoSeconds);
            mapView.getEngine().executeScript("resetLogicalPath(" + toJsPointsArray(endIndex) + ");");
            lastRenderedLogicalPointIndex = endIndex;
        } catch (Exception e) {
            log.error("Failed to redraw logical GPS path", e);
        }
    }

    private void appendLogicalPathThrough(double seconds) {
        int endIndex = logicalPointIndexAt(seconds);
        if (endIndex <= lastRenderedLogicalPointIndex) {
            return;
        }
        if (!mapLoaded || mapView == null) {
            log.warn("Skipping appendLogicalPathThrough: mapLoader is false or mapView is null");
            return;
        }
        if (!isLeafletReady()) {
            return;
        }
        try {
            for (int index = lastRenderedLogicalPointIndex + 1; index <= endIndex; index++) {
                GpsCoordinate coord = logicalGpsPath.get(index).coordinate();
                String js = String.format(Locale.US, "appendLogicalPathPoint(%f, %f);",
                        coord.latitude(), coord.longitude());
                mapView.getEngine().executeScript(js);
            }
            lastRenderedLogicalPointIndex = endIndex;
        } catch (Exception e) {
            log.error("JS error appending logical GPS path", e);
        }
    }

    private void updateCurrentGpsMarker(GpsCoordinate coord) {
        locationGps.setText(coord.toString());
        if (!mapLoaded || mapView == null) {
            log.warn("Skipping updateCurrentGpsMarker: mapLoader is false or mapView is null");
            return;
        }
        String js = String.format(Locale.US,
                UPDATE_CURRENT_MARKER_SCRIPT,
                coord.latitude(), coord.longitude(),
                coord.latitude(), coord.longitude());
        if (!isLeafletReady()) {
            return;
        }
        Platform.runLater(() -> {
            try {
                mapView.getEngine().executeScript(js);
            } catch (Exception e) {
                log.error("JS error updating current marker", e);
            }
        });
    }

    private void extendLogicalGpsPathThrough(long targetSampleIndex) {
        long firstSampleIndex = Math.max(0, lastBuiltLogicalSampleIndex + 1);
        for (long sampleIndex = firstSampleIndex; sampleIndex <= targetSampleIndex; sampleIndex++) {
            addLogicalPointAtSample(sampleIndex);
        }
        lastBuiltLogicalSampleIndex = Math.max(lastBuiltLogicalSampleIndex, targetSampleIndex);
    }

    private void addLogicalPointAtSample(long sampleIndex) {
        GpsPoint point = findGpsAtOrBefore(sampleIndex * LOGICAL_PATH_SAMPLE_SECONDS);
        if (point == null) {
            return;
        }
        addLogicalPoint(sampleIndex * LOGICAL_PATH_SAMPLE_SECONDS, point.toCoordinate());
    }

    private void addLogicalPoint(double timeSeconds, GpsCoordinate coord) {
        if (shouldRecordLogicalPoint(coord)) {
            logicalGpsPath.add(new TimedGpsCoordinate(timeSeconds, coord));
        }
    }

    private GpsCoordinate currentLogicalCoordinate(double seconds, GpsCoordinate fallback) {
        int index = logicalPointIndexAt(seconds);
        return index < 0 ? fallback : logicalGpsPath.get(index).coordinate();
    }

    private boolean shouldRecordLogicalPoint(GpsCoordinate coord) {
        if (coord == null) {
            return false;
        }
        if (logicalGpsPath.isEmpty()) {
            return true;
        }
        GpsCoordinate last = logicalGpsPath.getLast().coordinate();
        return distanceMeters(last, coord) >= MIN_LOGICAL_POINT_DISTANCE_METERS;
    }

    private int logicalPointIndexAt(double seconds) {
        int low = 0;
        int high = logicalGpsPath.size() - 1;
        int result = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (logicalGpsPath.get(middle).timeSeconds() <= seconds) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    private void resetLogicalGpsPath() {
        logicalGpsPath.clear();
        lastGpsIntervalIndex = -1;
        lastBuiltLogicalSampleIndex = -1;
        lastRenderedLogicalPointIndex = -1;
        lastVideoSeconds = -1;
    }

    private double distanceMeters(GpsCoordinate a, GpsCoordinate b) {
        double earthRadiusMeters = 6_371_000.0;
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                        * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 2 * earthRadiusMeters * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    private List<GpsPoint> normalizeGpsTimeline(List<GpsPoint> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return new ArrayList<>();
        }
        List<GpsPoint> normalized = new ArrayList<>();
        for (GpsPoint point : timeline) {
            if (point != null
                    && Double.isFinite(point.timeSeconds())
                    && Double.isFinite(point.latitude())
                    && Double.isFinite(point.longitude())) {
                normalized.add(point);
            }
        }
        normalized.sort(Comparator.comparingDouble(GpsPoint::timeSeconds));
        return normalized;
    }

    private void startNetworkMonitoring() {
        if (cleanedUp || (monitorTask != null && !monitorTask.isCancelled())) {
            return;
        }
        logNetworkMonitorStarted();
        monitorTask = scheduler.scheduleWithFixedDelay(this::checkForNetworkRecovery, 0, 2, TimeUnit.SECONDS);
    }

    private void checkForNetworkRecovery() {
        if (isNetworkAvailable()) {
            Platform.runLater(this::retryMapAfterNetworkRecovery);
        }
    }

    private void retryMapAfterNetworkRecovery() {
        if (cleanedUp) {
            return;
        }
        log.debug("Network recovered, stopping monitoring and retrying map tiles");
        stopNetworkMonitoring();
        if (mapView == null) {
            return;
        }
        try {
            if (isLeafletReady()) {
                mapView.getEngine().executeScript("retryMapTiles();");
            } else {
                loadMapHtml();
            }
        } catch (Exception e) {
            log.error("Failed to retry map tiles", e);
        }
    }

    private void logNetworkMonitorStarted() {
        long now = System.nanoTime();
        if (now - lastNetworkMonitorStartLogNanos < NETWORK_MONITOR_START_LOG_COOLDOWN_NANOS) {
            return;
        }
        lastNetworkMonitorStartLogNanos = now;
        log.debug("Starting network monitoring");
    }

    private void stopNetworkMonitoring() {
        if (monitorTask != null) {
            monitorTask.cancel(false);
            monitorTask = null;
        }
    }

    private boolean isNetworkAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(MAP_TILE_HOST, MAP_TILE_HTTPS_PORT), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLeafletReady() {
        if (!mapLoaded || mapView == null) {
            return false;
        }
        try {
            Object ready = mapView.getEngine().executeScript("isMapReady();");
            return Boolean.TRUE.equals(ready);
        } catch (Exception e) {
            return false;
        }
    }
}
