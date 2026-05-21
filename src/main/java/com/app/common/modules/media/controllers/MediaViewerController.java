package com.app.common.modules.media.controllers;

import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.app.common.dtos.FileView;
import com.app.common.modules.foldermanager.dtos.PathResolutionResult;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.media.dtos.GpsCoordinate;
import com.app.common.modules.media.dtos.GpsPoint;
import com.app.common.modules.media.services.MediaMetadataService;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;
import lombok.Setter;

@Component
@Scope("prototype")
public class MediaViewerController {

    private static final Logger log = LoggerFactory.getLogger(MediaViewerController.class);

    // ── FXML ─────────────────────────────────────────────────────────────────
    @FXML
    private StackPane contentPane;
    @FXML
    private ScrollPane scrollPane;
    @FXML
    private ImageView imageView;
    @FXML
    private Button btnPrev;
    @FXML
    private Button btnNext;
    @FXML
    private Button btnZoomIn;
    @FXML
    private Button btnZoomOut;
    @FXML
    private Button btnFit;
    @FXML
    private Button btnRotateL;
    @FXML
    private Button btnRotateR;
    @FXML
    private Label lblCounter;
    @FXML
    private Label lblFileName;
    @FXML
    private Label lblDimension;
    @FXML
    private Label lblFileSize;
    @FXML
    private Label lblZoom;
    @FXML
    private Label lblLoading;
    @FXML
    private Label lblError;
    @FXML
    private Label detailName;
    @FXML
    private Label detailType;
    @FXML
    private Label detailSize;
    @FXML
    private Label detailDate;
    @FXML
    private Label detailDevice;
    @FXML
    private Label detailUser;
    @FXML
    private Label detailStatus;
    @FXML
    private Label detailDimension;
    @FXML
    private StackPane imageContainer;
    @FXML
    private Label lblDetailTitle;
    @FXML
    private Label lblDetailNameKey;
    @FXML
    private Label lblDetailTypeKey;
    @FXML
    private Label lblDetailSizeKey;
    @FXML
    private Label lblDetailDateKey;
    @FXML
    private Label lblDetailDeviceKey;
    @FXML
    private Label lblDetailUserKey;
    @FXML
    private Label lblDetailStatusKey;
    @FXML
    private Label lblDetailDimensionKey;
    @FXML
    private Label lblDetailGpsKey;
    @FXML
    private Label detailGps;
    @FXML
    private StackPane videoPane;
    @FXML
    private Pane mediaViewWrapper;
    @FXML
    private MediaView mediaView;
    @FXML
    private Button btnPlayPause;
    @FXML
    private Slider videoSlider;
    @FXML
    private Slider volumeSlider;
    @FXML
    private Label lblVideoTime;
    @FXML
    private ComboBox<String> cbSpeed;

    // ── State ─────────────────────────────────────────────────────────────────
    private FileView currentFile;
    private Path currentPath;
    private Image currentImage;
    private List<FileView> mediaList;
    private int currentIndex;
    private double zoomFactor = 1.0;
    private double rotation = 0;
    private List<GpsPoint> gpsTimeline = new ArrayList<>();
    private boolean fitMode = true;
    private boolean sliderDragging = false;
    private MediaPlayer mediaPlayer;
    private ChangeListener<Bounds> boundsListener;
    private ChangeListener<Number> videoPaneWidthListener;
    private ChangeListener<Number> videoPaneHeightListener;

