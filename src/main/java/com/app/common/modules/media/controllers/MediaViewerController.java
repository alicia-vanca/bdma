package com.app.common.modules.media.controllers;

import java.io.IOException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.kordamp.ikonli.fontawesome6.FontAwesomeRegular;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.app.common.dtos.FileView;
import com.app.common.events.FileBookmarkToggledEvent;
import com.app.common.models.User;
import com.app.common.modules.dataexport.services.DataExportService;
import com.app.common.modules.datarestore.services.RestoreService;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.media.dtos.GpsCoordinate;
import com.app.common.modules.media.dtos.GpsPoint;
import com.app.common.modules.media.helpers.MediaDetailsPanelHelper;
import com.app.common.modules.media.helpers.MediaPathResolverHelper;
import com.app.common.modules.media.services.MapTileCacheService;
import com.app.common.modules.media.services.MediaMetadataService;
import com.app.common.modules.media.services.MediaViewerService;
import com.app.common.modules.session.Session;
import com.app.common.services.FileBookmarkService;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.transform.Rotate;
import javafx.scene.web.WebView;
import lombok.Setter;

@Component
@Scope("prototype")
public class MediaViewerController {

  private static final Logger log = LoggerFactory.getLogger(MediaViewerController.class);

  private static final double PANEL_WIDTH_DETAIL = 220;
  private static final double PANEL_WIDTH_LOCATION = 330;
  private static final double PANEL_MIN_WIDTH = 160;
  private static final double PANEL_MAX_WIDTH = 600;

  private static final String CSS_ACTIVE_TAB = "media-viewer-tab-btn-active";

  // FXML
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
  private Label detailBookmark;
  @FXML
  private Label detailBookmarkStatus;
  @FXML
  private Label detailBookmarkAt;
  @FXML
  private VBox videoPane;
  @FXML
  private StackPane videoContentPane;
  @FXML
  private Pane mediaViewWrapper;
  @FXML
  private MediaView mediaView;
  @FXML
  private Button btnPlayPause;
  @FXML
  private Button btnMute;
  @FXML
  private Slider videoSlider;
  @FXML
  private Slider volumeSlider;
  @FXML
  private Label lblVideoTime;
  @FXML
  private ComboBox<String> cbSpeed;
  @FXML
  private Label lblAudioPlaceholder;
  @FXML
  private Button btnExport;
  @FXML
  private WebView mapView;
  @FXML
  private StackPane mapErrorPane;
  @FXML
  private Label mapErrorLabel;
  @FXML
  private Button btnTabDetail;
  @FXML
  private Button btnTabLocation;
  @FXML
  private Label lblLocationGpsKey;
  @FXML
  private Label lblLocationDateKey;
  @FXML
  private Label lblLocationDeviceKey;
  @FXML
  private Label locationGps;
  @FXML
  private Label locationDate;
  @FXML
  private Label locationDevice;
  @FXML
  private VBox leftPanel;
  @FXML
  private VBox detailPane;
  @FXML
  private VBox locationPane;
  @FXML
  private Pane dragBar;

  // State
  private FileView requestedFile;
  private FileView currentFile;
  private List<FileView> mediaList;
  private int currentIndex;
  private double zoomFactor = 1.0;
  private double rotation = 0;
  private boolean fitMode = true;
  private boolean userResized = false;
  private MediaPlaybackController playbackController;
  private MediaGpsMapController gpsMapController;

