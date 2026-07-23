package com.app.common.modules.media.controllers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.app.common.dtos.FileView;
import com.app.common.helpers.NativeRuntimePathResolver;
import com.sun.jna.NativeLibrary;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;
import uk.co.caprica.vlcj.binding.lib.LibC;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.log.LogLevel;
import uk.co.caprica.vlcj.log.NativeLog;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

/**
 * Owns the libVLC lifecycle and playback controls for the media viewer.
 */
final class MediaPlaybackController {

    private static final Logger log = LoggerFactory.getLogger(MediaPlaybackController.class);
    private static final long END_TOLERANCE_MILLIS = 100;
    private static final String LIBVLC_LIBRARY_NAME = "libvlc";
    private static final Pattern SELECTED_MODULE_PATTERN = Pattern.compile(
            "using\\s+(?<type>.+?)\\s+module\\s+[\\\"'](?<name>[^\\\"']+)[\\\"']",
            Pattern.CASE_INSENSITIVE);

    private final VBox videoPane;
    private final StackPane videoContentPane;
    private final Pane mediaViewWrapper;
    private final ImageView videoImageView;
    private final Button btnPlayPause;
    private final Button btnMute;
    private final Slider videoSlider;
    private final Slider volumeSlider;
    private final Label lblVideoTime;
    private final ComboBox<String> cbSpeed;
    private final Label lblAudioPlaceholder;
    private final Label detailDimension;
    private final Predicate<String> isAudio;
    private final DoubleConsumer onPlaybackTimeChanged;

    private boolean sliderDragging;
    private boolean directSliderSeek;
    private boolean directVolumeAdjust;
    private boolean playing;
    private boolean playbackEnded;
    private boolean seekingAwayFromEnd;
    private Long pendingSeekAfterStartMillis;
    private double lastAudibleVolume = 1.0;
    private long currentMillis;
    private long totalMillis;
    private Popup seekTooltip;
    private Popup volumeTooltip;
    private Label seekTooltipLabel;
    private Label volumeTooltipLabel;
    private MediaPlayerFactory mediaPlayerFactory;
    private EmbeddedMediaPlayer mediaPlayer;
    private NativeLog nativeLog;
    private volatile Path bundledVlcDirectory;
    private final Set<String> observedVlcPlugins = ConcurrentHashMap.newKeySet();

    record PlaybackControllerDependencies(
            VBox videoPane,
            StackPane videoContentPane,
            Pane mediaViewWrapper,
            ImageView videoImageView,
            Button btnPlayPause,
            Button btnMute,
            Slider videoSlider,
            Slider volumeSlider,
            Label lblVideoTime,
            ComboBox<String> cbSpeed,
            Label lblAudioPlaceholder,
            Label detailDimension,
            Predicate<String> isAudio,
            DoubleConsumer onPlaybackTimeChanged) {
    }

    MediaPlaybackController(PlaybackControllerDependencies dependencies) {
        this.videoPane = dependencies.videoPane();
        this.videoContentPane = dependencies.videoContentPane();
        this.mediaViewWrapper = dependencies.mediaViewWrapper();
        this.videoImageView = dependencies.videoImageView();
        this.btnPlayPause = dependencies.btnPlayPause();
        this.btnMute = dependencies.btnMute();
        this.videoSlider = dependencies.videoSlider();
        this.volumeSlider = dependencies.volumeSlider();
        this.lblVideoTime = dependencies.lblVideoTime();
        this.cbSpeed = dependencies.cbSpeed();
        this.lblAudioPlaceholder = dependencies.lblAudioPlaceholder();
        this.detailDimension = dependencies.detailDimension();
        this.isAudio = dependencies.isAudio();
        this.onPlaybackTimeChanged = dependencies.onPlaybackTimeChanged();
    }

    void initialize() {
        seekTooltipLabel = createSliderTooltipLabel();
        volumeTooltipLabel = createSliderTooltipLabel();
        seekTooltip = createSliderTooltipPopup(seekTooltipLabel);
        volumeTooltip = createSliderTooltipPopup(volumeTooltipLabel);
        initializeVideoViewport();
        initializePlaybackControls();
        setupVolumeControls();
        setupSeekControls();
        attachPlaybackKeyboardShortcuts();
        videoImageView.setOnMouseClicked(e -> togglePlayback());
        resetPlaybackUi();
    }

