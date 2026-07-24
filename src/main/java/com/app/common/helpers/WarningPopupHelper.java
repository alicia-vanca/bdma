package com.app.common.helpers;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.util.Duration;

public class WarningPopupHelper {

    private static final double WINDOW_EDGE_GAP = 8.0;
    private static final double ARROW_EDGE_GAP = 18.0;
    private static final double WARNING_GRAPHIC_TIP_OFFSET_X = -9.0;
    private static final double POPUP_OFFSET_X = -95.0;

    private final Label anchor;
    private final double gap;
    private final Duration fadeInDuration;
    private final Duration visibleDuration;
    private final Duration fadeOutDuration;

    private Popup popup;
    private VBox popupTextList;
    private StackPane popupArrow;
    private FadeTransition fadeTransition;
    private PauseTransition previewHideDelay;
    private EventHandler<MouseEvent> ownerMousePressedFilter;
    private Scene ownerSceneWithFilter;
    private Scene ownerSceneWithResizeListener;
    private final ChangeListener<Number> ownerResizeListener = (observable, oldValue, newValue) ->
            hide();
    private final ChangeListener<Boolean> ownerFocusListener = (observable, wasFocused, focused) ->
            handleOwnerFocusChanged(focused);
    private java.util.List<String> currentWarnings = java.util.List.of();
    private String currentText = "";
    private boolean textInitialized;
    private boolean mouseInside;
    private boolean pinned;
    private boolean hiding;

    public WarningPopupHelper(Label anchor, double gap, Duration fadeInDuration, Duration fadeOutDuration) {
        this(anchor, gap, fadeInDuration, Duration.ZERO, fadeOutDuration);
    }

    public WarningPopupHelper(Label anchor, double gap, Duration fadeInDuration,
            Duration visibleDuration, Duration fadeOutDuration) {
        this.anchor = anchor;
        this.gap = gap;
        this.fadeInDuration = fadeInDuration;
        this.visibleDuration = visibleDuration;
        this.fadeOutDuration = fadeOutDuration;
    }

    public void initialize() {
        if (anchor == null) {
            return;
        }

        anchor.setVisible(false);
        anchor.setManaged(false);

        popup = new Popup();
        popup.setAutoFix(false);
        popup.setAutoHide(false);
        popup.setHideOnEscape(true);

        VBox popupRoot = createPopupContent();
        popupRoot.setOpacity(0);
        popupRoot.setOnMouseEntered(event -> {
            mouseInside = true;
            show(false);
        });
        popupRoot.setOnMouseExited(event -> {
            mouseInside = false;
            if (!pinned) {
                schedulePreviewHide();
            }
        });
        popup.getContent().setAll(popupRoot);
        popup.setOnHidden(event -> {
            pinned = false;
            hiding = false;
            stopFade();
            stopPreviewHideDelay();
            removeOwnerOutsideClickFilter();
            popupRoot.setOpacity(0);
        });

        anchor.setOnMouseEntered(event -> {
            mouseInside = true;
            show(false);
        });
        anchor.setOnMouseMoved(event -> {
            mouseInside = true;
            show(false);
        });
        anchor.setOnMouseExited(event -> {
            mouseInside = false;
            if (!pinned) {
                schedulePreviewHide();
            }
        });
        anchor.setOnMouseClicked(event -> {
            if (currentWarnings.isEmpty()) {
                return;
            }
            pinned = !pinned;
            if (pinned) {
                show(true);
            } else {
                hide();
            }
        });
        anchor.sceneProperty().addListener((observable, oldScene, newScene) -> {
            detachOwnerSceneListeners(oldScene);
            attachOwnerSceneListeners(newScene);
        });
        Platform.runLater(() -> attachOwnerSceneListeners(anchor.getScene()));
    }

    public void setText(String text) {
        java.util.List<String> nextWarnings = normalizeWarnings(text);
        String nextText = normalizeText(nextWarnings);
        boolean textChanged = !nextText.equals(currentText);
        currentWarnings = nextWarnings;
        currentText = nextText;
        boolean hasText = !currentWarnings.isEmpty();

        anchor.setVisible(hasText);
        anchor.setManaged(hasText);
        updatePopupWarnings();
        if (!hasText) {
            hide();
            textInitialized = true;
            return;
        }
        if (textInitialized && textChanged) {
            showTextChangePreview();
        }
        textInitialized = true;
    }

