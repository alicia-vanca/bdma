package com.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.app.admin.layout.controllers.AdminLayoutController;
import com.app.admin.settingsdialog.services.AdminSettingsDialogService;
import com.app.common.helpers.AlertHelper;
import com.app.common.helpers.CssLoader;
import com.app.common.helpers.NavigationHelper;
import com.app.common.helpers.SpringContextHolder;
import com.app.common.helpers.ViewLoader;
import com.app.common.modules.appupdate.controllers.AppUpdateController;
import com.app.common.modules.crypto.SyncedEncryptedFileTransitionStartup;
import com.app.common.modules.databaserecovery.services.DatabaseRecoveryService;
import com.app.common.modules.datarestore.services.RestoreService;
import com.app.common.modules.datasync.DataSyncRunner;
import com.app.common.modules.externalmediadecrypt.services.ExternalMediaDecryptService;
import com.app.common.modules.queuemanager.services.QueueManagerService;
import com.app.common.modules.session.Session;
import com.app.guest.services.AppStartupService;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.app.common.configs.AppContext;
import com.app.common.configs.AppRuntimeInitializer;

import ch.qos.logback.classic.LoggerContext;
import com.app.common.configs.LogbackConfigInitializer;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.ViewPaths;
import com.app.common.exceptions.AppException;
import com.app.common.exceptions.GlobalExceptionHandler;
import com.app.common.modules.foldermanager.services.FolderManagerService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.loggly.LogglyQueuedAppender;
import com.app.common.modules.theme.ThemeManager;
import com.app.common.utils.StageUtil;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import lombok.Getter;