    private void initializeVideoViewport() {
        videoImageView.setManaged(false);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(videoContentPane.widthProperty());
        clip.heightProperty().bind(videoContentPane.heightProperty());
        videoContentPane.setClip(clip);

        videoContentPane.widthProperty().addListener(
                (observable, oldWidth, newWidth) -> resizeVideoToPane());
        videoContentPane.heightProperty().addListener(
                (observable, oldHeight, newHeight) -> resizeVideoToPane());
        videoImageView.boundsInLocalProperty().addListener(
                (observable, oldBounds, newBounds) -> centerVideo(newBounds));
        videoImageView.imageProperty().addListener(
                (observable, oldImage, newImage) -> Platform.runLater(this::resizeVideoToPane));
    }

    private void resizeVideoToPane() {
        double width = videoContentPane.getWidth();
        double height = videoContentPane.getHeight();
        videoImageView.setFitWidth(width);
        videoImageView.setFitHeight(height);
        mediaViewWrapper.setPrefSize(width, height);
        mediaViewWrapper.setMaxSize(width, height);
        centerVideo(videoImageView.getBoundsInLocal());
    }

    private void centerVideo(Bounds bounds) {
        videoImageView.setLayoutX(centerOffset(videoContentPane.getWidth(), bounds.getWidth()));
        videoImageView.setLayoutY(centerOffset(videoContentPane.getHeight(), bounds.getHeight()));
    }

    void attach(FileView file, String mediaUri, Runnable onPlaybackError) {
        stop();
        configureUiForMediaType(isAudio.test(file.type()));

        EmbeddedMediaPlayer activePlayer = createMediaPlayer();
        mediaPlayer = activePlayer;
        try {
            activePlayer.videoSurface().set(new ImageViewVideoSurface(videoImageView));
            configurePlayerVolume(activePlayer);
            attachPlaybackEventHandlers(activePlayer, onPlaybackError);
            if (!activePlayer.media().play(mediaUri)) {
                throw new IllegalStateException("libVLC rejected media: " + mediaUri);
            }
        } catch (RuntimeException | LinkageError e) {
            stop();
            throw new IllegalStateException("Unable to start libVLC playback", e);
        }

        Platform.runLater(videoPane::requestFocus);
    }

    void togglePlayback() {
        EmbeddedMediaPlayer activePlayer = mediaPlayer;
        if (activePlayer == null) {
            return;
        }
        currentMillis = readCurrentMillis();
        if (playbackEnded || isAtEnd()) {
            restartPlaybackFromBeginning(activePlayer);
        } else if (playing) {
            pausePlayback(activePlayer);
        } else {
            startPlayback(activePlayer);
        }
    }

    void seekBy(int seconds) {
        if (mediaPlayer == null || totalMillis <= 0) {
            return;
        }
        if (playbackEnded && seconds > 0) {
            return;
        }
        long baseMillis = playbackEnded ? totalMillis : readCurrentMillis();
        seekTo(clampSeekTargetMillis(baseMillis, seconds * 1000L, totalMillis));
    }

