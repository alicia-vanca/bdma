package com.app.common.configs;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.util.StatusPrinter2;
import org.slf4j.LoggerFactory;

import com.app.common.definitions.AppDataPaths;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

@SuppressWarnings("java:S106") // Logger not yet initialized at this stage
public final class LogbackConfigInitializer {

    private LogbackConfigInitializer() {
    }

    public static void initialize() {
        File configFile = AppDataPaths.logConfigFile();

        ensureDirectoriesExist();

        if (shouldUpdateLogbackConfig(configFile)) {
            copyLogbackConfigFromResource(configFile);
        }

        System.setProperty("logging.config", configFile.getAbsolutePath());

        reloadLogbackConfiguration(configFile);
    }

    private static void ensureDirectoriesExist() {
        ensureDir(AppDataPaths.appDir());
        ensureDir(AppDataPaths.logsDir());
        ensureDir(AppDataPaths.configDir());
    }

    private static void ensureDir(String path) {
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("Failed to create directory: " + path);
        }
    }

    private static boolean shouldUpdateLogbackConfig(File configFile) {
        if (!configFile.exists())
            return true;

        try {
            String content = new String(Files.readAllBytes(configFile.toPath()));

            if (content.contains("C:/temp/test.log") || content.contains("C:\\temp\\test.log")) {
                System.out.println("Detected old test logback config, replacing...");
                Files.delete(configFile.toPath());
                return true;
            }
            if (content.contains("LOGGLY_TOKEN") || content.contains("LogglyAppender")) {
                System.out.println("Detected old Loggly config, replacing...");
                Files.delete(configFile.toPath());
                return true;
            }
            if (content.contains("<file>${LOG_DIR}/error.log</file>")) {
                System.out.println("Detected legacy error.log config, replacing...");
                Files.delete(configFile.toPath());
                return true;
            }

            return false;

        } catch (Exception e) {
            return true;
        }
    }

    private static void copyLogbackConfigFromResource(File configFile) {
        InputStream is = LogbackConfigInitializer.class.getClassLoader()
                .getResourceAsStream("logback-spring.xml");

        if (is == null) {
            is = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream("logback-spring.xml");
        }

        if (is == null) {
            System.err.println("logback-spring.xml not found in classpath");
            return;
        }

        try (InputStream resource = is) {
            Files.copy(resource, configFile.toPath());
        } catch (Exception e) {
            System.err.println("Failed to copy logback config: " + e.getMessage());
        }
    }

    private static void reloadLogbackConfiguration(File configFile) {
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.reset();

            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(loggerContext);
            configurator.doConfigure(configFile);

            new StatusPrinter2().print(loggerContext);

        } catch (Exception e) {
            System.err.println("Failed to configure logback: " + e.getMessage());
        }
    }
}