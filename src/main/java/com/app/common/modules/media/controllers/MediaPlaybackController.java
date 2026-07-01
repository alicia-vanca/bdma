package com.app.common.modules.media.controllers;

import java.util.function.DoubleConsumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.app.common.dtos.FileView;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.util.Duration;

/**
 * Owns the media-player lifecycle and playback controls for the media viewer.
 */
final class MediaPlaybackController {

    private static final Logger log = LoggerFactory.getLogger(MediaPlaybackController.class);

    private final VBox videoPane;
    private final StackPane videoContentPane;
    private final Pane mediaViewWrapper;
    private final MediaView mediaView;
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
    private double lastAudibleVolume = 1.0;
    private Popup seekTooltip;
    private Popup volumeTooltip;
    private Label seekTooltipLabel;
    private Label volumeTooltipLabel;
    private MediaPlayer mediaPlayer;
    private boolean playbackEnded;
    private boolean seekingAwayFromEnd;
    private Duration playbackClockTime = Duration.ZERO;
    private long playbackClockNanos;
    private ChangeListener<Bounds> boundsListener;
    private ChangeListener<Number> videoPaneWidthListener;
    private ChangeListener<Number> videoPaneHeightListener;

    private final AnimationTimer playbackProgressTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            MediaPlayer player = mediaPlayer;
            if (player == null || sliderDragging) {
                return;
            }
            Duration total = player.getTotalDuration();
            if (!isDurationValid(total)) {
                return;
            }
            Duration displayedTime = playbackClockTime;
            if (player.getStatus() == MediaPlayer.Status.PLAYING && playbackClockNanos > 0) {
                double elapsedSeconds = (System.nanoTime() - playbackClockNanos) / 1_000_000_000.0;
                displayedTime = displayedTime.add(Duration.seconds(elapsedSeconds * player.getRate()));
            }
            if (displayedTime.greaterThan(total)) {
                displayedTime = total;
            }
            renderPlaybackPosition(displayedTime, total);
        }
    };

    private enum PlaybackState {
        READY, PLAYING, PAUSED, SEEKING, ENDED, STOPPED
    }

    record PlaybackControllerDependencies(
            VBox videoPane,
            StackPane videoContentPane,
            Pane mediaViewWrapper,
            MediaView mediaView,
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
        this.mediaView = dependencies.mediaView();
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
        setupContentPaneListeners();
        setupVolumeControls();
    }

    void attach(FileView file, MediaPlayer player, Runnable onPlaybackError) {
        stop();
        mediaPlayer = player;
        mediaView.setMediaPlayer(player);
        mediaView.setPreserveRatio(true);
        setupMediaPlayer(file, onPlaybackError);
    }

    void togglePlayback() {
        if (mediaPlayer == null) {
            return;
        }
        Duration total = mediaPlayer.getTotalDuration();
        if (!seekingAwayFromEnd && (playbackEnded
                || (isDurationValid(total) && mediaPlayer.getCurrentTime().greaterThanOrEqualTo(total)))) {
            restartPlaybackFromBeginning();
            return;
        }
        if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            pausePlayback(mediaPlayer);
        } else {
            startPlayback(mediaPlayer);
        }
    }

    void seekBy(int seconds) {
        if (mediaPlayer == null) {
            return;
        }
        Duration total = mediaPlayer.getTotalDuration();
        if (!isDurationValid(total)) {
            return;
        }
        Duration current = playbackEnded ? total : mediaPlayer.getCurrentTime();
        Duration target = current.add(Duration.seconds(seconds));

        if (target.lessThan(Duration.ZERO)) {
            target = Duration.ZERO;
        }
        if (target.greaterThanOrEqualTo(total)) {
            target = total.subtract(Duration.millis(1));
        }
        seekTo(target);
    }

    void changeSpeed() {
        if (mediaPlayer == null || cbSpeed.getValue() == null) {
            return;
        }
        try {
            double rate = Double.parseDouble(cbSpeed.getValue().replace("x", ""));
            syncPlaybackClock(mediaPlayer.getCurrentTime());
            mediaPlayer.setRate(rate);
        } catch (NumberFormatException e) {
            log.warn("Invalid speed format: {}", cbSpeed.getValue());
        }
    }

    void toggleMute() {
        if (mediaPlayer == null) {
            return;
        }
        boolean silent = mediaPlayer.isMute() || volumeSlider.getValue() <= 0;
        if (silent) {
            volumeSlider.setValue(lastAudibleVolume > 0 ? lastAudibleVolume : 1.0);
            mediaPlayer.setMute(false);
        } else {
            lastAudibleVolume = volumeSlider.getValue();
            volumeSlider.setValue(0);
            mediaPlayer.setMute(true);
        }
        updateMuteButton();
    }

    void stop() {
        handlePlaybackState(PlaybackState.STOPPED, Duration.ZERO);
        seekTooltip.hide();
        volumeTooltip.hide();
        if (mediaPlayer != null) {
            mediaPlayer.volumeProperty().unbind();
            mediaPlayer.stop();
            mediaPlayer.dispose();
            mediaPlayer = null;
        }
        removeCenteringListeners();
        mediaView.setLayoutX(0);
        mediaView.setLayoutY(0);
    }

    private void setupContentPaneListeners() {
        videoContentPane.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (mediaView.getMediaPlayer() != null) {
                mediaView.setFitWidth(newVal.doubleValue());
                mediaViewWrapper.setMaxWidth(newVal.doubleValue());
                mediaViewWrapper.setPrefWidth(newVal.doubleValue());
            }
        });
        videoContentPane.heightProperty().addListener((obs, oldVal, newVal) -> {
            if (mediaView.getMediaPlayer() != null) {
                mediaView.setFitHeight(newVal.doubleValue());
                mediaViewWrapper.setMaxHeight(newVal.doubleValue());
                mediaViewWrapper.setPrefHeight(newVal.doubleValue());
            }
        });
    }

    private void setupVolumeControls() {
        volumeSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue.doubleValue() > 0) {
                lastAudibleVolume = newValue.doubleValue();
                if (mediaPlayer != null && mediaPlayer.isMute()) {
                    mediaPlayer.setMute(false);
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

    private void setupMediaPlayer(FileView file, Runnable onPlaybackError) {
        boolean audio = isAudio.test(file.type());
        configureUiForMediaType(audio);
        installCenteringListeners();
        initializePlaybackControls();

        MediaPlayer activePlayer = mediaPlayer;
        handlePlaybackState(PlaybackState.READY, Duration.ZERO);
        attachPlaybackEventHandlers(activePlayer, onPlaybackError);
        setupSeekControls(activePlayer);
        attachPlaybackKeyboardShortcuts();
        mediaView.setOnMouseClicked(e -> togglePlayback());
    }

    private void configureUiForMediaType(boolean audio) {
        mediaViewWrapper.setVisible(!audio);
        mediaViewWrapper.setManaged(!audio);
        lblAudioPlaceholder.setVisible(audio);
        lblAudioPlaceholder.setManaged(audio);

        if (!audio) {
            resizeMediaViewToPane();
        }
    }

    private void resizeMediaViewToPane() {
        mediaView.setFitWidth(videoContentPane.getWidth());
        mediaView.setFitHeight(videoContentPane.getHeight());
        mediaViewWrapper.setMaxWidth(videoContentPane.getWidth());
        mediaViewWrapper.setMaxHeight(videoContentPane.getHeight());
        mediaViewWrapper.setPrefWidth(videoContentPane.getWidth());
        mediaViewWrapper.setPrefHeight(videoContentPane.getHeight());
    }

    private void initializePlaybackControls() {
        if (cbSpeed.getItems().isEmpty()) {
            cbSpeed.getItems().addAll("0.25x", "0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x");
        }
        cbSpeed.setValue("1x");
    }

    private void attachPlaybackEventHandlers(MediaPlayer activePlayer, Runnable onPlaybackError) {
        configurePlayerVolume(activePlayer);
        activePlayer.setOnPlaying(() -> handlePlayingEvent(activePlayer));
        activePlayer.currentTimeProperty()
                .addListener((obs, oldVal, newVal) -> handleCurrentTimeChange(activePlayer, newVal));
        activePlayer.setOnReady(() -> handleReadyEvent(activePlayer));
        activePlayer.setOnEndOfMedia(() -> handlePlaybackEndEvent(activePlayer));
        activePlayer.setOnError(() -> {
            if (activePlayer == mediaPlayer) {
                Platform.runLater(onPlaybackError);
            }
        });
    }

    private void configurePlayerVolume(MediaPlayer activePlayer) {
        activePlayer.volumeProperty().bind(volumeSlider.valueProperty());
        activePlayer.setMute(false);
        updateMuteButton();
    }

    private void handlePlayingEvent(MediaPlayer activePlayer) {
        if (activePlayer == mediaPlayer) {
            handlePlaybackState(PlaybackState.PLAYING, null);
        }
    }

    private void handleCurrentTimeChange(MediaPlayer activePlayer, Duration newVal) {
        if (activePlayer != mediaPlayer) {
            return;
        }
        syncPlaybackClock(newVal);
        Duration total = activePlayer.getTotalDuration();
        if (shouldRecoverFromSeekingEnd(total, newVal)) {
            seekingAwayFromEnd = false;
            if (activePlayer.getStatus() == MediaPlayer.Status.PLAYING) {
                handlePlaybackState(PlaybackState.PLAYING, null);
            }
        }
        if (shouldUpdateSliderAndTime(activePlayer)) {
            onPlaybackTimeChanged.accept(newVal.toSeconds());
        }
    }

    private boolean shouldRecoverFromSeekingEnd(Duration total, Duration newVal) {
        return seekingAwayFromEnd && isDurationValid(total) && newVal.lessThan(total.subtract(Duration.millis(100)));
    }

    private void handleReadyEvent(MediaPlayer activePlayer) {
        if (activePlayer != mediaPlayer) {
            return;
        }
        updateDetailDimensions(activePlayer);
        handlePlaybackState(PlaybackState.READY, Duration.ZERO);
        startPlayback(activePlayer);
        Platform.runLater(videoPane::requestFocus);
    }

    private void handlePlaybackEndEvent(MediaPlayer activePlayer) {
        if (activePlayer != mediaPlayer || seekingAwayFromEnd) {
            return;
        }
        handlePlaybackState(PlaybackState.ENDED, activePlayer.getTotalDuration());
    }

    private void updateDetailDimensions(MediaPlayer activePlayer) {
        int width = activePlayer.getMedia().getWidth();
        int height = activePlayer.getMedia().getHeight();
        if (width > 0 && height > 0) {
            Platform.runLater(() -> detailDimension.setText(width + " × " + height));
        }
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

    private void installCenteringListeners() {
        boundsListener = (obs, oldVal, newVal) -> {
            double x = (videoContentPane.getWidth() - newVal.getWidth()) / 2;
            double y = (videoContentPane.getHeight() - newVal.getHeight()) / 2;
            mediaView.setLayoutX(Math.max(0, x));
            mediaView.setLayoutY(Math.max(0, y));
        };
        videoPaneWidthListener = (obs, oldVal, newVal) -> {
            double x = (newVal.doubleValue() - mediaView.getBoundsInLocal().getWidth()) / 2;
            mediaView.setLayoutX(Math.max(0, x));
        };
        videoPaneHeightListener = (obs, oldVal, newVal) -> {
            double y = (newVal.doubleValue() - mediaView.getBoundsInLocal().getHeight()) / 2;
            mediaView.setLayoutY(Math.max(0, y));
        };
        mediaView.boundsInLocalProperty().addListener(boundsListener);
        videoContentPane.widthProperty().addListener(videoPaneWidthListener);
        videoContentPane.heightProperty().addListener(videoPaneHeightListener);
    }

    private void removeCenteringListeners() {
        if (boundsListener != null) {
            mediaView.boundsInLocalProperty().removeListener(boundsListener);
            boundsListener = null;
        }
        if (videoPaneWidthListener != null) {
            videoContentPane.widthProperty().removeListener(videoPaneWidthListener);
            videoPaneWidthListener = null;
        }
        if (videoPaneHeightListener != null) {
            videoContentPane.heightProperty().removeListener(videoPaneHeightListener);
            videoPaneHeightListener = null;
        }
    }

    private void setupSeekControls(MediaPlayer activePlayer) {
        videoSlider.setOnMousePressed(e -> {
            sliderDragging = true;
            directSliderSeek = !isSliderThumb(e.getTarget());
            if (directSliderSeek) {
                updateSliderFromPointer(videoSlider, e);
            }
            showSliderTooltip(seekTooltip, seekTooltipLabel, videoSlider, formatSeekTooltip(activePlayer));
        });
        videoSlider.setOnMouseDragged(e -> {
            if (directSliderSeek) {
                updateSliderFromPointer(videoSlider, e);
            }
            showSliderTooltip(seekTooltip, seekTooltipLabel, videoSlider, formatSeekTooltip(activePlayer));
        });
        videoSlider.setOnMouseReleased(e -> {
            sliderDragging = false;
            directSliderSeek = false;
            seekTooltip.hide();
            if (activePlayer != mediaPlayer) {
                return;
            }
            Duration total = activePlayer.getTotalDuration();
            if (isDurationValid(total)) {
                seekTo(total.multiply(videoSlider.getValue() / 100));
            }
        });
    }

    private boolean isDurationValid(Duration duration) {
        return duration != null && duration.greaterThan(Duration.ZERO);
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

    private String formatSeekTooltip(MediaPlayer activePlayer) {
        Duration total = activePlayer.getTotalDuration();
        return isDurationValid(total)
                ? formatDuration(total.multiply(videoSlider.getValue() / 100.0))
                : "00:00";
    }

    private String formatVolumeTooltip() {
        return Math.round(volumeSlider.getValue() * 100) + "%";
    }

    private boolean shouldUpdateSliderAndTime(MediaPlayer activePlayer) {
        return !sliderDragging && activePlayer == mediaPlayer;
    }

    private void syncPlaybackClock(Duration time) {
        playbackClockTime = time != null && !time.isUnknown() ? time : Duration.ZERO;
        playbackClockNanos = System.nanoTime();
    }

    private void handlePlaybackState(PlaybackState state, Duration position) {
        MediaPlayer player = mediaPlayer;
        Duration total = player != null ? player.getTotalDuration() : Duration.UNKNOWN;
        switch (state) {
            case READY -> handleReadyState(total);
            case PLAYING -> handlePlayingState();
            case PAUSED -> handlePausedState(position, total);
            case SEEKING -> handleSeekingState(position, total, player);
            case ENDED -> handleEndedState(position, total);
            case STOPPED -> handleStoppedState();
        }
    }

    private void handleReadyState(Duration total) {
        playbackProgressTimer.stop();
        playbackEnded = false;
        seekingAwayFromEnd = false;
        sliderDragging = false;
        directSliderSeek = false;
        btnPlayPause.setText("▶");
        anchorPlaybackPosition(Duration.ZERO, total, false);
    }

    private void handlePlayingState() {
        playbackEnded = false;
        playbackClockNanos = System.nanoTime();
        playbackProgressTimer.start();
        btnPlayPause.setText("⏸");
    }

    private void handlePausedState(Duration position, Duration total) {
        playbackProgressTimer.stop();
        btnPlayPause.setText("▶");
        anchorPlaybackPosition(position, total, false);
    }

    private void handleSeekingState(Duration position, Duration total, MediaPlayer player) {
        Duration target = position != null ? position : Duration.ZERO;
        Duration current = resolveCurrentPositionForSeeking(total, player);
        boolean wasAtEnd = playbackEnded || (isDurationValid(total) && current != null
                && current.greaterThanOrEqualTo(total.subtract(Duration.millis(100))));
        seekingAwayFromEnd = wasAtEnd && isDurationValid(total)
                && target.lessThan(total.subtract(Duration.millis(100)));
        if (isDurationValid(total) && target.lessThan(total)) {
            playbackEnded = false;
        }
        anchorPlaybackPosition(target, total, true);
    }

    private Duration resolveCurrentPositionForSeeking(Duration total, MediaPlayer player) {
        if (playbackEnded && isDurationValid(total)) {
            return total;
        }
        return player != null ? player.getCurrentTime() : Duration.ZERO;
    }

    private void handleEndedState(Duration position, Duration total) {
        playbackProgressTimer.stop();
        playbackEnded = true;
        seekingAwayFromEnd = false;
        btnPlayPause.setText("▶");
        anchorPlaybackPosition(position, total, true);
    }

    private void handleStoppedState() {
        playbackProgressTimer.stop();
        playbackEnded = false;
        seekingAwayFromEnd = false;
        btnPlayPause.setText("▶");
        anchorPlaybackPosition(Duration.ZERO, Duration.UNKNOWN, false);
    }

    private void anchorPlaybackPosition(Duration position, Duration total, boolean updateGps) {
        Duration safePosition = position != null && !position.isUnknown() ? position : Duration.ZERO;
        syncPlaybackClock(safePosition);
        renderPlaybackPosition(safePosition, total);
        if (updateGps) {
            onPlaybackTimeChanged.accept(safePosition.toSeconds());
        }
    }

    private void renderPlaybackPosition(Duration position, Duration total) {
        if (isDurationValid(total)) {
            double progress = Math.clamp(position.toSeconds() / total.toSeconds() * 100, 0, 100);
            videoSlider.setValue(progress);
            lblVideoTime.setText(formatDuration(position) + " / " + formatDuration(total));
        } else {
            videoSlider.setValue(0);
            lblVideoTime.setText("00:00 / 00:00");
        }
    }

    private void startPlayback(MediaPlayer player) {
        if (player == null || player != mediaPlayer) {
            return;
        }
        player.play();
        handlePlaybackState(PlaybackState.PLAYING, null);
    }

    private void pausePlayback(MediaPlayer player) {
        if (player == null || player != mediaPlayer) {
            return;
        }
        player.pause();
        handlePlaybackState(PlaybackState.PAUSED, player.getCurrentTime());
    }

    private void seekTo(Duration target) {
        MediaPlayer player = mediaPlayer;
        if (player == null || target == null) {
            return;
        }
        boolean continuesPlaying = player.getStatus() == MediaPlayer.Status.PLAYING;
        handlePlaybackState(PlaybackState.SEEKING, target);
        player.seek(target);
        if (continuesPlaying) {
            handlePlaybackState(PlaybackState.PLAYING, null);
        }
    }

    private void restartPlaybackFromBeginning() {
        MediaPlayer player = mediaPlayer;
        if (player == null) {
            return;
        }
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
        }
        seekTo(Duration.ZERO);
        Platform.runLater(() -> {
            if (player == mediaPlayer) {
                startPlayback(player);
            }
        });
    }

    private void updateMuteButton() {
        boolean silent = volumeSlider.getValue() <= 0 || (mediaPlayer != null && mediaPlayer.isMute());
        btnMute.setText(silent ? "🔇" : "🔊");
    }

    private String formatDuration(Duration duration) {
        if (duration == null || duration.isUnknown()) {
            return "00:00";
        }
        int totalSeconds = (int) duration.toSeconds();
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%02d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
    }
}
