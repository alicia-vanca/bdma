package com.app;

import com.app.common.config.AppRuntimeInitializer;
import com.app.common.config.LogContext;
import com.app.common.config.LogbackConfigInitializer;
import com.app.common.css.CssLoader;
import com.app.common.exception.AppException;
import com.app.common.exception.GlobalExceptionHandler;
import com.app.common.helper.SpringContextHolder;
import com.app.common.i18n.I18n;
import com.app.common.theme.ThemeManager;
import com.app.common.ui.NavigationService;
import com.app.common.ui.StageUtils;
import com.app.common.ui.ViewLoader;
import com.app.common.ui.ViewPaths;
import com.app.file.service.DataFolderManager;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public class MainApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);

    private ConfigurableApplicationContext springContext;
    private DataFolderManager dataFolderManager;

    @Getter
    private static Stage primaryStage;
    @Getter
    private static Scene scene;

    private static void setPrimaryStage(Stage stage) {
        primaryStage = stage;
    }

    private static void setScene(Scene s) {
        scene = s;
    }

    public static void main(String[] args) {
        LogbackConfigInitializer.initialize();
        launch(args);
    }

    @Override
    public void init() {
        AppRuntimeInitializer.initialize();
        springContext = SpringApplication.run(SpringBootApp.class);

        GlobalExceptionHandler handler = springContext.getBean(GlobalExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(handler);

        dataFolderManager = springContext.getBean(DataFolderManager.class);
        dataFolderManager.init();
    }

    @Override
    public void start(Stage stage) {
        setPrimaryStage(stage);
        StageUtils.applyAppIcon(primaryStage);
        LogContext.init();
        I18n.loadSavedLocale();

        setScene(new Scene(new StackPane()));
        getPrimaryStage().setScene(getScene());

        CssLoader.applyBase(getScene());
        ThemeManager.apply(getScene());

        log.info("App started");
        showLogin();
    }

    @Override
    public void stop() {
        if (dataFolderManager != null)
            dataFolderManager.shutdown();
        if (springContext != null)
            springContext.close();
        log.info("App stopped");
    }

    public static void showLogin() {
        loadAndNavigate(ViewPaths.LOGIN, "BDMA", 400, 300);
    }

    public static void showAdmin() {
        loadAndNavigate(ViewPaths.ADMIN_LAYOUT, "BDMA", 1200, 800);
    }

    private static void loadAndNavigate(String fxml, String title, int w, int h) {
        try {
            ViewLoader viewLoader = SpringContextHolder.getBean(ViewLoader.class);
            var result = viewLoader.loadOrThrowWithController(fxml, null);

            Parent root = (Parent) result.node();

            if (ViewPaths.LOGIN.equals(fxml)) {
                NavigationService.goToLogin(root);
            } else {
                NavigationService.goToAdmin(root);
            }

            primaryStage.setTitle(title);
            primaryStage.setWidth(w);
            primaryStage.setHeight(h);
            primaryStage.centerOnScreen();

            if (primaryStage.getScene() == null) {
                primaryStage.setScene(scene);
            }
            primaryStage.show();

        } catch (AppException e) {
            log.warn("App error loading scene: {}", fxml, e);
        } catch (RuntimeException e) {
            log.error("Failed to load scene: {}", fxml, e);
        }
    }
}