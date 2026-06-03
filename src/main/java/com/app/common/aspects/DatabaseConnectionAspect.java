package com.app.common.aspects;

import com.app.common.definitions.AppDataPaths;
import com.app.common.helpers.AlertHelper;
import com.app.common.modules.i18n.I18n;
import com.app.common.services.DatabaseManager;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Connection;
import java.sql.Statement;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Aspect
@Component
public class DatabaseConnectionAspect {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConnectionAspect.class);
    private static final AtomicBoolean isAlertShowing = new AtomicBoolean(false);
    private final DatabaseManager databaseManager;

    public DatabaseConnectionAspect(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public static final class DatabaseUnavailableException extends RuntimeException {
        public DatabaseUnavailableException(Throwable cause) {
            super("SQLite disconnect error. Restarting...", cause);
        }
    }


    // Scan all repositories in the directory. com.app.common.repositories
    @Around("execution(* com.app.common.repositories.*.*(..))")
    public Object recoverFromRepositoryDatabaseFailure(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed(); // Run the JdbcTemplate command.
        } catch (Throwable e) {
            Throwable rootCause = getRootCause(e);
            // Check for SQLiteException errors.
            if (!(rootCause instanceof org.sqlite.SQLiteException sqliteEx)) {
                throw e;
            }
            String errMsgSqlite = sqliteEx.getMessage().toLowerCase();

            boolean databaseAvailable = isDatabaseAvailable();
            if (!databaseAvailable) {
                logger.error("Detected connection error to SQL: {}", errMsgSqlite);
                if (isAlertShowing.compareAndSet(false, true)) {
                    databaseManager.shutdown();
                    restoreDatabase();
                    showDatabaseLostAlertAndRestart();
                }
                holdUntilApplicationExits();
                throw new DatabaseUnavailableException(rootCause);
            }
            throw e;
        }
    }

    private void holdUntilApplicationExits() {
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        while (shutdownLatch.getCount() > 0) {
            try {
                shutdownLatch.await();
            } catch (InterruptedException ex) {
                // Keep repository callers parked while shutdown is in progress; returning would
                // let scheduler threads log restart-control exceptions during application exit.
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for application restart");
            }
        }
    }

    private void showDatabaseLostAlertAndRestart() {
        if (Platform.isFxApplicationThread()) {
            showDatabaseLostAlertOnFxThread();
            restartApplication();
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                showDatabaseLostAlertOnFxThread();
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
            restartApplication();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for database disconnect alert during shutdown");
        }
    }

    private void showDatabaseLostAlertOnFxThread() {
        Alert confirm = AlertHelper.create(Alert.AlertType.ERROR,
                I18n.get("aspect.db.disconnect"), I18n.get("aspect.db.lost"), I18n.get("aspect.db.alert"));
        confirm.showAndWait();
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable cause = throwable.getCause();
        if (cause == null) {
            return throwable;
        }
        return getRootCause(cause);
    }

    // restart application
    private void restartApplication() {
        try {
            List<String> command = new ArrayList<>();

            String userDir = System.getProperty("user.dir");

            File exeFile = new File(userDir + File.separator + "BDMA.exe");

            if (exeFile.exists()) {
                // CASE A: The user is running the program using an installed .exe file
                logger.info("Restarting the application from the .exe file : {}", exeFile.getPath());
                command.add(exeFile.getPath());
            } else {
                // CASE B: Running in a development environment (IDE) or a separate .jar file.
                logger.info("No .exe file found. Restart in Java Dev environment.");
                String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                command.add(javaBin);

                File currentJar = new File(
                        com.app.MainApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (currentJar.getName().endsWith(".jar")) {
                    command.add("-jar");
                    command.add(currentJar.getPath());
                } else {
                    command.add("-cp");
                    command.add(System.getProperty("java.class.path"));
                    command.add(com.app.MainApp.class.getName());
                }
            }

            // Start a new process (whether it's an .exe file or a Java command)
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.start();

            // Completely close old applications.
            Platform.exit();
            System.exit(0);

        } catch (Exception e) {
            logger.error("The application cannot be automatically restarted : ", e);
            // Final backup plan: It's still necessary to shut it down to avoid the user's
            // computer freezing.
            Platform.exit();
            System.exit(1);
        }
    }

    // Check if the database exists.
    private boolean isDatabaseAvailable() {
        try{
            File dbFile = AppDataPaths.dataFile();
            if (!dbFile.exists()) {
                logger.error("Database file not found");
                return false;
            }
            if (dbFile.length() == 0) {
                logger.error("Database file is empty");
                return false;
            }
            try (Connection connection = databaseManager.getDataSourceA().getConnection()) {

                // 1. Check basic connection
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1;");
                }

                // 2. RESOLVING MIGRATE/EMPTY FILE ISSUES: Check if any tables already exist in
                // the database.
                DatabaseMetaData metaData = connection.getMetaData();

                // Get a list of user-defined tables (TABLE), ignoring SQLite system tables.
                try (ResultSet resultSet = metaData.getTables(null, null, "%", new String[] { "TABLE" })) {
                    if (!resultSet.next()) {
                        // If resultSet.next() returns false, it means the database is empty and no
                        // tables have been migrated yet.
                        logger.error("Error: SQLite connection successful, but the database file is empty (0 tables)!");
                        return false;
                    }
                }
                return true; // Live connection and already has tabular data inside.

            } catch (Exception ex) {
                logger.error("Error attempting to connect to SQLite: {}", ex.getMessage());
                return false;
            }
        } catch (Exception ex) {
            logger.error(
                    "Database unavailable: {}",
                    ex.getMessage(),
                    ex);
            return false;
        }
    }
    public void restoreDatabase(){
        try {
            Path source= Paths.get(AppDataPaths.syncDatabaseForderDir());
            Path target= AppDataPaths.dataFile().toPath();
            Files.copy(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
            logger.info("Restore database file");
        } catch (Exception ex) {
            logger.error("Failded to restore database file",ex);
        }
    }
}
