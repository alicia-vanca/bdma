package com.app.common.helpers;

import com.app.common.modules.i18n.I18n;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.StageStyle;
// Các import CHUẨN của Spring AOP
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.SQLException;
import org.sqlite.SQLiteException;
import org.sqlite.SQLiteErrorCode;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.lang.management.ManagementFactory;
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
    @Autowired
    private DataSource dataSource;
    // Scan all repositories in the directory. com.app.common.repositories
    @Around("execution(* com.app.common.repositories.*.*(..))")
    public Object handleDatabaseException(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed(); // Run the JdbcTemplate command.
        } catch (Throwable e) {
            Throwable rootCause = getRootCause(e);
            //Check for SQLException errors
            if (rootCause instanceof SQLException) {
                SQLException sqlException = (SQLException) rootCause;
                String errorMsg = sqlException.getMessage().toLowerCase();
                logger.error("Detected connection error to SQL: {}", errorMsg);

                //Check for SQLiteException errors.
                if (rootCause instanceof org.sqlite.SQLiteException) {
                    org.sqlite.SQLiteException sqliteEx = (org.sqlite.SQLiteException) rootCause;
                    String errMsgSqlite=sqliteEx.getMessage().toLowerCase();
                    boolean isDatabaseAlive = checkDatabaseConnection();
                    if (!isDatabaseAlive) {
                        // Display the alert on the JavaFX Platform.runLater UI and restart the app.
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle(I18n.get("helper.db.disconnect"));
                            alert.setHeaderText(I18n.get("helper.db.lost"));
                            alert.setContentText(I18n.get("helper.db.alert"));
                            alert.setGraphic(null);
                            alert.showAndWait();
                            restartApplication();
                        });
                    }
                }
            }
            throw new RuntimeException("SQLite disconnect error. Restarting...");
        }
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable cause = throwable.getCause();
        if (cause == null) {
            return throwable;
        }
        return getRootCause(cause);
    }
    //restart application
    private void restartApplication() {
        try {
            List<String> command = new ArrayList<>();

            List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
            String sunJavaCommand = System.getProperty("sun.java.command");

            String userDir = System.getProperty("user.dir");

            File exeFile = new File(userDir + File.separator + "BDMA.exe");

            if (exeFile.exists()) {
                // CASE A: The user is running the program using an installed .exe file
                logger.info("Restarting the application from the .exe file.: {}", exeFile.getPath());
                command.add(exeFile.getPath());
            } else {
                // CASE B: Running in a development environment (IDE) or a separate .jar file.
                logger.info("No .exe file found. Restart in Java Dev environment.");
                String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                command.add(javaBin);

                File currentJar = new File(com.app.MainApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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
            logger.error("The application cannot be automatically restarted.: ", e);
            // Final backup plan: It's still necessary to shut it down to avoid the user's computer freezing.
            Platform.exit();
            System.exit(1);
        }
    }

    //Check if the database exists.
    private boolean checkDatabaseConnection() {
        try (Connection connection = dataSource.getConnection()) {

            // 1. Check basic connection
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1;");
            }

            // 2. RESOLVING MIGRATE/EMPTY FILE ISSUES: Check if any tables already exist in the database.
            DatabaseMetaData metaData = connection.getMetaData();

            // Get a list of user-defined tables (TABLE), ignoring SQLite system tables.
            try (ResultSet resultSet = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                if (!resultSet.next()) {
                    // If resultSet.next() returns false, it means the database is empty and no tables have been migrated yet.
                    logger.error("Error: SQLite connection successful, but the database file is empty (0 tables)!");
                    return false;
                }
            }

            return true; // Live connection and already has tabular data inside.

        } catch (Exception ex) {
            logger.error("Error attempting to connect to SQLite: {}", ex.getMessage());
            return false;
        }
    }
}
