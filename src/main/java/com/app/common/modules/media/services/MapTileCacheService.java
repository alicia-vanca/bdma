package com.app.common.modules.media.services;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppDataPaths;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import jakarta.annotation.PreDestroy;

@Service
public class MapTileCacheService {

    private static final Logger log = LoggerFactory.getLogger(MapTileCacheService.class);
    private static final Pattern TILE_PATH = Pattern.compile("^/tiles/(\\d+)/(\\d+)/(\\d+)\\.png$");
    private static final String UPSTREAM_TEMPLATE = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final String UPSTREAM_PROBE_URL = "https://tile.openstreetmap.org/0/0/0.png";
    private static final byte[] EMPTY_TILE = new byte[0];
    private static final int MAX_CONCURRENT_REQUESTS = 16;
    private static final long UPSTREAM_PROBE_INTERVAL_SECONDS = 1;

    private final Path cacheDirectory;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ScheduledExecutorService connectivityExecutor;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, Object> tileLocks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Boolean>> upstreamStateListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean upstreamOnline = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object connectivityLock = new Object();
    private ScheduledFuture<?> connectivityProbeTask;

    public MapTileCacheService() {
        try {
            cacheDirectory = Path.of(AppDataPaths.appTmpDir(), "map-tiles");
            clearCacheDirectory();
            Files.createDirectories(cacheDirectory);
            AtomicInteger workerSequence = new AtomicInteger();
            executor = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS, r -> {
                Thread thread = new Thread(r, "MapTileCache-" + workerSequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            connectivityExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "MapTileConnectivity");
                thread.setDaemon(true);
                return thread;
            });
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/tiles/", this::handleTileRequest);
            server.setExecutor(executor);
            server.start();
            log.info("Map tile session cache started. directory={}, port={}",
                    cacheDirectory, server.getAddress().getPort());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize map tile session cache", e);
        }
    }

    public String tileUrlTemplate() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/tiles/{z}/{x}/{y}.png";
    }

    public void addUpstreamStateListener(Consumer<Boolean> listener) {
        synchronized (connectivityLock) {
            upstreamStateListeners.add(listener);
            if (!upstreamOnline.get()) {
                listener.accept(false);
            }
        }
    }

    public void removeUpstreamStateListener(Consumer<Boolean> listener) {
        upstreamStateListeners.remove(listener);
    }

    public void reportUpstreamUnavailable() {
        synchronized (connectivityLock) {
            if (closed.get()) {
                return;
            }
            if (upstreamOnline.compareAndSet(true, false)) {
                notifyUpstreamStateListeners(false);
            }
            if (connectivityProbeTask == null || connectivityProbeTask.isDone()) {
                connectivityProbeTask = connectivityExecutor.scheduleWithFixedDelay(
                        this::probeUpstream,
                        UPSTREAM_PROBE_INTERVAL_SECONDS,
                        UPSTREAM_PROBE_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);
            }
        }
    }

    private void handleTileRequest(HttpExchange exchange) {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendStatus(exchange, 405);
                return;
            }

            Matcher matcher = TILE_PATH.matcher(exchange.getRequestURI().getPath());
            if (!matcher.matches()) {
                sendStatus(exchange, 404);
                return;
            }

            int zoom = Integer.parseInt(matcher.group(1));
            int x = Integer.parseInt(matcher.group(2));
            int y = Integer.parseInt(matcher.group(3));
            if (!isValidTile(zoom, x, y)) {
                sendStatus(exchange, 400);
                return;
            }

            String key = zoom + "/" + x + "/" + y;
            Path tilePath = cacheDirectory.resolve(Integer.toString(zoom))
                    .resolve(Integer.toString(x))
                    .resolve(y + ".png");
            serveTile(exchange, key, tilePath, zoom, x, y);
        } catch (IOException ignored) {
            // The WebView may cancel outstanding requests while moving or closing.
        } catch (RuntimeException e) {
            log.error("Unexpected map tile proxy failure", e);
        }
    }

    private void serveTile(HttpExchange exchange, String key, Path tilePath, int zoom, int x, int y)
            throws IOException {
        byte[] bytes;
        try {
            bytes = loadTile(key, tilePath, zoom, x, y);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendStatusIfPossible(exchange, 503);
            return;
        } catch (IOException e) {
            log.warn("Map tile cache operation failed. tile={}", key, e);
            sendStatusIfPossible(exchange, 502);
            return;
        }
        if (bytes.length == 0) {
            sendStatus(exchange, 502);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private byte[] loadTile(String key, Path tilePath, int zoom, int x, int y)
            throws IOException, InterruptedException {
        if (Files.isRegularFile(tilePath)) {
            log.trace("Serving cached map tile. tile={}, path={}", key, tilePath);
            return Files.readAllBytes(tilePath);
        }

        Object lock = tileLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                if (Files.isRegularFile(tilePath)) {
                    log.trace("Serving cached map tile. tile={}, path={}", key, tilePath);
                    return Files.readAllBytes(tilePath);
                }

                if (!upstreamOnline.get()) {
                    return EMPTY_TILE;
                }

                byte[] bytes = downloadTile(zoom, x, y);
                if (bytes.length == 0) {
                    return EMPTY_TILE;
                }

                Files.createDirectories(tilePath.getParent());
                Path temporary = Files.createTempFile(tilePath.getParent(), y + "-", ".tmp");
                Files.write(temporary, bytes);
                moveIntoCache(temporary, tilePath);
                log.trace("Cached map tile. tile={}, path={}", key, tilePath);
                return bytes;
            }
        } finally {
            tileLocks.remove(key, lock);
        }
    }

    private byte[] downloadTile(int zoom, int x, int y) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(UPSTREAM_TEMPLATE.formatted(zoom, x, y)))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "BDMA-MediaViewer")
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            return EMPTY_TILE;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length == 0) {
            return EMPTY_TILE;
        }
        markUpstreamOnline();
        return response.body();
    }

    private void probeUpstream() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(UPSTREAM_PROBE_URL))
                .timeout(Duration.ofSeconds(1))
                .header("User-Agent", "BDMA-MediaViewer")
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                markUpstreamOnline();
            }
        } catch (IOException e) {
            log.trace("Map tile upstream probe failed: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void markUpstreamOnline() {
        synchronized (connectivityLock) {
            if (!upstreamOnline.compareAndSet(false, true)) {
                return;
            }
            if (connectivityProbeTask != null) {
                connectivityProbeTask.cancel(false);
                connectivityProbeTask = null;
            }
            notifyUpstreamStateListeners(true);
        }
    }

    private void notifyUpstreamStateListeners(boolean online) {
        for (Consumer<Boolean> listener : upstreamStateListeners) {
            try {
                listener.accept(online);
            } catch (RuntimeException e) {
                log.warn("Map tile upstream-state listener failed", e);
            }
        }
    }

    private void moveIntoCache(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean isValidTile(int zoom, int x, int y) {
        if (zoom < 0 || zoom > 19 || x < 0 || y < 0) {
            return false;
        }
        int limit = 1 << zoom;
        return x < limit && y < limit;
    }

    private void sendStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private void sendStatusIfPossible(HttpExchange exchange, int status) {
        try {
            sendStatus(exchange, status);
        } catch (IOException ignored) {
            // The response may already have started or the client may have disconnected.
        }
    }

    @PreDestroy
    public void close() {
        closed.set(true);
        server.stop(0);
        executor.shutdownNow();
        connectivityExecutor.shutdownNow();
        clearCacheDirectory();
    }

    private void clearCacheDirectory() {
        if (!Files.exists(cacheDirectory)) {
            return;
        }
        try (var paths = Files.walk(cacheDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Unable to delete map tile cache path: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Unable to clear map tile session cache: {}", cacheDirectory, e);
        }
    }
}