    void changeSpeed() {
        EmbeddedMediaPlayer activePlayer = mediaPlayer;
        if (activePlayer == null || cbSpeed.getValue() == null) {
            return;
        }
        try {
            float rate = Float.parseFloat(cbSpeed.getValue().replace("x", ""));
            if (!activePlayer.controls().setRate(rate)) {
                log.warn("libVLC rejected playback rate: {}", rate);
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid speed format: {}", cbSpeed.getValue());
        }
    }

    void toggleMute() {
        EmbeddedMediaPlayer activePlayer = mediaPlayer;
        if (activePlayer == null) {
            return;
        }
        boolean silent = activePlayer.audio().isMute() || volumeSlider.getValue() <= 0;
        if (silent) {
            double restoredVolume = lastAudibleVolume > 0 ? lastAudibleVolume : 1.0;
            activePlayer.audio().setMute(false);
            volumeSlider.setValue(restoredVolume);
        } else {
            lastAudibleVolume = volumeSlider.getValue();
            activePlayer.audio().setMute(true);
            volumeSlider.setValue(0);
        }
        updateMuteButton();
    }

    void stop() {
        EmbeddedMediaPlayer activePlayer = mediaPlayer;
        mediaPlayer = null;
        if (activePlayer != null) {
            try {
                activePlayer.controls().stop();
            } catch (RuntimeException e) {
                log.debug("Failed to stop libVLC player cleanly", e);
            } finally {
                try {
                    activePlayer.release();
                } catch (RuntimeException | LinkageError e) {
                    log.warn("Failed to release libVLC player", e);
                }
            }
        }
        videoImageView.setImage(null);
        videoImageView.setLayoutX(0);
        videoImageView.setLayoutY(0);
        resetPlaybackUi();
        if (seekTooltip != null) {
            seekTooltip.hide();
        }
        if (volumeTooltip != null) {
            volumeTooltip.hide();
        }
    }

    void release() {
        stop();
        NativeLog activeNativeLog = nativeLog;
        nativeLog = null;
        if (activeNativeLog != null) {
            try {
                activeNativeLog.release();
            } catch (RuntimeException | LinkageError e) {
                log.warn("Failed to release libVLC native log", e);
            }
        }
        MediaPlayerFactory activeFactory = mediaPlayerFactory;
        mediaPlayerFactory = null;
        if (activeFactory != null) {
            try {
                activeFactory.release();
            } catch (RuntimeException | LinkageError e) {
                log.warn("Failed to release libVLC factory", e);
            }
        }
    }

    private EmbeddedMediaPlayer createMediaPlayer() {
        try {
            if (mediaPlayerFactory == null) {
                Path vlcDirectory = resolveBundledVlcDirectory();
                configureBundledVlcRuntime(vlcDirectory);
                mediaPlayerFactory = new MediaPlayerFactory(
                        (NativeDiscovery) null, "--no-video-title-show", "--verbose=2");
                bundledVlcDirectory = vlcDirectory;
                nativeLog = new NativeLog(mediaPlayerFactory.getLibVlcInstance().get());
                nativeLog.setLevel(LogLevel.DEBUG);
                nativeLog.addLogListener(this::handleVlcNativeLog);
                log.info("Using bundled libVLC runtime: {}", vlcDirectory);
            }
            return mediaPlayerFactory.mediaPlayers().newEmbeddedMediaPlayer();
        } catch (RuntimeException | LinkageError e) {
            throw new IllegalStateException("Unable to initialize bundled libVLC", e);
        }
    }

    private static void configureBundledVlcRuntime(Path vlcDirectory) {
        NativeLibrary.addSearchPath(LIBVLC_LIBRARY_NAME, vlcDirectory.toString());
        String pluginPath = vlcDirectory.resolve("plugins").toString();
        if (LibC.INSTANCE._putenv("VLC_PLUGIN_PATH=" + pluginPath) != 0) {
            throw new IllegalStateException("Unable to configure bundled libVLC plugin path");
        }
    }

    static SelectedVlcModule parseSelectedVlcModule(String message) {
        Matcher matcher = SELECTED_MODULE_PATTERN.matcher(message == null ? "" : message);
        return matcher.find()
                ? new SelectedVlcModule(matcher.group("type").trim(), matcher.group("name").trim())
                : null;
    }

    static String pluginCategoryForModuleType(String type) {
        String normalizedType = type.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').trim();
        if (normalizedType.equals("decoder") || normalizedType.endsWith(" decoder")
                || normalizedType.equals("encoder") || normalizedType.endsWith(" encoder")) {
            return "codec";
        }
        return switch (normalizedType) {
        case "access" -> "access";
        case "access output" -> "access_output";
        case "audio filter" -> "audio_filter";
        case "audio mixer" -> "audio_mixer";
        case "audio output" -> "audio_output";
        case "demux", "demuxer" -> "demux";
        case "packetizer" -> "packetizer";
        case "spu", "subtitle" -> "spu";
        case "stream filter" -> "stream_filter";
        case "stream output", "stream out" -> "stream_out";
        case "text renderer" -> "text_renderer";
        case "video converter", "video chroma" -> "video_chroma";
        case "video filter" -> "video_filter";
        case "video output" -> "video_output";
        default -> null;
        };
    }

    private void handleVlcNativeLog(LogLevel level, String module, String file, Integer line,
            String name, String header, Integer id, String message) {
        SelectedVlcModule selectedModule = parseSelectedVlcModule(message);
        if (selectedModule != null) {
            String pluginKey = selectedModule.type() + "|" + selectedModule.name();
            if (observedVlcPlugins.add(pluginKey)) {
                Path pluginDll = resolveVlcPluginDll(selectedModule);
                log.info("VLC_RUNTIME_PLUGIN [{}] type={} module={} dll={} message={}",
                        level, selectedModule.type(), selectedModule.name(),
                        pluginDll == null ? "unresolved" : bundledVlcDirectory.relativize(pluginDll), message);
            }
        }
        if (level == LogLevel.WARNING || level == LogLevel.ERROR) {
            log.warn("VLC_NATIVE_LOG [{}] module={} message={}", level, module, message);
        }
    }

    private Path resolveVlcPluginDll(SelectedVlcModule selectedModule) {
        Path root = bundledVlcDirectory;
        if (root == null) {
            return null;
        }
        String fileName = switch (selectedModule.name()) {
        case "d3d11_filters" -> "libdirect3d11_filters_plugin.dll";
        case "d3d9_filters" -> "libdirect3d9_filters_plugin.dll";
        default -> "lib" + selectedModule.name() + "_plugin.dll";
        };
        String category = pluginCategoryForModuleType(selectedModule.type());
        if (category != null) {
            Path candidate = root.resolve("plugins").resolve(category).resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        try (var pluginFiles = Files.walk(root.resolve("plugins"), 2)) {
            return pluginFiles
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.debug("Failed to resolve libVLC plugin DLL: {}", fileName, e);
            return null;
        }
    }

    record SelectedVlcModule(String type, String name) {}

    private static Path resolveBundledVlcDirectory() {
        return NativeRuntimePathResolver.resolve(
                MediaPlaybackController.class, "vlc", "libVLC", MediaPlaybackController::isCompleteVlcDirectory);
    }

    static Path findBundledVlcDirectory(List<Path> candidates) {
        return NativeRuntimePathResolver.find(candidates, "libVLC", MediaPlaybackController::isCompleteVlcDirectory);
    }

    private static boolean isCompleteVlcDirectory(Path directory) {
        return Files.isRegularFile(directory.resolve("libvlc.dll"))
                && Files.isRegularFile(directory.resolve("libvlccore.dll"))
                && Files.isDirectory(directory.resolve("plugins"));
    }

    static double centerOffset(double containerSize, double contentSize) {
        return Math.max(0, (containerSize - contentSize) / 2);
    }

    private void configureUiForMediaType(boolean audio) {
        mediaViewWrapper.setVisible(!audio);
        mediaViewWrapper.setManaged(!audio);
        lblAudioPlaceholder.setVisible(audio);
        lblAudioPlaceholder.setManaged(audio);
        if (!audio) {
            resizeVideoToPane();
        }
    }

    private void initializePlaybackControls() {
        if (cbSpeed.getItems().isEmpty()) {
            cbSpeed.getItems().addAll("0.25x", "0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x");
        }
        cbSpeed.setValue("1x");
    }

    private void configurePlayerVolume(EmbeddedMediaPlayer activePlayer) {
        activePlayer.audio().setVolume(toVlcVolume(volumeSlider.getValue()));
        activePlayer.audio().setMute(false);
        updateMuteButton();
    }

    private void attachPlaybackEventHandlers(EmbeddedMediaPlayer activePlayer, Runnable onPlaybackError) {
        activePlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void mediaPlayerReady(MediaPlayer player) {
                runOnFxIfActive(activePlayer, () -> {
                    updateTotalMillis(activePlayer.status().length());
                    updateDetailDimensions(activePlayer);
                });
            }

            @Override
            public void lengthChanged(MediaPlayer player, long newLength) {
                runOnFxIfActive(activePlayer, () -> updateTotalMillis(newLength));
            }

            @Override
            public void playing(MediaPlayer player) {
                runOnFxIfActive(activePlayer, () -> {
                    playing = true;
                    playbackEnded = false;
                    btnPlayPause.setText("⏸");
                    Long pendingSeekMillis = pendingSeekAfterStartMillis;
                    if (pendingSeekMillis != null) {
                        pendingSeekAfterStartMillis = null;
                        activePlayer.controls().setTime(pendingSeekMillis);
                    }
                });
            }

            @Override
            public void paused(MediaPlayer player) {
                runOnFxIfActive(activePlayer, () -> {
                    if (!playbackEnded) {
                        playing = false;
                        currentMillis = readCurrentMillis();
                        btnPlayPause.setText("▶");
                        renderPlaybackPosition();
                    }
                });
            }

            @Override
            public void timeChanged(MediaPlayer player, long newTime) {
                runOnFxIfActive(activePlayer, () -> handleTimeChanged(newTime));
            }

            @Override
            public void finished(MediaPlayer player) {
                runOnFxIfActive(activePlayer, MediaPlaybackController.this::handlePlaybackFinished);
            }

            @Override
            public void videoOutput(MediaPlayer player, int newCount) {
                runOnFxIfActive(activePlayer, () -> updateDetailDimensions(activePlayer));
            }

            @Override
            public void error(MediaPlayer player) {
                runOnFxIfActive(activePlayer, () -> {
                    log.error("libVLC playback error");
                    stop();
                    onPlaybackError.run();
                });
            }
        });
    }

