package com.app.common.modules.media.services;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.app.common.definitions.ViewPaths;
import com.app.common.dtos.FileView;
import com.app.common.events.LanguageChangedEvent;
import com.app.common.events.ThemeChangedEvent;
import com.app.common.helpers.CssLoader;
import com.app.common.helpers.ViewLoader;
import com.app.common.modules.media.controllers.MediaViewerController;
import com.app.common.modules.theme.ThemeManager;
import com.app.common.utils.StageUtil;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;

@Service
public class MediaViewerService {

    private static final Logger log = LoggerFactory.getLogger(MediaViewerService.class);

    private final ViewLoader viewLoader;

    // Singleton stage
    private Stage viewerStage;
    private MediaViewerController controller;

    public MediaViewerService(ViewLoader viewLoader) {
        this.viewLoader = viewLoader;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void open(List<FileView> allFiles, FileView clicked, Stage owner) {
        List<FileView> viewableList = allFiles.stream()
                .filter(f -> isViewable(f.type()))
                .toList();

        if (viewableList.isEmpty()) return;

        int index = viewableList.indexOf(clicked);
        if (index < 0) index = 0;
        final int startIndex = index;

        if (viewerStage == null) {
            initStage(owner);

            viewerStage.setOnShown(e -> {
                controller.setMedia(viewableList, startIndex);
                controller.setOnLoadFile(this::dispatchLoad);
            });
        } else {
            controller.setMedia(viewableList, startIndex);
            controller.setOnLoadFile(this::dispatchLoad);
        }

        viewerStage.show();
        viewerStage.toFront();
    }

    // ── Stage init ────────────────────────────────────────────────────────────

    private void initStage(Stage owner) {
        try {
            ViewLoader.LoadResult<MediaViewerController> result =
                    viewLoader.loadView(ViewPaths.MEDIA_VIEWER);

            if (result == null) {
                log.error("Failed to load media-viewer FXML");
                return;
            }

            controller = result.controller();
            controller.setOnLoadFile(this::dispatchLoad);

            Parent root = (Parent) result.node();
            Scene scene = new Scene(root);
            CssLoader.applyDialog(scene, ViewPaths.MEDIA_VIEWER);
            ThemeManager.apply(scene);

            viewerStage = new Stage();
            StageUtil.applyAppIcon(viewerStage);
            viewerStage.setTitle("MEDIA");
            viewerStage.setScene(scene);
            viewerStage.initOwner(owner);
            viewerStage.initModality(Modality.NONE);
            viewerStage.setMinWidth(640);
            viewerStage.setMinHeight(480);

            viewerStage.setOnCloseRequest(e -> {
                if (controller != null) {
                    controller.cleanup();
                }
                viewerStage = null;
                controller = null;
            });

        } catch (Exception e) {
            log.error("Failed to init MediaViewer stage", e);
        }
    }

    // ── Type dispatch ─────────────────────────────────────────────────────────

    private void dispatchLoad(FileView file) {
        if (isImage(file.type())) {
            controller.loadImage(file);
        } else if (isVideo(file.type())) {
            controller.loadVideo(file); // placeholder, implement sau
        }
    }

    // ── Type helpers ──────────────────────────────────────────────────────────

    public boolean isViewable(String type) {
        return isImage(type) || isVideo(type);
    }

    private boolean isImage(String type) {
        return "image".equals(type);
    }

    private boolean isVideo(String type) {
        if (type == null) return false;
        return switch (type) {
            case "video", "IMP" -> true;
            default -> false;
        };
    }

    public void close() {
        if (viewerStage != null) {
            if (controller != null) {
                controller.cleanup();
            }
            viewerStage.close();
            viewerStage = null;
            controller = null;
        }
    }

    @EventListener
    public void onThemeChanged(ThemeChangedEvent event) {
        if (viewerStage != null && viewerStage.getScene() != null) {
            Platform.runLater(() -> ThemeManager.apply(viewerStage.getScene()));
        }
    }

    @EventListener
    public void onLanguageChanged(LanguageChangedEvent event) {
        if (controller != null) {
            Platform.runLater(() -> controller.refreshLocalizedText());
        }
    }
}
