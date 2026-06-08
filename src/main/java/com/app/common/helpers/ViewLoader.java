package com.app.common.helpers;

import com.app.common.modules.baselayout.controllers.BaseLayoutController;
import com.app.common.modules.i18n.I18n;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ViewLoader {

    private static final Logger log = LoggerFactory.getLogger(ViewLoader.class);

    public Node load(String fxmlPath) {
        var result = loadView(fxmlPath);
        return result != null ? result.node() : null;
    }

    /**
     * Loads an FXML view and returns the view root with its own controller.
     *
     * Parent/owner controller wiring is intentionally not handled here.
     */
    public <T> LoadResult<T> loadView(String fxmlPath) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath), I18n.getBundle());
            loader.setControllerFactory(SpringContextHolder::getBean);

            Node node = loader.load();
            T controller = loader.getController();

            if (controller instanceof BaseLayoutController base) {
                base.setFxmlPath(fxmlPath);
            }

            return new LoadResult<>(node, controller);

        } catch (Exception e) {
            log.error("Failed to load view: {}", fxmlPath, e);
            return null;
        }
    }

    // Fail fast for flows that require a valid view so navigation errors are
    // surfaced immediately.
    public <T> LoadResult<T> loadViewOrThrow(String fxmlPath) {
        LoadResult<T> result = loadView(fxmlPath);
        if (result == null || result.node() == null) {
            throw new IllegalStateException("Unable to load view: " + fxmlPath);
        }
        return result;
    }

    public record LoadResult<T>(Node node, T controller) {
    }
}