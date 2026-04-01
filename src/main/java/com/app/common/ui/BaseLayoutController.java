package com.app.common.ui;

import com.app.MainApp;
import com.app.common.css.CssLoader;
import com.app.common.theme.ThemeManager;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import lombok.Setter;

import java.util.List;

public abstract class BaseLayoutController {

    private static final String ACTIVE_BUTTON = "active-button";

    private final ViewLoader viewLoader;

    @Setter
    private String fxmlPath;

    private String currentModuleFxml;

    protected BaseLayoutController(ViewLoader viewLoader) {
        this.viewLoader = viewLoader;
    }

    // ── Subclass ──────────────────────────────────────────────────

    protected abstract StackPane getContentArea();

    // ── Content ─────────────────────────────────────────────────────────────

    public void setContent(Node node) {
        if (node != null) {
            getContentArea().getChildren().setAll(node);
        }
    }

    // ── View loading ────────────────────────────────────────────────────────

    protected Node loadView(String fxml) {
        var result = viewLoader.loadWithController(fxml, this);
        if (result != null) {
            // KHÔNG ghi đè fxmlPath — đó là layout path, do ViewLoader set
            currentModuleFxml = fxml;
            CssLoader.applyModule(MainApp.getScene(), fxml);
            return result.node();
        }
        return null;
    }

    // Use explicit controller type to keep casting checked at runtime and avoid unchecked generic casts.
    protected <T> ViewLoader.LoadResult<T> loadViewWithController(String fxml, Class<T> controllerType) {
        ViewLoader.LoadResult<Object> result = viewLoader.loadWithController(fxml, this);
        if (result != null) {
            // KHÔNG ghi đè fxmlPath
            currentModuleFxml = fxml;
            CssLoader.applyModule(MainApp.getScene(), fxml);

            Object controller = result.controller();
            if (!controllerType.isInstance(controller)) {
                throw new IllegalStateException("Unexpected controller type for " + fxml);
            }

            return new ViewLoader.LoadResult<>(result.node(), controllerType.cast(controller));
        }
        return null;
    }

    // ── Menu ────────────────────────────────────────────────────────────────

    protected void setActiveButton(List<Button> buttons, Button active) {
        buttons.forEach(b -> b.getStyleClass().remove(ACTIVE_BUTTON));
        if (!active.getStyleClass().contains(ACTIVE_BUTTON)) {
            active.getStyleClass().add(ACTIVE_BUTTON);
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────

    protected void reloadUI() {
        if (fxmlPath == null) {
            throw new IllegalStateException("FXML path not set for controller");
        }

        // Snapshot trước khi reload
        String moduleToRestore = currentModuleFxml;

        // Reload layout shell
        var result = viewLoader.loadWithController(fxmlPath, null);
        if (result == null)
            return;

        Scene scene = MainApp.getScene();
        scene.setRoot((javafx.scene.Parent) result.node());
        CssLoader.applyAdmin(scene);
        ThemeManager.apply(scene);

        // initialize() của AdminLayoutController đã load dashboard mặc định.
        // Nếu user đang ở module khác thì restore lại đúng module.
        if (moduleToRestore != null
                && !moduleToRestore.equals(ViewPaths.ADMIN_DASHBOARD)
                && result.controller() instanceof BaseLayoutController layoutCtrl) {
            Node moduleNode = layoutCtrl.loadView(moduleToRestore);
            layoutCtrl.setContent(moduleNode);
        }
    }
}