public class MainApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);
    private static final long APPLICATION_START_NANOS = System.nanoTime();
    private static final Object SINGLE_INSTANCE_MONITOR = new Object();

    // S1450: assigned in acquireSingleInstanceLock() at runtime, cannot be final
    @SuppressWarnings("java:S1450")
    private static ServerSocket instanceSocket;
    private static boolean singleInstanceChecked;
    private static boolean singleInstanceOwner;

    private ConfigurableApplicationContext springContext;
    private volatile FolderManagerService folderManagerService;
    private volatile RestoreService restoreService;
    private volatile QueueManagerService queueManagerService;
    private volatile ExternalMediaDecryptService stateService;
    private volatile CompletableFuture<Void> databaseMigration = CompletableFuture.completedFuture(null);
    private volatile boolean databaseMigrationFailed;
    private volatile Thread postUiInitializer;
    private volatile AppStartupService appStartupService;
    private volatile SyncedEncryptedFileTransitionStartup encryptedTransitionStartup;
    private RuntimeException startupFailure;
    private volatile boolean stopping;

    @Getter
    private static Stage primaryStage;
    @Getter
    private static Scene scene;
    private boolean forceClosing;

    private static void setPrimaryStage(Stage stage) {
        primaryStage = stage;
    }


    private static void setScene(Scene s) {
        scene = s;
    }

    public static void main(String[] args) {
        LogbackConfigInitializer.initialize();
        log.info("========== APPLICATION START REQUESTED (Version {}, Java {}, PID {}) ==========",
                AppContext.getVersion(), System.getProperty("java.version"), ProcessHandle.current().pid());

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

        prepareDatabaseBeforeSpringStartup();
        AppRuntimeInitializer.initialize();

        if (System.getProperty("debug") == null && !"true".equalsIgnoreCase(System.getenv("DEBUG"))) {
            System.setProperty("debug", "false");
        }
        SpringApplication application = new SpringApplication(SpringBootApp.class);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(loadBundledApplicationProperties());
        try {
            springContext = application.run();
        } catch (RuntimeException e) {
            startupFailure = e;
            log.error("Application startup failed", e);
            return;
        }
        SpringContextHolder.setContext(springContext);
        LogglyQueuedAppender.setSpringReady(true);

        GlobalExceptionHandler handler = springContext.getBean(GlobalExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(handler);

        databaseMigration = CompletableFuture.runAsync(() -> {
            try {
                springContext.getBean(Flyway.class).migrate();
            } catch (RuntimeException failure) {
                databaseMigrationFailed = true;
                throw failure;
            }
        }, task -> Thread.ofPlatform().daemon().name("database-migration").start(task));

    }

    /**
     * Restores the SQLite database before runtime initialization, Spring
     * DataSource,
     * and Flyway can create or validate an empty source database file.
     */
    private void prepareDatabaseBeforeSpringStartup() {
        try {
            new DatabaseRecoveryService().prepareDatabase();
        } catch (Exception e) {
            log.error("Failed to prepare database before startup", e);
        }
    }

    @Override
    public void start(Stage stage) {
        if (!singleInstanceOwner) {
            log.info("Skipping startup for duplicate launch.");
            Platform.exit();
            return;
        }

        setPrimaryStage(stage);
        configureCloseHandler();
        StageUtil.applyAppIcon(primaryStage);
        if (!awaitDatabaseMigration()) {
            return;
        }
        I18n.loadSavedLocale();
        ThemeManager.loadSavedTheme();

        setScene(new Scene(new StackPane()));
        getPrimaryStage().setScene(getScene());

        CssLoader.applyBase(getScene());
        ThemeManager.apply(getScene());

        if (startupFailure != null) {
            handleStartupFailure(startupFailure);
            return;
        }

        showAdmin();
        scheduleAfterFirstLayout();
    }

    private void scheduleAfterFirstLayout() {
        Scene currentScene = getScene();
        currentScene.addPostLayoutPulseListener(new Runnable() {
            @Override
            public void run() {
                currentScene.removePostLayoutPulseListener(this);
                Platform.runLater(MainApp.this::afterUiShown);
            }
        });
        Platform.requestNextPulse();
    }

    private void afterUiShown() {
        if (stopping || !primaryStage.isShowing()) {
            return;
        }

        long startupDurationMillis = (System.nanoTime() - APPLICATION_START_NANOS) / 1_000_000;
        log.info("========== APPLICATION INITIALIZATION FINISHED - UI SHOWN (startup time: {} ms) ==========",
                startupDurationMillis);


        runPostUiStep("update check", () -> {
            if (springContext.getBean(Session.class).isGuest()) {
                springContext.getBean(AppUpdateController.class).checkOnStartup();
            }
        });

        Thread initializer = Thread.ofPlatform()
                .daemon()
                .name("post-ui-initializer")
                .unstarted(() -> {
                    try {
                        initializeAfterUi();
                    } finally {
                        postUiInitializer = null;
                    }
                });
        postUiInitializer = initializer;
        initializer.start();
    }

    private boolean awaitDatabaseMigration() {
        try {
            databaseMigration.join();
            return true;
        } catch (CompletionException e) {
            databaseMigrationFailed = true;
            RuntimeException failure = e.getCause() instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new RuntimeException("Deferred database migration failed", e.getCause());
            startupFailure = failure;
            log.error("Deferred database migration failed", failure);
            Platform.runLater(() -> handleStartupFailure(failure));
            return false;
        }
    }

    private void initializeAfterUi() {
        if (postUiInitializationCancelled()) {
            return;
        }

        runPostUiStep("synced encrypted transition", () -> {
            SyncedEncryptedFileTransitionStartup transitionStartup = springContext
                    .getBean(SyncedEncryptedFileTransitionStartup.class);
            encryptedTransitionStartup = transitionStartup;
            transitionStartup.startAfterUi();
        });
        if (postUiInitializationCancelled()) {
            return;
        }

        try {
            AppStartupService startupService = springContext.getBean(AppStartupService.class);
            appStartupService = startupService;
            folderManagerService = springContext.getBean(FolderManagerService.class);
            restoreService = springContext.getBean(RestoreService.class);
            queueManagerService = springContext.getBean(QueueManagerService.class);
            stateService = springContext.getBean(ExternalMediaDecryptService.class);
            startupService.initialize();
        } catch (RuntimeException failure) {
            if (!postUiInitializationCancelled()) {
                failPostUiInitialization(failure);
            }
            return;
        }

        if (postUiInitializationCancelled()) {
            return;
        }
        runPostUiStep("storage status refresh", () -> {
            if (!springContext.getBean(Session.class).isDev()) {
                springContext.getBean(AdminLayoutController.class).refreshStorageStatus();
            }
        });

        if (postUiInitializationCancelled()) {
            return;
        }
        try {
            springContext.getBean(DataSyncRunner.class).startDeviceTracker();
        } catch (RuntimeException failure) {
            if (!postUiInitializationCancelled()) {
                failPostUiInitialization(failure);
            }
        }
    }

    private boolean postUiInitializationCancelled() {
        ConfigurableApplicationContext context = springContext;
        return stopping || Thread.currentThread().isInterrupted() || context == null || !context.isActive();
    }

    private void failPostUiInitialization(RuntimeException failure) {
        startupFailure = failure;
        log.error("Post-UI application initialization failed", failure);
        Platform.runLater(() -> {
            if (!stopping) {
                handleStartupFailure(failure);
            }
        });
    }

    private void runPostUiStep(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Post-UI initialization step failed: {}", step, e);
        }
    }

    /**
     * Shows startup-specific guidance before closing when Spring cannot start.
     * Flyway validation failures mean existing local data no longer matches the
     * bundled database migrations.
     */
    private void handleStartupFailure(RuntimeException failure) {
        if (isFlywayValidationFailure(failure)) {
            showFatalStartupMessage(
                    "Database Not Compatible",
                    "The existing database is not compatible with this version of BDMA.",
                    "Please contact an administrator for assistance.\n"
                            + "Do not delete app data unless you are sure the existing local data is no longer needed.");
        } else {
            showFatalStartupMessage(
                    "Startup Failed",
                    "BDMA could not start.",
                    "Please contact admin.");
        }
        Platform.exit();
    }

    private boolean isFlywayValidationFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof FlywayValidateException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void showFatalStartupMessage(String title, String header, String content) {
        AlertHelper.createError(title, header, content).showAndWait();
    }

    @Override
    public void stop() {
        stopping = true;
        log.info("========== APPLICATION SHUTDOWN REQUESTED ==========");
        releaseSingleInstanceLock();
        boolean startupTasksStopped = stopStartupTasks();

        if (startupTasksStopped && springContext != null) {
            springContext.close();
        }

        if (startupTasksStopped && folderManagerService != null)
            folderManagerService.shutdown();

        if (!startupTasksStopped) {
            log.error("Skipping Spring shutdown and database backup because startup tasks did not stop");
        } else if (databaseMigrationFailed) {
            log.warn("Skipping database backup because database migration failed");
        } else {
            try {
                new DatabaseRecoveryService().backupSourceToBackup();
            } catch (Exception e) {
                log.error("Failed to back up database before shutdown", e);
            }
        }

        log.info("App stopped");
        shutdownLoggingSystem();

        // Stop all non-daemon threads that may be keeping the JVM running in
        // background after the JavaFX application has been stopped.
        System.exit(0);
    }

    private boolean stopStartupTasks() {
        boolean stopped = stopPostUiInitializer();
        SyncedEncryptedFileTransitionStartup transitionStartup = encryptedTransitionStartup;
        if (transitionStartup != null) {
            stopped = transitionStartup.stopAndWait() && stopped;
        }
        AppStartupService startupService = appStartupService;
        if (startupService != null) {
            stopped = startupService.stopDeviceListLoader() && stopped;
        }
        ConfigurableApplicationContext context = springContext;
        if (context != null && context.isActive()
                && context.getBeanFactory().containsSingleton("adminSettingsDialogService")) {
            AdminSettingsDialogService settingsService = context.getBeanFactory()
                    .getBean("adminSettingsDialogService", AdminSettingsDialogService.class);
            stopped = settingsService.stopResetTask() && stopped;
        }
        return stopped;
    }

    private boolean stopPostUiInitializer() {
        Thread initializer = postUiInitializer;
        if (initializer == null) {
            return true;
        }

        initializer.interrupt();
        try {
            // ponytail: 2 s shutdown ceiling; move startup work into cancellable tasks if steps can block longer.
            initializer.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean stopped = !initializer.isAlive();
        if (!stopped) {
            log.error("Post-UI initializer did not stop within 2000 ms");
        }
        return stopped;
    }

    /**
     * Stops Logback before the packaged JVM exits so async appenders can flush
     * queued events and Loggly can run its final drain deterministically.
     */
    private static void shutdownLoggingSystem() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.stop();
    }

    public static void showLogin() {
        primaryStage.setMaximized(false);
        primaryStage.setResizable(false);
        primaryStage.setMinWidth(0);
        primaryStage.setMinHeight(0);
        loadAndNavigate(ViewPaths.LOGIN, "BDMA", 480, 420);
    }

    public static void showAdmin() {
        primaryStage.setResizable(true);
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(500);
        loadAndNavigate(ViewPaths.ADMIN_LAYOUT, "BDMA", 1201, 800);
        Platform.runLater(() -> primaryStage.setMaximized(true));
    }

    private static void loadAndNavigate(String fxml, String title, int w, int h) {
        try {
            ViewLoader viewLoader = SpringContextHolder.getBean(ViewLoader.class);
            var result = viewLoader.loadViewOrThrow(fxml);

            Parent root = (Parent) result.node();

            if (ViewPaths.LOGIN.equals(fxml) || ViewPaths.TOTP.equals(fxml)) {
                NavigationHelper.goToLogin(root);
            } else {
                NavigationHelper.goToAdmin(root);
            }

            primaryStage.setTitle(title);

            if (!primaryStage.isMaximized()) {
                primaryStage.setWidth(w);
                primaryStage.setHeight(h);
                primaryStage.centerOnScreen();
            }

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
            ServerSocket listenerSocket = new ServerSocket(AppConstants.SINGLE_INSTANCE_PORT, 1,
                    InetAddress.getByName("127.0.0.1"));
            instanceSocket = listenerSocket;

            Thread listenerThread = new Thread(() -> {
                while (!listenerSocket.isClosed()) {
                    try {
                        Socket incoming = listenerSocket.accept();
                        incoming.close();
                        Platform.runLater(MainApp::bringToFront);
                    } catch (IOException e) {
                        if (!listenerSocket.isClosed()) {
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

    private void configureCloseHandler() {
        primaryStage.setOnCloseRequest(event -> {
            if (forceClosing) {
                return;
            }
            if (springContext == null) {
                return;
            }
            QueueManagerService currentQueueManager = resolveInitializedBean(
                    queueManagerService, QueueManagerService.class);
            ExternalMediaDecryptService currentStateService = resolveInitializedBean(
                    stateService, ExternalMediaDecryptService.class);
            RestoreService currentRestoreService = resolveInitializedBean(
                    restoreService, RestoreService.class);
            boolean syncRunning = currentQueueManager != null && currentQueueManager.hasRunningSyncTask();
            boolean exportRunning = currentQueueManager != null && currentQueueManager.hasRunningExportTask();
            boolean decryptRunning = currentStateService != null && currentStateService.isRunning();
            boolean restoreRunning = currentRestoreService != null && currentRestoreService.isRunning();


            if (!syncRunning && !exportRunning && !restoreRunning && !decryptRunning) {
                return;
            }

            // Keep the window open until the user confirms closing active work.
            event.consume();
            if (syncRunning) {
                checkProcess(
                        "app.close.syncRunning.header",
                        "app.close.syncRunning.message");
                return;
            }

            if (exportRunning) {
                checkProcess(
                        "app.close.exportRunning.header",
                        "app.close.exportRunning.message");
                return;
            }
            if (decryptRunning) {
                checkProcess(
                        "app.close.decryptRunning.header",
                        "app.close.decryptRunning.message");
                return;
            }
            checkProcess(
                    "app.close.restoreRunning.header",
                    "app.close.restoreRunning.message");
        });
    }

    private <T> T resolveInitializedBean(T currentBean, Class<T> beanType) {
        if (currentBean != null) {
            return currentBean;
        }

        ConfigurableApplicationContext context = springContext;
        if (context == null || !context.isActive()) {
            return null;
        }

        try {
            for (String beanName : context.getBeanFactory().getBeanNamesForType(beanType, false, false)) {
                if (context.getBeanFactory().containsSingleton(beanName)) {
                    return context.getBean(beanName, beanType);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to resolve initialized bean while closing: {}", beanType.getSimpleName(), e);
        }
        return null;
    }

    private void continueCloseRequest() {
        forceClosing = true;
        primaryStage.close();
    }

    /**
     * Shows a confirmation dialog before closing while queue work is still running.
     *
     * @param header  resource key for the dialog header
     * @param message resource key for the dialog message
     */
    public void checkProcess(String header, String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> checkProcess(header, message));
            return;
        }

        showCheckProcess(header, message);
    }

    private void showCheckProcess(String header, String message) {
        Alert alert = AlertHelper.createConfirmation(
                I18n.get("app.close.processRunning.title"),
                I18n.get(header),
                I18n.get(message));

        ButtonType okButton = new ButtonType(I18n.get("app.close.ok"), ButtonBar.ButtonData.OK_DONE);

        ButtonType cancelButton = new ButtonType(I18n.get("app.close.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

        AlertHelper.setButtons(alert, okButton, cancelButton);

        alert.setOnShown(event -> {
            var node = alert.getDialogPane().lookup(".button-bar");
            if (node instanceof ButtonBar buttonBar) {
                buttonBar.setButtonOrder(ButtonBar.BUTTON_ORDER_NONE);
            }
        });

        alert.showAndWait().ifPresent(result -> {
            if (result == okButton) {
                continueCloseRequest();
            }
        });
    }

}
