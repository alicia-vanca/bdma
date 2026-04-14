package com.app.common.helpers;

import com.app.MainApp;
import com.app.common.modules.theme.ThemeManager;
import com.app.common.utils.StageUtil;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class DialogHelper {

    public record Dialog<T>(Stage stage, T controller) {
    }

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
}