    private void showTextChangePreview() {
        show(false, () -> {
            if (!pinned && !mouseInside) {
                schedulePreviewHide();
            }
        });
    }

    public void hide() {
        pinned = false;
        if (popup == null || !popup.isShowing()) {
            return;
        }
        if (hiding) {
            return;
        }
        stopPreviewHideDelay();
        hiding = true;
        fadeTo(0, fadeOutDuration, () -> Platform.runLater(popup::hide));
    }

    private VBox createPopupContent() {
        StackPane icon = createPopupIcon();

        popupTextList = new VBox();
        popupTextList.getStyleClass().add("status-warning-popup-text-list");
        updatePopupWarnings();

        Button closeButton = new Button("\u00D7");
        closeButton.getStyleClass().add("status-warning-popup-close");
        closeButton.setOnAction(event -> hide());

        HBox row = new HBox(10, icon, popupTextList);
        row.setAlignment(Pos.CENTER_LEFT);

        StackPane card = new StackPane(row, closeButton);
        StackPane.setAlignment(row, Pos.CENTER_LEFT);
        StackPane.setAlignment(closeButton, Pos.TOP_RIGHT);
        card.getStyleClass().add("status-warning-popup-card");

        popupArrow = createPopupArrow();

        VBox popupBox = new VBox(card, popupArrow);
        popupBox.setAlignment(Pos.TOP_CENTER);
        popupBox.setStyle("-fx-background-color: transparent;");

        popupBox.getStyleClass().add("status-warning-popup");
        syncStylesheets(popupBox);
        return popupBox;
    }

    private StackPane createPopupArrow() {
        Region fill = new Region();
        fill.getStyleClass().add("status-warning-popup-arrow-fill");

        Region leftBorder = new Region();
        leftBorder.getStyleClass().add("status-warning-popup-arrow-side-left");

        Region rightBorder = new Region();
        rightBorder.getStyleClass().add("status-warning-popup-arrow-side-right");

        StackPane arrow = new StackPane(fill, leftBorder, rightBorder);
        arrow.getStyleClass().add("status-warning-popup-arrow");
        return arrow;
    }

    private StackPane createPopupIcon() {
        Region triangle = new Region();
        triangle.getStyleClass().add("status-warning-popup-icon-triangle");

        Region bar = new Region();
        bar.getStyleClass().add("status-warning-popup-icon-bar");

        Region dot = new Region();
        dot.getStyleClass().add("status-warning-popup-icon-dot");

        StackPane icon = new StackPane(triangle, bar, dot);
        icon.getStyleClass().add("status-warning-popup-icon");
        return icon;
    }

    private void show(boolean shouldPin) {
        show(shouldPin, null);
    }

    private void show(boolean shouldPin, Runnable onShown) {
        if (popup == null || popupTextList == null || currentWarnings.isEmpty()
                || anchor.getScene() == null
                || anchor.getScene().getWindow() == null) {
            return;
        }

        pinned = shouldPin || pinned;
        hiding = false;
        stopPreviewHideDelay();
        updatePopupWarnings();
        syncStylesheets(popup.getContent().getFirst());

        if (!popup.isShowing()) {
            Node popupContent = popup.getContent().getFirst();
            popupContent.setOpacity(0);
            Bounds anchorBounds = anchor.localToScreen(anchor.getBoundsInLocal());
            double initialX = anchorBounds != null ? anchorBounds.getMinX() : 0;
            double initialY = anchorBounds != null ? anchorBounds.getMinY() : 0;
            popup.show(anchor, initialX, initialY);
            popup.getScene().setFill(Color.TRANSPARENT);
            popup.getScene().getRoot().setStyle("-fx-background-color: transparent;");
        }
        installOwnerOutsideClickFilter();
        relocate();
        Platform.runLater(this::relocate);
        fadeTo(1, fadeInDuration, onShown);
    }