  // Dependencies
  private final MediaMetadataService mediaMetadataService;
  private final MediaViewerService mediaViewerService;
  private final MediaPathResolverHelper pathResolver;
  private final MapTileCacheService mapTileCacheService;
  private MediaDetailsPanelHelper detailsPanel;
  private final FileBookmarkService bookmarkService;
  private final DataExportService dataExportService;
  private final Session session;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "MediaViewer-Loader");
    t.setDaemon(true);
    return t;
  });
  @Setter
  private Consumer<FileView> onLoadFile;

  public MediaViewerController(FolderManagerService folderManagerService,
      MediaMetadataService mediaMetadataService, MediaViewerService mediaViewerService,
      MapTileCacheService mapTileCacheService, RestoreService restoreService,
      FileBookmarkService bookmarkService, DataExportService dataExportService, Session session) {
    this.mediaMetadataService = mediaMetadataService;
    this.mediaViewerService = mediaViewerService;
    this.mapTileCacheService = mapTileCacheService;
    this.pathResolver = new MediaPathResolverHelper(folderManagerService, restoreService);
    this.bookmarkService = bookmarkService;
    this.dataExportService = dataExportService;
    this.session = session;
  }

  public void refreshLocalizedText() {
    btnTabDetail.setText(I18n.get("media.viewer.detail.title"));
    btnTabLocation.setText(I18n.get("media.viewer.location.title"));

    lblDetailNameKey.setText(I18n.get("media.viewer.detail.name"));
    lblDetailTypeKey.setText(I18n.get("media.viewer.detail.type"));
    lblDetailSizeKey.setText(I18n.get("media.viewer.detail.size"));
    lblDetailDateKey.setText(I18n.get("media.viewer.detail.date"));
    lblDetailDeviceKey.setText(I18n.get("media.viewer.detail.device"));
    lblDetailUserKey.setText(I18n.get("media.viewer.detail.user"));
    lblDetailStatusKey.setText(I18n.get("media.viewer.detail.status"));
    lblDetailDimensionKey.setText(I18n.get("media.viewer.detail.dimension"));
    lblLocationGpsKey.setText(I18n.get("media.viewer.location.gps"));
    lblLocationDateKey.setText(I18n.get("media.viewer.detail.date"));
    lblLocationDeviceKey.setText(I18n.get("media.viewer.location.device"));
    gpsMapController.refreshLocalizedText();
    btnExport.setText(I18n.get("file.export.selected"));
    if (currentFile != null) {
      detailsPanel.updateDetails(currentFile);
      // Refresh bookmark UI (status + timestamp) with new locale
      updateBookmarkUI(currentFile);
    }
  }

  // Init
  @FXML
  public void initialize() {
    detailsPanel = new MediaDetailsPanelHelper(
        new MediaDetailsPanelHelper.Dependencies(detailName, detailType, detailSize, detailDate,
            detailDevice, detailUser, detailStatus, detailDimension, locationGps, locationDate,
            locationDevice));
    initializePlaybackController();
    initializeGpsMapController();
    refreshLocalizedText();
    // Setup listener and resize handle
    setupScrollPaneListeners();
    setupResizeHandle();
    // Display default tabs
    showDefaultTab();
  }

  private void initializePlaybackController() {
    playbackController = new MediaPlaybackController(
        new MediaPlaybackController.PlaybackControllerDependencies(videoPane, videoContentPane,
            mediaViewWrapper, mediaView, btnPlayPause, btnMute, videoSlider, volumeSlider,
            lblVideoTime, cbSpeed, lblAudioPlaceholder, detailDimension,
            mediaViewerService::isAudio, this::updateGpsForTime));
    playbackController.initialize();
  }

  private void initializeGpsMapController() {
    gpsMapController = new MediaGpsMapController(
        new MediaGpsMapController.Dependencies(mapView, mapErrorPane, mapErrorLabel, locationPane,
            locationGps, mapTileCacheService));
    gpsMapController.initialize();
  }

  private void setupScrollPaneListeners() {
    scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
      if (e.isControlDown()) {
        applyZoom(e.getDeltaY() > 0 ? 1.1 : 0.9, new Point2D(e.getSceneX(), e.getSceneY()));
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

    detailBookmark.setOnMouseClicked(e -> onToggleBookmark());
  }

  // Public API
  public void setMedia(List<FileView> list, int startIndex) {
    this.mediaList = list;
    this.currentIndex = startIndex;
    loadCurrent();
  }

  public void refreshMapTheme(String theme) {
    gpsMapController.refreshTheme(theme);
  }

  // Navigation
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

  // Load
  private void loadCurrent() {
    FileView file = mediaList.get(currentIndex);
    requestedFile = file;
    currentFile = file;
    updateNavButtons();
    updateCounter();
    resetTransform();
    gpsMapController.resetForFile(file);
    clearLocationPanel();

    if (onLoadFile != null) {
      onLoadFile.accept(file);
    }
  }

  public void loadImage(FileView file) {
    showLoading();
    stopCurrentMedia();

    executor.submit(() -> {
      try {
        Path absolutePath = resolveMediaPath(file);
        String uri = absolutePath.toUri().toString();
        Image img = new Image(uri, true);
        AtomicBoolean completionHandled = new AtomicBoolean();

        img.progressProperty().addListener((obs, o, n) -> {
          if (n.doubleValue() >= 1.0 && completionHandled.compareAndSet(false, true)) {
            Platform.runLater(() -> onImageReady(img, file, absolutePath));
          }
        });

        if (img.getProgress() >= 1.0 && completionHandled.compareAndSet(false, true)) {
          Platform.runLater(() -> onImageReady(img, file, absolutePath));
        }

      } catch (Exception e) {
        log.error("Failed to load image: {}", file.syncedPath(), e);
        Platform.runLater(
            () -> showErrorIfCurrent(file, I18n.get("media.viewer.error.image.load", file.name())));
      }
    });
  }

  private void onImageReady(Image img, FileView file, Path absolutePath) {
    if (isNotCurrentRequestedFile(file)) {
      log.debug("Ignoring stale image load. completedFile={}, requestedFile={}", file.name(),
          requestedFile != null ? requestedFile.name() : "—");
      return;
    }
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
        Path absolutePath = resolveMediaPath(file);
        String uri = absolutePath.toUri().toString();
        loadVideoInternal(file, uri, absolutePath);
      } catch (Exception e) {
        log.error("Failed to load video: {}", file.syncedPath(), e);
        Platform.runLater(
            () -> showErrorIfCurrent(file, I18n.get("media.viewer.error.video.load", file.name())));
      }
    });
  }

  private void loadVideoInternal(FileView file, String uri, Path absolutePath) {
    Platform.runLater(() -> {
      if (isNotCurrentRequestedFile(file)) {
        log.debug("Ignoring stale video load.");
        return;
      }
      try {
        Media media = new Media(uri);
        MediaPlayer player = new MediaPlayer(media);

        loadGpsTimeline(file, absolutePath);
        playbackController.attach(file, player,
            () -> showError(I18n.get("media.viewer.error.video.playback", file.name())));
        showVideoPane();
        updateVideoStatusBar(file);
        updateVideoDetailPanel(file);
      } catch (Exception e) {
        log.error("Failed to create MediaPlayer: {}", file.syncedPath(), e);
        showError(I18n.get("media.viewer.error.video.play", file.name()));
      }
    });
  }

  private void loadGpsTimeline(FileView file, Path absolutePath) {
    executor.submit(() -> {
      List<GpsPoint> timeline = mediaMetadataService.readGpsTimeline(absolutePath, file.type());
      Platform.runLater(() -> {
        if (isNotCurrentRequestedFile(file)) {
          log.debug("Ignoring stale GPS timeline.");
          return;
        }
        GpsCoordinate gps = gpsMapController.setTimeline(timeline);
        updateLocationPanel(file, gps);
      });
    });
  }

  // GPS

  private void updateGpsForTime(double currentSeconds) {
    gpsMapController.updateForVideoTime(currentSeconds);
  }

  // Zoom
  @FXML
  private void onZoomIn() {
    applyZoom(1.2);
  }

  @FXML
  private void onZoomOut() {
    applyZoom(0.8);
  }

  @FXML
  private void onFit() {
    fitMode = true;
    fitImageToPane();
  }

  private void applyZoom(double factor) {
    applyZoom(factor, null);
  }

  private void applyZoom(double factor, Point2D sceneAnchor) {
    if (imageView.getImage() == null)
      return;

    Bounds viewportBounds = scrollPane.getViewportBounds();
    double viewportW = viewportBounds.getWidth() > 0 ? viewportBounds.getWidth() : scrollPane.getWidth() - 2;
    double viewportH = viewportBounds.getHeight() > 0 ? viewportBounds.getHeight() : scrollPane.getHeight() - 2;
    if (viewportW <= 0 || viewportH <= 0)
      return;

    Point2D anchorInViewport = resolveZoomAnchor(sceneAnchor, viewportW, viewportH);
    imageContainer.applyCss();
    imageContainer.layout();

    Bounds oldImageBounds = imageView.getBoundsInParent();
    double oldContentW = imageContainer.getLayoutBounds().getWidth();
    double oldContentH = imageContainer.getLayoutBounds().getHeight();
    double oldMaxScrollX = Math.max(oldContentW - viewportW, 0);
    double oldMaxScrollY = Math.max(oldContentH - viewportH, 0);
    double anchorContentX = oldMaxScrollX * scrollPane.getHvalue() + anchorInViewport.getX();
    double anchorContentY = oldMaxScrollY * scrollPane.getVvalue() + anchorInViewport.getY();
    double imageAnchorRatioX = oldImageBounds.getWidth() > 0
        ? Math.clamp((anchorContentX - oldImageBounds.getMinX()) / oldImageBounds.getWidth(), 0, 1)
        : 0.5;
    double imageAnchorRatioY = oldImageBounds.getHeight() > 0
        ? Math.clamp((anchorContentY - oldImageBounds.getMinY()) / oldImageBounds.getHeight(), 0, 1)
        : 0.5;

    fitMode = false;
    zoomFactor *= factor;
    double fitW = imageView.getImage().getWidth() * zoomFactor;
    double fitH = imageView.getImage().getHeight() * zoomFactor;

    imageView.setFitWidth(fitW);
    imageView.setFitHeight(fitH);
    updateImageRotationTransform();

    resizeImageContainerForViewport(viewportW, viewportH);

    scrollToZoomAnchor(anchorInViewport, imageAnchorRatioX, imageAnchorRatioY, viewportW,
        viewportH);

    lblZoom.setText(new DecimalFormat("##0%").format(zoomFactor));
  }

  private void scrollToZoomAnchor(Point2D anchorInViewport, double imageAnchorRatioX,
      double imageAnchorRatioY, double viewportW, double viewportH) {
    if (viewportW <= 0 || viewportH <= 0)
      return;

    imageContainer.applyCss();
    imageContainer.layout();

    double contentW = imageContainer.getLayoutBounds().getWidth();
    double contentH = imageContainer.getLayoutBounds().getHeight();
    Bounds imageBounds = imageView.getBoundsInParent();
    double targetContentX = imageBounds.getMinX() + imageBounds.getWidth() * imageAnchorRatioX;
    double targetContentY = imageBounds.getMinY() + imageBounds.getHeight() * imageAnchorRatioY;
    double maxScrollX = Math.max(contentW - viewportW, 0);
    double maxScrollY = Math.max(contentH - viewportH, 0);
    scrollPane
        .setHvalue(maxScrollX > 0 ? Math.clamp((targetContentX - anchorInViewport.getX()) / maxScrollX, 0, 1) : 0);
    scrollPane
        .setVvalue(maxScrollY > 0 ? Math.clamp((targetContentY - anchorInViewport.getY()) / maxScrollY, 0, 1) : 0);
  }

  private Point2D resolveZoomAnchor(Point2D sceneAnchor, double viewportW, double viewportH) {
    Node viewport = scrollPane.lookup(".viewport");
    if (sceneAnchor != null && viewport != null) {
      Point2D localAnchor = viewport.sceneToLocal(sceneAnchor);
      if (localAnchor.getX() >= 0 && localAnchor.getX() <= viewportW && localAnchor.getY() >= 0
          && localAnchor.getY() <= viewportH) {
        return localAnchor;
      }
    }
    return new Point2D(viewportW / 2, viewportH / 2);
  }

  private void resizeImageContainerForViewport(double viewportW, double viewportH) {
    double visualW = rotatedVisualWidth(imageView.getFitWidth(), imageView.getFitHeight());
    double visualH = rotatedVisualHeight(imageView.getFitWidth(), imageView.getFitHeight());
    double contentW = Math.max(visualW, viewportW);
    double contentH = Math.max(visualH, viewportH);

    imageContainer.setMinWidth(contentW);
    imageContainer.setMinHeight(contentH);
    imageContainer.setPrefWidth(contentW);
    imageContainer.setPrefHeight(contentH);
    imageContainer.resize(contentW, contentH);
    imageContainer.layout();
  }

  private double rotatedVisualWidth(double width, double height) {
    double radians = Math.toRadians(normalizedRotation());
    return Math.abs(width * Math.cos(radians)) + Math.abs(height * Math.sin(radians));
  }

  private double rotatedVisualHeight(double width, double height) {
    double radians = Math.toRadians(normalizedRotation());
    return Math.abs(width * Math.sin(radians)) + Math.abs(height * Math.cos(radians));
  }

  private void updateImageRotationTransform() {
    imageView.getTransforms().clear();
    double normalizedRotation = normalizedRotation();
    if (normalizedRotation != 0) {
      imageView.getTransforms().add(new Rotate(normalizedRotation, imageView.getFitWidth() / 2,
          imageView.getFitHeight() / 2));
    }
  }

  private double normalizedRotation() {
    return ((rotation % 360) + 360) % 360;
  }

  private void fitImageToPane() {
    if (imageView.getImage() == null)
      return;

    double paneW = scrollPane.getWidth() - 2;
    double paneH = scrollPane.getHeight() - 2;
    if (paneW <= 0 || paneH <= 0)
      return;

    double imgW = imageView.getImage().getWidth();
    double imgH = imageView.getImage().getHeight();
    double visualImgW = rotatedVisualWidth(imgW, imgH);
    double visualImgH = rotatedVisualHeight(imgW, imgH);
    double scale = Math.min(paneW / visualImgW, paneH / visualImgH);

    imageView.setFitWidth(imgW * scale);
    imageView.setFitHeight(imgH * scale);
    updateImageRotationTransform();
    resizeImageContainerForViewport(paneW, paneH);

    zoomFactor = scale;
    lblZoom.setText(new DecimalFormat("##0%").format(zoomFactor));
  }

  // Rotate
  @FXML
  private void onRotateLeft() {
    applyRotation(-90);
  }

  @FXML
  private void onRotateRight() {
    applyRotation(90);
  }

  private void applyRotation(double angle) {
    if (imageView.getImage() == null)
      return;

    Bounds viewportBounds = scrollPane.getViewportBounds();
    double viewportW = viewportBounds.getWidth() > 0 ? viewportBounds.getWidth() : scrollPane.getWidth() - 2;
    double viewportH = viewportBounds.getHeight() > 0 ? viewportBounds.getHeight() : scrollPane.getHeight() - 2;
    Point2D anchorInViewport = new Point2D(Math.max(viewportW, 0) / 2, Math.max(viewportH, 0) / 2);
    double imageAnchorRatioX = 0.5;
    double imageAnchorRatioY = 0.5;
    if (viewportW > 0 && viewportH > 0) {
      imageContainer.applyCss();
      imageContainer.layout();

      Bounds oldImageBounds = imageView.getBoundsInParent();
      double oldContentW = imageContainer.getLayoutBounds().getWidth();
      double oldContentH = imageContainer.getLayoutBounds().getHeight();
      double oldMaxScrollX = Math.max(oldContentW - viewportW, 0);
      double oldMaxScrollY = Math.max(oldContentH - viewportH, 0);
      double anchorContentX = oldMaxScrollX * scrollPane.getHvalue() + anchorInViewport.getX();
      double anchorContentY = oldMaxScrollY * scrollPane.getVvalue() + anchorInViewport.getY();
      imageAnchorRatioX = oldImageBounds.getWidth() > 0
          ? Math.clamp((anchorContentX - oldImageBounds.getMinX()) / oldImageBounds.getWidth(), 0,
              1)
          : 0.5;
      imageAnchorRatioY = oldImageBounds.getHeight() > 0
          ? Math.clamp((anchorContentY - oldImageBounds.getMinY()) / oldImageBounds.getHeight(), 0,
              1)
          : 0.5;
    }

    rotation = normalizedRotation() + angle;
    updateImageRotationTransform();
    if (viewportW > 0 && viewportH > 0) {
      resizeImageContainerForViewport(viewportW, viewportH);
      scrollToZoomAnchor(anchorInViewport, imageAnchorRatioX, imageAnchorRatioY, viewportW,
          viewportH);
    }
  }

  // State
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

  private void showErrorIfCurrent(FileView file, String message) {
    if (!isNotCurrentRequestedFile(file)) {
      showError(message);
    }
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
    lblFileSize.setText(MediaDetailsPanelHelper.formatSize(file.fileSize()));
    if (img != null) {
      lblDimension.setText((int) img.getWidth() + " × " + (int) img.getHeight());
    }
    lblZoom.setText("Fit");
  }

  private static final String STYLE_BOOKMARKED = "bookmarked";
  private static final String STYLE_BOOKMARK_ICON = "bookmark-icon";

  private void clearStatusBar() {
    lblFileName.setText("");
    lblDimension.setText("");
    lblFileSize.setText("");
    lblZoom.setText("");
    detailBookmarkStatus.setText("");
    detailBookmarkAt.setText("—");
    setBookmarkIcon(false);

    detailsPanel.clear();
  }

  private void clearLocationPanel() {
    detailsPanel.clearLocation();
  }

  // Tab switching
  private void showDefaultTab() {
    onTabDetail();
  }

  @FXML
  private void onTabDetail() {
    switchTab(true);
  }

  @FXML
  private void onTabLocation() {
    switchTab(false);
  }

  private void switchTab(boolean showDetail) {
    detailPane.setVisible(showDetail);
    detailPane.setManaged(showDetail);
    locationPane.setVisible(!showDetail);
    locationPane.setManaged(!showDetail);

    btnTabDetail.getStyleClass().remove(CSS_ACTIVE_TAB);
    btnTabLocation.getStyleClass().remove(CSS_ACTIVE_TAB);

    if (showDetail) {
      btnTabDetail.getStyleClass().add(CSS_ACTIVE_TAB);
      if (!userResized)
        setPanelWidth(PANEL_WIDTH_DETAIL);
    } else {
      btnTabLocation.getStyleClass().add(CSS_ACTIVE_TAB);
      if (!userResized)
        setPanelWidth(PANEL_WIDTH_LOCATION);
      gpsMapController.refreshMapSize();
    }
  }

  private void setPanelWidth(double w) {
    leftPanel.setMinWidth(w);
    leftPanel.setMaxWidth(w);
    leftPanel.setPrefWidth(w);
  }

  private void setupResizeHandle() {
    final double[] dragStartX = { 0 };
    final double[] dragStartWidth = { 0 };

    dragBar.setCursor(Cursor.H_RESIZE);

    dragBar.setOnMousePressed(e -> {
      dragStartX[0] = e.getScreenX();
      dragStartWidth[0] = leftPanel.getWidth();
      userResized = false;
      e.consume();
    });

    dragBar.setOnMouseDragged(e -> {
      double delta = e.getScreenX() - dragStartX[0];
      double newWidth = Math.clamp(dragStartWidth[0] + delta, PANEL_MIN_WIDTH, PANEL_MAX_WIDTH);
      leftPanel.setMinWidth(newWidth);
      leftPanel.setMaxWidth(newWidth);
      leftPanel.setPrefWidth(newWidth);
      userResized = true;
      e.consume();
    });

    dragBar.setOnMouseReleased(Event::consume);
  }

  private Path resolveMediaPath(FileView file) throws IOException {
    return pathResolver.resolve(file);
  }

  private boolean isNotCurrentRequestedFile(FileView file) {
    return !Objects.equals(requestedFile, file);
  }

  public void cleanup() {
    stopCurrentMedia();
    gpsMapController.cleanup();
    executor.shutdownNow();
  }

  private void updateDetailPanel(FileView file, Image img, Path absolutePath) {
    prepareDetailPanel(file, img);
    loadStandaloneGps(file, absolutePath);
  }

  private void updateVideoDetailPanel(FileView file) {
    prepareDetailPanel(file, null);
  }

  private void updateVideoStatusBar(FileView file) {
    lblFileName.setText(file.name());
    lblFileSize.setText(MediaDetailsPanelHelper.formatSize(file.fileSize()));
    lblZoom.setText("");
    lblDimension.setText("");
  }

  private void prepareDetailPanel(FileView file, Image img) {
    this.currentFile = file;
    detailsPanel.updateDetails(file);
    detailsPanel.setDimension(
        img != null ? (int) img.getWidth() + " × " + (int) img.getHeight() : "—");
    updateBookmarkUI(file);
  }

  private void onToggleBookmark() {
    if (currentFile == null || currentFile.fileId() == null) {
      return;
    }
    try {
      boolean currentlyBookmarked = bookmarkService.isBookmarked(currentFile.fileId());
      bookmarkService.setBookmarked(currentFile.fileId(), !currentlyBookmarked);
    } catch (Exception e) {
      log.error("Failed to set bookmark for file {}", currentFile.fileId(), e);
    }
  }

  private void loadStandaloneGps(FileView file, Path absolutePath) {
    executor.submit(() -> {
      assert file.type() != null;
      log.trace("Reading GPS metadata. file={}, type={}, path={}", file.name(), file.type(),
          absolutePath);
      Optional<GpsCoordinate> gps = mediaMetadataService.readGps(absolutePath, file.type());
      log.trace("GPS metadata read complete. file={}, found={}, value={}", file.name(),
          gps.isPresent(), gps.map(GpsCoordinate::toString).orElse("—"));
      Platform.runLater(() -> {
        if (isNotCurrentRequestedFile(file)) {
          log.debug("Ignoring stale GPS metadata. completedFile={}, requestedFile={}", file.name(),
              requestedFile != null ? requestedFile.name() : "—");
          return;
        }
        updateLocationPanel(file, gps.orElse(null));
      });
    });
  }

  private void updateLocationPanel(FileView file, GpsCoordinate gps) {
    detailsPanel.updateLocation(file, gps);

    log.trace("Location metadata loaded. file={}, hasGps={}, timelinePoints={}", file.name(),
        gps != null, gpsMapController.timelinePointCount());

    gpsMapController.setCurrentGps(gps);
  }

  @FXML
  private void onExport() {
    if (currentFile == null || currentFile.fileId() == null) {
      return;
    }
    dataExportService.exportSelectedFiles(List.of(currentFile));
  }

  private void updateBookmarkUI(FileView file) {
    if (file.fileId() == null) {
      setBookmarkIcon(false);
      detailBookmarkStatus.setText("");
      detailBookmarkAt.setText("—");
      return;
    }
    boolean bookmarked = bookmarkService.isBookmarked(file.fileId());
    if (bookmarked) {
      Optional<String> timestamp = bookmarkService.getBookmarkTimestamp(file.fileId());
      setBookmarkIcon(true);
      detailBookmarkStatus.setText(I18n.get("bookmark.status"));
      detailBookmarkAt.setText(timestamp.map(this::formatBookmarkTimestamp).orElse(""));
    } else {
      setBookmarkIcon(false);
      detailBookmarkStatus.setText("");
      detailBookmarkAt.setText("—");
    }
  }

  private void setBookmarkIcon(boolean bookmarked) {
    FontIcon icon;
    if (bookmarked) {
      icon = new FontIcon(FontAwesomeSolid.BOOKMARK);
      icon.getStyleClass().add(STYLE_BOOKMARKED);
    } else {
      icon = new FontIcon(FontAwesomeRegular.BOOKMARK);
    }
    icon.getStyleClass().add(STYLE_BOOKMARK_ICON);
    detailBookmark.setGraphic(icon);
    detailBookmark.setText(null);
  }

  private String formatBookmarkTimestamp(String rawTimestamp) {
    if (rawTimestamp == null || rawTimestamp.isBlank())
      return "";
    try {
      LocalDateTime dt = LocalDateTime.parse(rawTimestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
      Locale locale = I18n.getLocale();
      String pattern = locale.getLanguage().equals("vi") ? "dd/MM/yyyy HH:mm" : "MMM dd, yyyy HH:mm";
      return dt.format(DateTimeFormatter.ofPattern(pattern, locale));
    } catch (Exception e) {
      return rawTimestamp;
    }
  }

  public void onBookmarkChanged(FileBookmarkToggledEvent event) {
    Platform.runLater(() -> {
      User currentUser = session.getUser();
      if (currentUser == null || !Objects.equals(currentUser.getId(), event.getUserId())) {
        return;
      }
      if (currentFile == null || !event.getFileIds().contains(currentFile.fileId())) {
        return;
      }
      if (event.isBookmarked()) {
        setBookmarkIcon(true);
        detailBookmarkStatus.setText(I18n.get("bookmark.status"));
        String ts = event.getUpdatedAt();
        if (ts == null) {
          ts = bookmarkService.getBookmarkTimestamp(currentFile.fileId()).orElse(null);
        }
        detailBookmarkAt.setText(
                ts != null ? formatBookmarkTimestamp(ts) : "—");
      } else {
        setBookmarkIcon(false);
        detailBookmarkStatus.setText("");
        detailBookmarkAt.setText("—");
      }
    });
  }

  @FXML
  private void onPlayPause() {
    playbackController.togglePlayback();
  }

  @FXML
  private void onSeekBack5() {
    playbackController.seekBy(-5);
  }

  @FXML
  private void onSeekBack30() {
    playbackController.seekBy(-30);
  }

  @FXML
  private void onSeekForward5() {
    playbackController.seekBy(5);
  }

  @FXML
  private void onSeekForward30() {
    playbackController.seekBy(30);
  }

  @FXML
  private void onSpeedChanged() {
    playbackController.changeSpeed();
  }

  @FXML
  private void onToggleMute() {
    playbackController.toggleMute();
  }

  private void stopCurrentMedia() {
    playbackController.stop();
    gpsMapController.resetLogicalPath();
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
}
