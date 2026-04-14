package com.app.common.services;

import com.app.common.helpers.NoticeStackRenderer;
import javafx.application.Platform;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

/**
 * Routes feature-level notifications to the active shell notice container.
 */
@Component
public class AppNoticeService {

    private NoticeStackRenderer noticeRenderer;

    public void bindNoticeContainer(VBox container) {
        if (container == null) {
            noticeRenderer = null;
            return;
        }
        noticeRenderer = new NoticeStackRenderer(container);
    }

    public void showSuccess(String text) {
        show(text, true);
    }

    public void showError(String text) {
        show(text, false);
    }

    private void show(String text, boolean success) {
        if (noticeRenderer == null || text == null || text.isBlank()) {
            return;
        }

        Runnable action = () -> {
            if (success) {
                noticeRenderer.showSuccess(text);
            } else {
                noticeRenderer.showError(text);
            }
        };

        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }

        Platform.runLater(action);
    }
}