    private void installOwnerOutsideClickFilter() {
        if (anchor.getScene() == null || ownerMousePressedFilter != null) {
            return;
        }

        ownerMousePressedFilter = event -> {
            if (popup == null || !popup.isShowing()) {
                return;
            }
            double screenX = event.getScreenX();
            double screenY = event.getScreenY();
            if (containsScreenPoint(anchor, screenX, screenY)
                    || containsScreenPoint(getPopupContent(), screenX, screenY)) {
                return;
            }
            hide();
        };
        ownerSceneWithFilter = anchor.getScene();
        ownerSceneWithFilter.addEventFilter(MouseEvent.MOUSE_PRESSED, ownerMousePressedFilter);
    }

    private void attachOwnerSceneListeners(Scene scene) {
        if (scene == null) {
            return;
        }
        if (ownerSceneWithResizeListener != scene) {
            if (ownerSceneWithResizeListener != null) {
                detachOwnerSceneListeners(ownerSceneWithResizeListener);
            }
            scene.widthProperty().addListener(ownerResizeListener);
            scene.heightProperty().addListener(ownerResizeListener);
            ownerSceneWithResizeListener = scene;
        }
        if (scene.getWindow() != null) {
            scene.getWindow().focusedProperty().removeListener(ownerFocusListener);
            scene.getWindow().focusedProperty().addListener(ownerFocusListener);
        }
    }

    private void detachOwnerSceneListeners(Scene scene) {
        if (scene == null) {
            return;
        }
        scene.widthProperty().removeListener(ownerResizeListener);
        scene.heightProperty().removeListener(ownerResizeListener);
        if (scene.getWindow() != null) {
            scene.getWindow().focusedProperty().removeListener(ownerFocusListener);
        }
        if (ownerSceneWithResizeListener == scene) {
            ownerSceneWithResizeListener = null;
        }
    }

    private void handleOwnerFocusChanged(boolean focused) {
        if (focused) {
            return;
        }
        mouseInside = false;
        hide();
    }

    private void removeOwnerOutsideClickFilter() {
        if (ownerSceneWithFilter != null && ownerMousePressedFilter != null) {
            ownerSceneWithFilter.removeEventFilter(MouseEvent.MOUSE_PRESSED, ownerMousePressedFilter);
        }
        ownerSceneWithFilter = null;
        ownerMousePressedFilter = null;
    }

    private void syncStylesheets(Node popupContent) {
        if (!(popupContent instanceof Parent popupParent)) {
            return;
        }
        if (anchor != null && anchor.getScene() != null) {
            popupParent.getStylesheets().setAll(anchor.getScene().getStylesheets());
        }
    }

    private void relocate() {
        if (popup == null || !popup.isShowing()) {
            return;
        }

        Node popupContent = popup.getContent().getFirst();
        popupContent.applyCss();
        popupContent.autosize();

        Bounds popupBounds = popupContent.getLayoutBounds();
        Bounds iconBounds = getAnchorVisualBounds();
        if (popupBounds == null || iconBounds == null) {
            return;
        }

        double popupWidth = popupBounds.getWidth();
        double popupHeight = popupBounds.getHeight();
        double anchorCenterX = iconBounds.getMinX() + iconBounds.getWidth() / 2 + WARNING_GRAPHIC_TIP_OFFSET_X;
        double x = clampToWindow(anchorCenterX - popupWidth / 2 + POPUP_OFFSET_X, popupWidth);
        double y = iconBounds.getMinY() - popupHeight - gap;
        popup.setX(x);
        popup.setY(y);
        alignArrowToAnchor(anchorCenterX, x, popupWidth);
    }

    private double clampToWindow(double x, double popupWidth) {
        if (anchor.getScene() == null || anchor.getScene().getWindow() == null) {
            return x;
        }

        double minX = anchor.getScene().getWindow().getX() + WINDOW_EDGE_GAP;
        double maxX = anchor.getScene().getWindow().getX()
                + anchor.getScene().getWindow().getWidth()
                - popupWidth
                - WINDOW_EDGE_GAP;
        if (maxX < minX) {
            return minX;
        }
        return Math.clamp(x, minX, maxX);
    }

    private void alignArrowToAnchor(double anchorCenterX, double popupX, double popupWidth) {
        if (popupArrow == null) {
            return;
        }

        double desiredTranslate = anchorCenterX - (popupX + popupWidth / 2);
        double maxTranslate = Math.max(0, popupWidth / 2 - ARROW_EDGE_GAP);
        popupArrow.setTranslateX(Math.clamp(desiredTranslate, -maxTranslate, maxTranslate) + 1);
    }

