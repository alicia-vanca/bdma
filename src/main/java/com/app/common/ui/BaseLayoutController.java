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

    // Use explicit controller type to keep casting checked at runtime and avoid
    // unchecked generic casts.
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

    // Layouts with menu tabs should override this and return all tab buttons used
    // by setActiveButton(). Default is an empty list for layouts without tab menus.
    protected List<Button> getMenuButtons() {
        return List.of();
    }

    // Override in subclasses to map a module FXML path to its nav button, so that
    // reloadUI() can restore the correct active-button highlight after a language
    // reload.
    protected abstract Button getButtonForModule(String fxml);

    // Shared notice hooks allow child modules to show shell-level messages without
    // depending on a concrete layout controller implementation.
    public void showNoticeSuccess(String text) {
        // default no-op for layouts that do not provide shell-level notice UI
    }

    public void showNoticeError(String text) {
        // default no-op for layouts that do not provide shell-level notice UI
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
            // Restore the active nav button to match the restored module; openDefaultTab()
            // already activated btnDashboard, so we must override it here.
            Button btn = layoutCtrl.getButtonForModule(moduleToRestore);
            if (btn != null) {
                layoutCtrl.setActiveButton(layoutCtrl.getMenuButtons(), btn);
            }
        }
    }
}