    private final FolderManagerService folderManagerService;
    private final MediaMetadataService mediaMetadataService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MediaViewer-Loader");
        t.setDaemon(true);
        return t;
    });

    @Setter
    private Consumer<FileView> onLoadFile;

    public MediaViewerController(FolderManagerService folderManagerService,
            MediaMetadataService mediaMetadataService) {
        this.folderManagerService = folderManagerService;
        this.mediaMetadataService = mediaMetadataService;
    }

    public void refreshLocalizedText() {
        lblDetailTitle.setText(I18n.get("media.viewer.detail.title"));
        lblDetailTitle.setText(I18n.get("media.viewer.detail.title"));
        lblDetailNameKey.setText(I18n.get("media.viewer.detail.name"));
        lblDetailTypeKey.setText(I18n.get("media.viewer.detail.type"));
        lblDetailSizeKey.setText(I18n.get("media.viewer.detail.size"));
        lblDetailDateKey.setText(I18n.get("media.viewer.detail.date"));
        lblDetailDeviceKey.setText(I18n.get("media.viewer.detail.device"));
        lblDetailUserKey.setText(I18n.get("media.viewer.detail.user"));
        lblDetailStatusKey.setText(I18n.get("media.viewer.detail.status"));
        lblDetailDimensionKey.setText(I18n.get("media.viewer.detail.dimension"));
        lblDetailGpsKey.setText(I18n.get("media.viewer.detail.gps"));
        if (currentFile != null) {
            updateDetailPanel(currentFile, currentImage, currentPath);
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────
    @FXML
    public void initialize() {
        refreshLocalizedText();

        scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.isControlDown()) {
                applyZoom(e.getDeltaY() > 0 ? 1.1 : 0.9);
                e.consume();
            }
        });

        scrollPane.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() > 0 && imageView.getImage() != null && fitMode) {
                fitImageToPane();
            }
        });

        scrollPane.heightProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() > 0 && imageView.getImage() != null && fitMode) {
                fitImageToPane();
            }
        });

        contentPane.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (mediaView.getMediaPlayer() != null) {
                mediaView.setFitWidth(newVal.doubleValue());
                mediaViewWrapper.setMaxWidth(newVal.doubleValue());
                mediaViewWrapper.setPrefWidth(newVal.doubleValue());
            }
        });

        contentPane.heightProperty().addListener((obs, oldVal, newVal) -> {
            if (mediaView.getMediaPlayer() != null) {
                mediaView.setFitHeight(newVal.doubleValue());
                mediaViewWrapper.setMaxHeight(newVal.doubleValue());
                mediaViewWrapper.setPrefHeight(newVal.doubleValue());
            }
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────
    public void setMedia(List<FileView> list, int startIndex) {
        this.mediaList = list;
        this.currentIndex = startIndex;
        loadCurrent();
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    @FXML
    private void onPrev() {
        if (currentIndex > 0) {
            currentIndex--;
            loadCurrent();
        }
    }

    @FXML
    private void onNext() {
        if (currentIndex < mediaList.size() - 1) {
            currentIndex++;
            loadCurrent();
        }
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    private void loadCurrent() {
        FileView file = mediaList.get(currentIndex);
        updateNavButtons();
        updateCounter();
        resetTransform();

        if (onLoadFile != null) {
            onLoadFile.accept(file);
        }
    }

    public void loadImage(FileView file) {
        showLoading();

        executor.submit(() -> {
            try {
                PathResolutionResult result = folderManagerService
                        .findAbsolutePathFromNonDriveLetterPath(file.syncedPath(), file.fileSize());

                if (result.isNotFound() || result.isError()) {
                    Platform.runLater(() -> showError(I18n.get("media.viewer.error.image.not.found", file.name())));
                    return;
                }

                Path absolutePath = result.getPath();
                String uri = absolutePath.toUri().toString();
                Image img = new Image(uri, true);

                img.progressProperty().addListener((obs, o, n) -> {
                    if (n.doubleValue() >= 1.0) {
                        Platform.runLater(() -> onImageReady(img, file, absolutePath));
                    }
                });

                if (img.getProgress() >= 1.0) {
                    Platform.runLater(() -> onImageReady(img, file, absolutePath));
                }

            } catch (Exception e) {
                log.error("Failed to load image: {}", file.syncedPath(), e);
                Platform.runLater(() -> showError(I18n.get("media.viewer.error.image.load", file.name())));
            }
        });
    }

    private void onImageReady(Image img, FileView file, Path absolutePath) {
        if (img.isError()) {
            showError(I18n.get("media.viewer.error.image.display", file.name()));
            return;
        }
        imageView.setImage(img);
        showImagePane();
        updateStatusBar(file, img);
        updateDetailPanel(file, img, absolutePath);
        scrollPane.applyCss();
        scrollPane.layout();
        fitImageToPane();
    }

    public void loadVideo(FileView file) {
        showLoading();
        stopCurrentMedia();

        executor.submit(() -> {
            try {
                PathResolutionResult result = folderManagerService
                        .findAbsolutePathFromNonDriveLetterPath(file.syncedPath(), file.fileSize());

                if (result.isNotFound() || result.isError()) {
                    Platform.runLater(() -> showError(I18n.get("media.viewer.error.video.not.found", file.name())));
                    return;
                }

                String uri = result.getPath().toUri().toString();

                Platform.runLater(() -> {
                    try {
                        Media media = new Media(uri);
                        mediaPlayer = new MediaPlayer(media);
                        mediaView.setMediaPlayer(mediaPlayer);
                        mediaView.setPreserveRatio(true);

                        executor.submit(() -> {
                            List<GpsPoint> timeline = mediaMetadataService
                                    .readGpsTimeline(result.getPath(), file.type());
                            Platform.runLater(() -> gpsTimeline = timeline);
                        });
                        setupMediaPlayer(file);
                        showVideoPane();
                        updateDetailPanel(file, null, result.getPath());

                    } catch (Exception e) {
                        log.error("Failed to create MediaPlayer: {}", file.syncedPath(), e);
                        showError(I18n.get("media.viewer.error.video.play", file.name()));
                    }
                });

            } catch (Exception e) {
                log.error("Failed to load video: {}", file.syncedPath(), e);
                Platform.runLater(() -> showError(I18n.get("media.viewer.error.video.load", file.name())));
            }
        });
    }

    // ── Zoom ─────────────────────────────────────────────────────────────────
    @FXML
    private void onZoomIn() {
        fitMode = false;
        applyZoom(1.2);
    }

    @FXML
    private void onZoomOut() {
        fitMode = false;
        applyZoom(0.8);
    }

    @FXML
    private void onFit() {
        fitMode = true;
        fitImageToPane();
    }

    private void applyZoom(double factor) {
        if (imageView.getImage() == null) return;

        zoomFactor *= factor;

        double fitW = imageView.getImage().getWidth() * zoomFactor;
        double fitH = imageView.getImage().getHeight() * zoomFactor;

        imageView.setFitWidth(fitW);
        imageView.setFitHeight(fitH);

        double paneW = scrollPane.getWidth() - 2;
        double paneH = scrollPane.getHeight() - 2;

        imageContainer.setMinWidth(Math.max(fitW, paneW));
        imageContainer.setMinHeight(Math.max(fitH, paneH));
        imageContainer.setPrefWidth(Math.max(fitW, paneW));
        imageContainer.setPrefHeight(Math.max(fitH, paneH));

        lblZoom.setText(new DecimalFormat("##0%").format(zoomFactor));
    }

    private void fitImageToPane() {
        if (imageView.getImage() == null) return;

        double paneW = scrollPane.getWidth() - 2;
        double paneH = scrollPane.getHeight() - 2;
        if (paneW <= 0 || paneH <= 0) return;

        double imgW = imageView.getImage().getWidth();
        double imgH = imageView.getImage().getHeight();
        double scale = Math.min(paneW / imgW, paneH / imgH);

        double fitW = imgW * scale;
        double fitH = imgH * scale;

        imageView.setFitWidth(fitW);
        imageView.setFitHeight(fitH);

        imageContainer.setMinWidth(paneW);
        imageContainer.setMinHeight(paneH);
        imageContainer.setPrefWidth(paneW);
        imageContainer.setPrefHeight(paneH);

        zoomFactor = scale;
        lblZoom.setText(new DecimalFormat("##0%").format(zoomFactor));
    }

    // ── Rotate ────────────────────────────────────────────────────────────────
    @FXML
    private void onRotateLeft() {
        applyRotation(-90);
    }

    @FXML
    private void onRotateRight() {
        applyRotation(90);
    }

    private void applyRotation(double angle) {
        rotation = (rotation + angle) % 360;
        imageView.getTransforms().clear();
        imageView.getTransforms().add(new Rotate(
                rotation,
                imageView.getFitWidth() / 2,
                imageView.getFitHeight() / 2));
    }

    // ── UI state helpers ──────────────────────────────────────────────────────
    private void showLoading() {
        scrollPane.setVisible(false);
        scrollPane.setManaged(false);
        lblError.setVisible(false);
        lblError.setManaged(false);
        lblLoading.setVisible(true);
        lblLoading.setManaged(true);
        clearStatusBar();
    }

    private void showImagePane() {
        lblLoading.setVisible(false);
        lblLoading.setManaged(false);
        lblError.setVisible(false);
        lblError.setManaged(false);
        videoPane.setVisible(false);
        videoPane.setManaged(false);
        scrollPane.setVisible(true);
        scrollPane.setManaged(true);
        setImageToolsVisible(true);
    }

    private void showError(String message) {
        lblLoading.setVisible(false);
        lblLoading.setManaged(false);
        scrollPane.setVisible(false);
        scrollPane.setManaged(false);
        lblError.setText(message);
        lblError.setVisible(true);
        lblError.setManaged(true);
        setImageToolsVisible(false);
    }

    private void setImageToolsVisible(boolean visible) {
        btnZoomIn.setVisible(visible);
        btnZoomOut.setVisible(visible);
        btnFit.setVisible(visible);
        btnRotateL.setVisible(visible);
        btnRotateR.setVisible(visible);
    }

    private void updateNavButtons() {
        btnPrev.setDisable(currentIndex <= 0);
        btnNext.setDisable(currentIndex >= mediaList.size() - 1);
    }

    private void updateCounter() {
        lblCounter.setText((currentIndex + 1) + " / " + mediaList.size());
    }

    private void resetTransform() {
        rotation = 0;
        zoomFactor = 1.0;
        fitMode = true;
        imageView.getTransforms().clear();
        stopCurrentMedia();
    }

    private void updateStatusBar(FileView file, Image img) {
        lblFileName.setText(file.name());
        lblFileSize.setText(formatSize(file.fileSize()));
        if (img != null) {
            lblDimension.setText((int) img.getWidth() + " × " + (int) img.getHeight());
        }
        lblZoom.setText("Fit");
    }

    private void clearStatusBar() {
        lblFileName.setText("");
        lblDimension.setText("");
        lblFileSize.setText("");
        lblZoom.setText("");

        detailName.setText("—");
        detailType.setText("—");
        detailSize.setText("—");
        detailDate.setText("—");
        detailDevice.setText("—");
        detailUser.setText("—");
        detailStatus.setText("—");
        detailDimension.setText("—");
        detailGps.setText("—");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private String formatSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    public void cleanup() {
        stopCurrentMedia();
        executor.shutdownNow();
    }

    private void updateDetailPanel(FileView file, Image img, Path absolutePath) {
        this.currentFile = file;
        this.currentImage = img;
        this.currentPath = absolutePath;
        detailName.setText(file.name() != null ? file.name() : "—");
        detailType.setText(formatType(file.type()));
        detailSize.setText(formatSize(file.fileSize()));
        detailDate.setText(file.createDate() != null ? file.createDate() : "—");
        detailDevice.setText(file.deviceName() != null ? file.deviceName() : "—");
        detailUser.setText(file.username() != null ? file.username() : "—");
        detailStatus.setText(formatStatus(file.status()));
        detailDimension.setText(img != null
                ? (int) img.getWidth() + " × " + (int) img.getHeight()
                : "—");
        detailGps.setText("…");
        executor.submit(() -> {
            assert file.type() != null;
            Optional<GpsCoordinate> gps = mediaMetadataService.readGps(absolutePath, file.type());
            Platform.runLater(() ->
                    detailGps.setText(gps.map(GpsCoordinate::toString).orElse("—"))
            );
        });
    }

    private void setupMediaPlayer(FileView file) {
        mediaView.setFitWidth(contentPane.getWidth());
        mediaView.setFitHeight(contentPane.getHeight());
        mediaViewWrapper.setMaxWidth(contentPane.getWidth());
        mediaViewWrapper.setMaxHeight(contentPane.getHeight());
        mediaViewWrapper.setPrefWidth(contentPane.getWidth());
        mediaViewWrapper.setPrefHeight(contentPane.getHeight());

        boundsListener = (obs, oldVal, newVal) -> {
            double x = (videoPane.getWidth() - newVal.getWidth()) / 2;
            double y = (videoPane.getHeight() - newVal.getHeight()) / 2;
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
        videoPane.widthProperty().addListener(videoPaneWidthListener);
        videoPane.heightProperty().addListener(videoPaneHeightListener);

        if (cbSpeed.getItems().isEmpty()) {
            cbSpeed.getItems().addAll("0.25x", "0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x");
        }
        cbSpeed.setValue("1x");

        mediaPlayer.volumeProperty().bind(volumeSlider.valueProperty());

        mediaPlayer.currentTimeProperty().addListener((obs, oldVal, newVal) -> {
            if (!sliderDragging) {
                Duration total = mediaPlayer.getTotalDuration();
                if (total != null && total.greaterThan(Duration.ZERO)) {
                    videoSlider.setValue(newVal.toSeconds() / total.toSeconds() * 100);
                }
                lblVideoTime.setText(formatDuration(newVal) + " / " + formatDuration(total));
                updateGpsForTime(newVal.toSeconds());
            }
        });

        videoSlider.setOnMousePressed(e -> sliderDragging = true);
        videoSlider.setOnMouseReleased(e -> {
            sliderDragging = false;
            Duration total = mediaPlayer.getTotalDuration();
            if (total != null) {
                mediaPlayer.seek(total.multiply(videoSlider.getValue() / 100));
            }
        });

        mediaView.setOnMouseClicked(e -> onPlayPause());

        videoPane.setFocusTraversable(true);
        videoPane.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case LEFT -> seek(-5);
                case RIGHT -> seek(5);
                default -> seek(0);
            }
            e.consume();
        });

        mediaPlayer.setOnReady(() -> {
            int width = mediaPlayer.getMedia().getWidth();
            int height = mediaPlayer.getMedia().getHeight();
            if (width > 0 && height > 0) {
                Platform.runLater(() -> detailDimension.setText(width + " × " + height));
            }
            lblVideoTime.setText("00:00 / " + formatDuration(mediaPlayer.getTotalDuration()));
            mediaPlayer.play();
            btnPlayPause.setText("⏸");
            Platform.runLater(() -> videoPane.requestFocus());
        });

        mediaPlayer.setOnEndOfMedia(() -> {
            btnPlayPause.setText("▶");
            videoSlider.setValue(0);
        });

        mediaPlayer.setOnError(() ->
                Platform.runLater(() -> showError(I18n.get("media.viewer.error.video.playback", file.name())))
        );

        lblFileName.setText(file.name());
        lblFileSize.setText(formatSize(file.fileSize()));
        lblZoom.setText("");
        lblDimension.setText("");
    }

    @FXML
    private void onPlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            mediaPlayer.pause();
            btnPlayPause.setText("▶");
        } else {
            mediaPlayer.play();
            btnPlayPause.setText("⏸");
        }
    }

    @FXML
    private void onSeekBack5() {
        seek(-5);
    }

    @FXML
    private void onSeekBack30() {
        seek(-30);
    }

    @FXML
    private void onSeekForward5() {
        seek(5);
    }

    @FXML
    private void onSeekForward30() {
        seek(30);
    }

    private void seek(int seconds) {
        if (mediaPlayer == null) return;
        Duration current = mediaPlayer.getCurrentTime();
        Duration total = mediaPlayer.getTotalDuration();
        Duration target = current.add(Duration.seconds(seconds));

        if (target.lessThan(Duration.ZERO)) target = Duration.ZERO;
        if (target.greaterThan(total)) target = total;

        mediaPlayer.seek(target);
    }

    @FXML
    private void onSpeedChanged() {
        if (mediaPlayer == null || cbSpeed.getValue() == null) return;
        try {
            double rate = Double.parseDouble(cbSpeed.getValue().replace("x", ""));
            mediaPlayer.setRate(rate);
        } catch (NumberFormatException e) {
            log.warn("Invalid speed format: {}", cbSpeed.getValue());
        }
    }

    private void stopCurrentMedia() {
        if (mediaPlayer != null) {
            mediaPlayer.volumeProperty().unbind();
            mediaPlayer.stop();
            mediaPlayer.dispose();
            mediaPlayer = null;
        }
        if (boundsListener != null) {
            mediaView.boundsInLocalProperty().removeListener(boundsListener);
            boundsListener = null;
        }
        if (videoPaneWidthListener != null) {
            videoPane.widthProperty().removeListener(videoPaneWidthListener);
            videoPaneWidthListener = null;
        }
        if (videoPaneHeightListener != null) {
            videoPane.heightProperty().removeListener(videoPaneHeightListener);
            videoPaneHeightListener = null;
        }
        mediaView.setLayoutX(0);
        mediaView.setLayoutY(0);
    }

    private void showVideoPane() {
        lblLoading.setVisible(false);
        lblLoading.setManaged(false);
        lblError.setVisible(false);
        lblError.setManaged(false);
        scrollPane.setVisible(false);
        scrollPane.setManaged(false);
        videoPane.setVisible(true);
        videoPane.setManaged(true);
        setImageToolsVisible(false);
    }

    private String formatDuration(Duration duration) {
        if (duration == null || duration.isUnknown()) return "00:00";
        int totalSeconds = (int) duration.toSeconds();
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void updateGpsForTime(double currentSeconds) {
        if (gpsTimeline == null || gpsTimeline.isEmpty()) return;

        GpsPoint best = gpsTimeline.getFirst();
        for (GpsPoint point : gpsTimeline) {
            if (point.timeSeconds() <= currentSeconds) {
                best = point;
            } else {
                break;
            }
        }

        GpsCoordinate coord = best.toCoordinate();
        detailGps.setText(coord.toString());
    }

    private String formatStatus(String status) {
        if (status == null || status.isEmpty()) return "—";
        return I18n.get("file.status." + status);
    }

    private String formatType(String type) {
        if (type == null || type.isEmpty()) return "—";
        return I18n.get("file.type." + type);
    }
}
