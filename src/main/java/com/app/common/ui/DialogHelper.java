package com.app.common.ui;

import com.app.MainApp;
import com.app.common.css.CssLoader;
import com.app.common.helper.SpringContextHolder;
import com.app.common.theme.ThemeManager;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class DialogHelper {

    public record DialogResult<T>(Stage stage, T controller) {
    }

    public static Stage open(String fxml, String title) {
        return openWithController(fxml, title).stage();
    }

    public static <T> DialogResult<T> openWithController(String fxml, String title) {
        ViewLoader loader = SpringContextHolder.getBean(ViewLoader.class);
        ViewLoader.LoadResult<T> result = loader.loadOrThrowWithController(fxml, null);

        Parent root = (Parent) result.node();
        Scene scene = new Scene(root);

        CssLoader.applyDialog(scene, fxml);

        Stage stage = new Stage();
        stage.setTitle(title);
        stage.setScene(scene);
        stage.initOwner(MainApp.getPrimaryStage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setOnShowing(e -> ThemeManager.apply(scene));

        return new DialogResult<>(stage, result.controller());
    }
}