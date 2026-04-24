package com.app.common.helpers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.MainApp;
import com.app.common.events.ThemeChangedEvent;
import com.app.common.modules.theme.ThemeManager;
import com.app.common.utils.StageUtil;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

@Component
public class DialogHelper {

    private static final Logger log = LoggerFactory.getLogger(DialogHelper.class);

    public record Dialog<T>(Stage stage, T controller) {
    }

    // Track all open dialog scenes and their controllers for theme updates
    private static final List<Scene> openDialogScenes = new CopyOnWriteArrayList<>();
    private static final List<Object> openDialogControllers = new CopyOnWriteArrayList<>();

    public static Stage createDialogStage(String fxml, String title) {
        return createDialog(fxml, title).stage();
    }

    public static <T> Dialog<T> createDialog(String fxml, String title) {
        ViewLoader loader = SpringContextHolder.getBean(ViewLoader.class);
        ViewLoader.LoadResult<T> loadedView = loader.loadViewOrThrow(fxml);

        Parent root = (Parent) loadedView.node();
        Scene scene = new Scene(root);

        CssLoader.applyDialog(scene, fxml);

        Stage stage = new Stage();
        StageUtil.applyAppIcon(stage);
        stage.setTitle(title);
        stage.setScene(scene);
        stage.initOwner(MainApp.getPrimaryStage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setOnShowing(e -> ThemeManager.apply(scene));

        // Register this dialog's scene and controller for theme updates
        openDialogScenes.add(scene);
        openDialogControllers.add(loadedView.controller());
        log.info("Registered dialog scene for theme updates: {} (total: {})", fxml, openDialogScenes.size());

        // Remove scene and controller when dialog closes
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> {
            int index = openDialogScenes.indexOf(scene);
            if (index >= 0) {
                openDialogScenes.remove(index);
                openDialogControllers.remove(index);
            }
            log.info("Removed dialog scene: {} (remaining: {})", fxml, openDialogScenes.size());
        });

        // Provide consistent keyboard dismissal for all modal popups opened by
        // DialogHelper.
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stage.close();
                event.consume();
            }
        });

        return new Dialog<>(stage, loadedView.controller());
    }

    /**
     * Listens for theme change events and updates all open dialogs.
     * Updates CSS for dialog scenes and calls onThemeChanged() on controllers that
     * implement it.
     * This solves the prototype bean issue where Spring events would notify all
     * instances.
     *
     * @param event the theme change event containing the new theme
     */
    @EventListener
    public void onThemeChanged(ThemeChangedEvent event) {
        // Copy lists to avoid ConcurrentModificationException if a dialog closes during
        // iteration
        List<Scene> scenes = new ArrayList<>(openDialogScenes);
        List<Object> controllers = new ArrayList<>(openDialogControllers);

        // Update CSS for all dialog scenes
        scenes.forEach(ThemeManager::apply);

        // Call onThemeChanged on controllers that have the method
        for (Object controller : controllers) {
            try {
                var method = controller.getClass().getMethod("onThemeChanged", ThemeChangedEvent.class);
                method.invoke(controller, event);
            } catch (NoSuchMethodException e) {
                // Controller doesn't have onThemeChanged method, skip
            } catch (Exception e) {
                log.warn("Failed to call onThemeChanged on {}", controller.getClass().getSimpleName(), e);
            }
        }
    }
}