    private Bounds getAnchorVisualBounds() {
        anchor.applyCss();
        Node graphicNode = anchor.lookup(".status-warning-anchor-icon");
        if (graphicNode != null) {
            Bounds graphicBounds = graphicNode.localToScreen(graphicNode.getBoundsInLocal());
            if (graphicBounds != null) {
                return graphicBounds;
            }
        }

        Node textNode = anchor.lookup(".text");
        if (textNode != null) {
            Bounds textBounds = textNode.localToScreen(textNode.getBoundsInLocal());
            if (textBounds != null) {
                return textBounds;
            }
        }
        return anchor.localToScreen(anchor.getBoundsInLocal());
    }

    private boolean containsScreenPoint(Node node, double screenX, double screenY) {
        if (node == null) {
            return false;
        }
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        return bounds != null && bounds.contains(screenX, screenY);
    }

    private void fadeTo(double targetOpacity, Duration duration, Runnable onFinished) {
        Node popupContent = getPopupContent();
        if (popupContent == null) {
            if (onFinished != null) {
                onFinished.run();
            }
            return;
        }

        stopFade();
        boolean cached = popupContent.isCache();
        CacheHint cacheHint = popupContent.getCacheHint();
        popupContent.setCache(true);
        popupContent.setCacheHint(CacheHint.SPEED);

        fadeTransition = new FadeTransition(duration, popupContent);
        fadeTransition.setFromValue(popupContent.getOpacity());
        fadeTransition.setToValue(targetOpacity);
        fadeTransition.setInterpolator(targetOpacity > popupContent.getOpacity()
                ? Interpolator.EASE_OUT
                : Interpolator.EASE_IN);
        fadeTransition.setOnFinished(event -> {
            fadeTransition = null;
            popupContent.setOpacity(targetOpacity);
            popupContent.setCache(cached);
            popupContent.setCacheHint(cacheHint);
            if (onFinished != null) {
                onFinished.run();
            }
        });
        fadeTransition.play();
    }

    private void stopFade() {
        if (fadeTransition != null) {
            fadeTransition.stop();
            fadeTransition = null;
        }
    }

    private void schedulePreviewHide() {
        stopPreviewHideDelay();
        if (visibleDuration == null || visibleDuration.lessThanOrEqualTo(Duration.ZERO)) {
            hide();
            return;
        }

        previewHideDelay = new PauseTransition(visibleDuration);
        previewHideDelay.setOnFinished(event -> {
            previewHideDelay = null;
            if (!pinned && !mouseInside) {
                hide();
            }
        });
        previewHideDelay.play();
    }

    private void stopPreviewHideDelay() {
        if (previewHideDelay != null) {
            previewHideDelay.stop();
            previewHideDelay = null;
        }
    }

    private Node getPopupContent() {
        if (popup == null || popup.getContent().isEmpty()) {
            return null;
        }
        return popup.getContent().getFirst();
    }

    private void updatePopupWarnings() {
        if (popupTextList == null) {
            return;
        }

        popupTextList.getChildren().clear();
        for (int i = 0; i < currentWarnings.size(); i++) {
            if (i > 0) {
                Separator separator = new Separator();
                separator.getStyleClass().add("status-warning-popup-separator");
                popupTextList.getChildren().add(separator);
            }

            Label warningLabel = new Label(currentWarnings.get(i));
            warningLabel.setWrapText(true);
            warningLabel.getStyleClass().add("status-warning-popup-text");
            popupTextList.getChildren().add(warningLabel);
        }
    }

    private String normalizeText(java.util.List<String> warnings) {
        return String.join(System.lineSeparator(), warnings);
    }

    private java.util.List<String> normalizeWarnings(String text) {
        if (text == null || text.isBlank()) {
            return java.util.List.of();
        }

        String[] parts = text.split("\\|");
        java.util.List<String> warnings = new java.util.ArrayList<>();
        for (String part : parts) {
            String warning = part.replaceFirst("^\\s*\\u26A0\\s*", "").trim();
            if (warning.isBlank()) {
                continue;
            }
            warnings.add(warning);
        }
        return warnings;
    }
}
