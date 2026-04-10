package com.app.common.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Manages stacked, auto-expiring notices inside a container so multiple
 * messages can coexist without overwriting each other.
 */
public class NoticeStackHelper {

    public static final String STYLE_SUCCESS = "message-success";
    public static final String STYLE_ERROR = "message-error";

    private final VBox container;
    private final int maxVisible;
    private final Duration ttl;
    private final Duration fadeDuration;

    public NoticeStackHelper(VBox container) {
        this(container, 4, Duration.seconds(3), Duration.millis(220));
    }

    public NoticeStackHelper(VBox container, int maxVisible, Duration ttl, Duration fadeDuration) {
        this.container = container;
        this.maxVisible = maxVisible;
        this.ttl = ttl;
        this.fadeDuration = fadeDuration;
    }

    public void showSuccess(String text) {
        show(text, STYLE_SUCCESS);
    }

    public void showError(String text) {
        show(text, STYLE_ERROR);
    }

    public void show(String text, String styleClass) {
        Label notice = new Label(text);
        notice.getStyleClass().addAll("global-notice", styleClass);
        notice.setMouseTransparent(true);

        // Shift existing notices up before sliding in the new one.
        if (container.getChildren().isEmpty()) {
            addAndStartLifecycle(notice);
            return;
        }

        double shift = estimateNoticeShift(notice);
        ParallelTransition slideUpAll = new ParallelTransition();
        for (Node child : container.getChildren()) {
            child.setTranslateY(child.getTranslateY() + shift);
            TranslateTransition slide = new TranslateTransition(Duration.millis(180), child);
            slide.setToY(0);
            slideUpAll.getChildren().add(slide);
        }

        container.getChildren().add(notice);
        notice.setTranslateY(shift);
        container.toFront();

        TranslateTransition slideIn = new TranslateTransition(Duration.millis(180), notice);
        slideIn.setToY(0);
        slideUpAll.getChildren().add(slideIn);

        slideUpAll.setOnFinished(e -> {
            while (container.getChildren().size() > maxVisible) {
                container.getChildren().removeFirst();
            }
            startLifecycle(notice);
        });
        slideUpAll.play();
    }

    private void addAndStartLifecycle(Label notice) {
        container.getChildren().add(notice);
        container.toFront();

        while (container.getChildren().size() > maxVisible) {
            container.getChildren().removeFirst();
        }

        startLifecycle(notice);
    }

    private void startLifecycle(Label notice) {

        PauseTransition life = new PauseTransition(ttl);
        life.setOnFinished(e -> {
            FadeTransition fade = new FadeTransition(fadeDuration, notice);
            fade.setFromValue(1.0);
            fade.setToValue(0.0);
            fade.setOnFinished(done -> container.getChildren().remove(notice));
            fade.play();
        });
        life.play();
    }

    private double estimateNoticeShift(Label notice) {
        // Force CSS so preferred height is available before node is attached.
        notice.applyCss();
        double height = notice.prefHeight(-1);
        if (height <= 0) {
            height = 36;
        }
        return height + container.getSpacing();
    }
}
