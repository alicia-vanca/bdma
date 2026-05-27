package com.app.common.ui;

import com.app.common.helper.SpringContextHolder;
import com.app.common.i18n.I18n;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ViewLoader {

    private static final Logger log = LoggerFactory.getLogger(ViewLoader.class);

    public Node load(String fxml) {
        var result = loadWithController(fxml, null);
        return result != null ? result.node() : null;
    }

    public <T> LoadResult<T> loadWithController(String fxml, BaseLayoutController layoutController) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml), I18n.getBundle());
            loader.setControllerFactory(SpringContextHolder::getBean);

            Node node = loader.load();
            T controller = loader.getController();

            if (controller instanceof BaseLayoutController base) {
                base.setFxmlPath(fxml);
            }

            if (layoutController != null && controller instanceof LayoutAware la) {
                la.setLayoutController(layoutController);
            }

            return new LoadResult<>(node, controller);

        } catch (Exception e) {
            log.error("Failed to load view: {}", fxml, e);
            return null;
        }
    }

    // Fail fast for flows that require a valid view, so navigation errors are
    // surfaced immediately.
    public <T> LoadResult<T> loadOrThrowWithController(String fxml, BaseLayoutController layoutController) {
        LoadResult<T> result = loadWithController(fxml, layoutController);
        if (result == null || result.node() == null) {
            throw new IllegalStateException("Unable to load view: " + fxml);
        }
        return result;
    }

    public record LoadResult<T>(Node node, T controller) {
    }
}