    private void runOnFxIfActive(EmbeddedMediaPlayer activePlayer, Runnable action) {
        Platform.runLater(() -> {
            if (activePlayer == mediaPlayer) {
                action.run();
            }
        });
    }

    private void handleTimeChanged(long newTime) {
        currentMillis = clampPlaybackMillis(newTime);
        if (seekingAwayFromEnd && currentMillis < Math.max(0, totalMillis - END_TOLERANCE_MILLIS)) {
            seekingAwayFromEnd = false;
            playbackEnded = false;
        }
        if (!sliderDragging) {
            renderPlaybackPosition();
            onPlaybackTimeChanged.accept(currentMillis / 1000.0);
        }
    }

    private void handlePlaybackFinished() {
        if (seekingAwayFromEnd) {
            return;
        }
        playing = false;
        playbackEnded = true;
        currentMillis = totalMillis;
        btnPlayPause.setText("▶");
        renderPlaybackPosition();
        onPlaybackTimeChanged.accept(currentMillis / 1000.0);
    }

    private void updateTotalMillis(long newLength) {
        if (newLength <= 0) {
            return;
        }
        totalMillis = newLength;
        currentMillis = clampPlaybackMillis(currentMillis);
        renderPlaybackPosition();
    }

    private void updateDetailDimensions(EmbeddedMediaPlayer activePlayer) {
        try {
            java.awt.Dimension dimensions = activePlayer.video().videoDimension();
            if (dimensions != null && dimensions.width > 0 && dimensions.height > 0) {
                detailDimension.setText(dimensions.width + " × " + dimensions.height);
            }
        } catch (RuntimeException e) {
            log.debug("Video dimensions are not available yet", e);
        }
    }

