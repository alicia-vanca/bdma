package com.app;

import com.app.common.configs.AppRuntimeInitializer;
import com.app.common.configs.LogbackConfigInitializer;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.ViewPaths;
import com.app.common.exceptions.AppException;
import com.app.common.exceptions.GlobalExceptionHandler;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.helpers.CssLoader;
import com.app.common.helpers.NavigationHelper;
import com.app.common.helpers.SpringContextHolder;
import com.app.common.helpers.ViewLoader;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.theme.ThemeManager;
import com.app.common.utils.StageUtil;

import com.app.common.modules.databackup.services.DataBackupService;
import com.app.common.modules.databackup.workers.DataBackupWorker;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;

public class MainApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);
    private static final Object SINGLE_INSTANCE_MONITOR = new Object();

    // S1450: assigned in acquireSingleInstanceLock() at runtime, cannot be final
    @SuppressWarnings("java:S1450")
    private static ServerSocket instanceSocket;
    private static boolean singleInstanceChecked;
    private static boolean singleInstanceOwner;

    private ConfigurableApplicationContext springContext;
    private FolderManagerService folderManagerService;

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

        if (!ensureSingleInstanceOrSignal()) {
            log.warn("App is already running. Bringing existing window to front.");
            System.exit(0);
        }
        launch(args);
    }

    @Override
    public void init() {
        if (!ensureSingleInstanceOrSignal()) {
            return;
        }

        AppRuntimeInitializer.initialize();
        SpringApplication application = new SpringApplication(SpringBootApp.class);
        application.setDefaultProperties(loadBundledApplicationProperties());
        springContext = application.run();

        GlobalExceptionHandler handler = springContext.getBean(GlobalExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(handler);

        folderManagerService = springContext.getBean(FolderManagerService.class);
        folderManagerService.init();
        DataBackupWorker backupWorker = springContext.getBean(DataBackupWorker.class);
        Thread backupThread = new Thread(backupWorker, "backup-worker");
        backupThread.setDaemon(true);
        backupThread.start();

        DataBackupService backupService = springContext.getBean(DataBackupService.class);
        backupService.init();
    }

    @Override
    public void start(Stage stage) {
        if (!singleInstanceOwner) {
            log.info("Skipping startup for duplicate launch.");
            Platform.exit();
            return;
        }

        setPrimaryStage(stage);
        StageUtil.applyAppIcon(primaryStage);
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
        releaseSingleInstanceLock();
        if (springContext != null)
            springContext.close();
        if (folderManagerService != null)
            folderManagerService.shutdown();
        log.info("App stopped");
    }

    public static void showLogin() {
        primaryStage.setResizable(false);
        loadAndNavigate(ViewPaths.LOGIN, "BDMA", 400, 350);
    }

    public static void showAdmin() {
        primaryStage.setResizable(true);
        loadAndNavigate(ViewPaths.ADMIN_LAYOUT, "BDMA", 1200, 800);
    }

    private static void loadAndNavigate(String fxml, String title, int w, int h) {
        try {
            ViewLoader viewLoader = SpringContextHolder.getBean(ViewLoader.class);
            var result = viewLoader.loadViewOrThrow(fxml);

            Parent root = (Parent) result.node();

            if (ViewPaths.LOGIN.equals(fxml)) {
                NavigationHelper.goToLogin(root);
            } else {
                NavigationHelper.goToAdmin(root);
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

    private static boolean acquireSingleInstanceLock() {
        try {
            instanceSocket = new ServerSocket(AppConstants.SINGLE_INSTANCE_PORT, 1,
                    InetAddress.getByName("127.0.0.1"));

            Thread listenerThread = new Thread(() -> {
                while (!instanceSocket.isClosed()) {
                    try {
                        Socket incoming = instanceSocket.accept();
                        incoming.close();
                        Platform.runLater(MainApp::bringToFront);
                    } catch (IOException e) {
                        if (!instanceSocket.isClosed()) {
                            log.warn("Single instance listener error", e);
                        }
                    }
                }
            }, "single-instance-listener");
            listenerThread.setDaemon(true);
            listenerThread.start();

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // Guard startup in both main() and the JavaFX lifecycle so packaged launches
    // still enforce the single-instance contract.
    private static boolean ensureSingleInstanceOrSignal() {
        synchronized (SINGLE_INSTANCE_MONITOR) {
            if (singleInstanceChecked) {
                return singleInstanceOwner;
            }

            singleInstanceChecked = true;
            singleInstanceOwner = acquireSingleInstanceLock();
            if (!singleInstanceOwner) {
                signalExistingInstance();
            }
            return singleInstanceOwner;
        }
    }

    private static void releaseSingleInstanceLock() {
        synchronized (SINGLE_INSTANCE_MONITOR) {
            if (instanceSocket != null) {
                try {
                    instanceSocket.close();
                } catch (IOException e) {
                    log.warn("Failed to release single instance lock", e);
                } finally {
                    instanceSocket = null;
                }
            }
            singleInstanceChecked = false;
            singleInstanceOwner = false;
        }
    }

    private static void signalExistingInstance() {
        try (Socket socket = new Socket("127.0.0.1", AppConstants.SINGLE_INSTANCE_PORT)) {
            log.info("Signal sent to existing instance.");
        } catch (IOException e) {
            log.warn("Could not signal existing instance", e);
        }
    }

    private Properties loadBundledApplicationProperties() {
        Properties properties = new Properties();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = MainApp.class.getClassLoader();
        }

        try (InputStream inputStream = classLoader.getResourceAsStream("application.properties")) {
            if (inputStream != null) {
                properties.load(inputStream);
            } else {
                log.warn("Bundled application.properties was not found on the classpath");
            }
        } catch (IOException e) {
            log.warn("Could not read bundled application.properties", e);
        }

        return properties;
    }

    private static void bringToFront() {
        if (primaryStage != null) {
            primaryStage.setIconified(false);
            primaryStage.toFront();
            primaryStage.requestFocus();
            log.info("Brought existing window to front.");
        }
    }
}