    private void setupVolumeControls() {
        volumeSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            double volume = newValue.doubleValue();
            if (volume > 0) {
                lastAudibleVolume = volume;
            }
            EmbeddedMediaPlayer activePlayer = mediaPlayer;
            if (activePlayer != null) {
                activePlayer.audio().setVolume(toVlcVolume(volume));
                if (volume > 0 && activePlayer.audio().isMute()) {
                    activePlayer.audio().setMute(false);
                }
            }
            updateMuteButton();
        });
        volumeSlider.setOnMousePressed(e -> {
            directVolumeAdjust = !isSliderThumb(e.getTarget());
            if (directVolumeAdjust) {
                updateSliderFromPointer(volumeSlider, e);
            }
            showSliderTooltip(volumeTooltip, volumeTooltipLabel, volumeSlider, formatVolumeTooltip());
        });
        volumeSlider.setOnMouseDragged(e -> {
            if (directVolumeAdjust) {
                updateSliderFromPointer(volumeSlider, e);
            }
            showSliderTooltip(volumeTooltip, volumeTooltipLabel, volumeSlider, formatVolumeTooltip());
        });
        volumeSlider.setOnMouseReleased(e -> {
            directVolumeAdjust = false;
            volumeTooltip.hide();
        });
    }

    private void setupSeekControls() {
        videoSlider.setOnMousePressed(e -> {
            sliderDragging = true;
            directSliderSeek = !isSliderThumb(e.getTarget());
            if (directSliderSeek) {
                updateSliderFromPointer(videoSlider, e);
            }
            showSliderTooltip(seekTooltip, seekTooltipLabel, videoSlider, formatSeekTooltip());
        });
        videoSlider.setOnMouseDragged(e -> {
            if (directSliderSeek) {
                updateSliderFromPointer(videoSlider, e);
            }
            showSliderTooltip(seekTooltip, seekTooltipLabel, videoSlider, formatSeekTooltip());
        });
        videoSlider.setOnMouseReleased(e -> {
            sliderDragging = false;
            directSliderSeek = false;
            seekTooltip.hide();
            if (mediaPlayer != null && totalMillis > 0) {
                seekTo(sliderTargetMillis(totalMillis, videoSlider.getValue()));
            }
        });
    }

    private void attachPlaybackKeyboardShortcuts() {
        videoPane.setFocusTraversable(true);
        videoPane.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case LEFT -> seekBy(-5);
                case RIGHT -> seekBy(5);
                default -> {
                    return;
                }
            }
            e.consume();
        });
    }

    private void startPlayback(EmbeddedMediaPlayer activePlayer) {
        if (activePlayer != mediaPlayer) {
            return;
        }
        activePlayer.controls().play();
        playing = true;
        playbackEnded = false;
        btnPlayPause.setText("⏸");
    }

    private void pausePlayback(EmbeddedMediaPlayer activePlayer) {
        if (activePlayer != mediaPlayer) {
            return;
        }
        activePlayer.controls().pause();
        currentMillis = readCurrentMillis();
        playing = false;
        btnPlayPause.setText("▶");
        renderPlaybackPosition();
    }

    private void seekTo(long targetMillis) {
        EmbeddedMediaPlayer activePlayer = mediaPlayer;
        if (activePlayer == null || totalMillis <= 0) {
            return;
        }
        boolean wasAtEnd = playbackEnded || isAtEnd();
        seekingAwayFromEnd = wasAtEnd
                && targetMillis < Math.max(0, totalMillis - END_TOLERANCE_MILLIS);
        playbackEnded = false;
        currentMillis = targetMillis;
        if (seekingAwayFromEnd) {
            pendingSeekAfterStartMillis = targetMillis;
            startPlayback(activePlayer);
        } else {
            pendingSeekAfterStartMillis = null;
            activePlayer.controls().setTime(targetMillis);
        }
        renderPlaybackPosition();
        onPlaybackTimeChanged.accept(currentMillis / 1000.0);
    }

    private void restartPlaybackFromBeginning(EmbeddedMediaPlayer activePlayer) {
        playbackEnded = false;
        seekingAwayFromEnd = true;
        currentMillis = 0;
        activePlayer.controls().setTime(0);
        activePlayer.controls().play();
        playing = true;
        btnPlayPause.setText("⏸");
        renderPlaybackPosition();
        onPlaybackTimeChanged.accept(0);
    }

    private long readCurrentMillis() {
        EmbeddedMediaPlayer activePlayer = mediaPlayer;
        if (activePlayer == null) {
            return currentMillis;
        }
        try {
            return clampPlaybackMillis(activePlayer.status().time());
        } catch (RuntimeException e) {
            log.debug("Failed to read libVLC playback time", e);
            return currentMillis;
        }
    }

    private boolean isAtEnd() {
        return totalMillis > 0 && currentMillis >= Math.max(0, totalMillis - END_TOLERANCE_MILLIS);
    }

    private long clampPlaybackMillis(long millis) {
        if (totalMillis <= 0) {
            return Math.max(0, millis);
        }
        return Math.clamp(millis, 0, totalMillis);
    }

    private void resetPlaybackUi() {
        sliderDragging = false;
        directSliderSeek = false;
        playing = false;
        playbackEnded = false;
        seekingAwayFromEnd = false;
        pendingSeekAfterStartMillis = null;
        currentMillis = 0;
        totalMillis = 0;
        cbSpeed.setValue("1x");
        btnPlayPause.setText("▶");
        videoSlider.setValue(0);
        lblVideoTime.setText("00:00 / 00:00");
        updateMuteButton();
    }

    private void renderPlaybackPosition() {
        if (totalMillis > 0) {
            double progress = Math.clamp((double) currentMillis / totalMillis * 100, 0, 100);
            videoSlider.setValue(progress);
            lblVideoTime.setText(formatDuration(currentMillis) + " / " + formatDuration(totalMillis));
        } else {
            videoSlider.setValue(0);
            lblVideoTime.setText("00:00 / 00:00");
        }
    }

    private void updateMuteButton() {
        EmbeddedMediaPlayer activePlayer = mediaPlayer;
        boolean muted = volumeSlider.getValue() <= 0
                || (activePlayer != null && activePlayer.audio().isMute());
        btnMute.setText(muted ? "🔇" : "🔊");
    }

    private int toVlcVolume(double volume) {
        return (int) Math.round(Math.clamp(volume, 0, 1) * 100);
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%02d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
    }

    private String formatSeekTooltip() {
        return totalMillis > 0
                ? formatDuration(sliderTargetMillis(totalMillis, videoSlider.getValue()))
                : "00:00";
    }

    private String formatVolumeTooltip() {
        return Math.round(volumeSlider.getValue() * 100) + "%";
    }

    private boolean isSliderThumb(Object target) {
        return target instanceof Node node && node.getStyleClass().contains("thumb");
    }

    private void updateSliderFromPointer(Slider slider, MouseEvent event) {
        Node track = slider.lookup(".track");
        if (track == null) {
            return;
        }
        Bounds trackBounds = track.localToScene(track.getBoundsInLocal());
        if (trackBounds.getWidth() <= 0) {
            return;
        }
        double fraction = (event.getSceneX() - trackBounds.getMinX()) / trackBounds.getWidth();
        fraction = Math.clamp(fraction, 0, 1);
        double range = slider.getMax() - slider.getMin();
        slider.setValue(slider.getMin() + fraction * range);
    }

    private Label createSliderTooltipLabel() {
        Label label = new Label();
        label.getStyleClass().add("media-slider-tooltip");
        return label;
    }

    private Popup createSliderTooltipPopup(Label label) {
        Popup popup = new Popup();
        popup.setAutoFix(true);
        popup.getContent().add(label);
        popup.getScene().setFill(Color.TRANSPARENT);
        popup.getScene().getRoot().setStyle("-fx-background-color: transparent;");
        return popup;
    }

    private void showSliderTooltip(Popup popup, Label label, Slider slider, String text) {
        Node thumb = slider.lookup(".thumb");
        if (thumb == null || slider.getScene() == null) {
            return;
        }
        Bounds thumbBounds = thumb.localToScreen(thumb.getBoundsInLocal());
        if (thumbBounds == null) {
            return;
        }
        label.getStylesheets().setAll(slider.getScene().getStylesheets());
        label.setText(text);
        label.applyCss();
        label.autosize();
        double anchorX = thumbBounds.getMinX() + (thumbBounds.getWidth() - label.getWidth()) / 2;
        double anchorY = thumbBounds.getMinY() - label.getHeight() - 7;
        if (popup.isShowing()) {
            popup.setX(anchorX);
            popup.setY(anchorY);
        } else {
            popup.show(slider, anchorX, anchorY);
        }
    }

    static long clampSeekTargetMillis(long currentMillis, long deltaMillis, long totalMillis) {
        if (totalMillis <= 0) {
            return 0;
        }
        return Math.clamp(currentMillis + deltaMillis, 0, Math.max(0, totalMillis - 1));
    }

    static long sliderTargetMillis(long totalMillis, double sliderValue) {
        if (totalMillis <= 0) {
            return 0;
        }
        double percentage = Math.clamp(sliderValue, 0, 100) / 100.0;
        return Math.round(Math.max(0, totalMillis - 1) * percentage);
    }
}
