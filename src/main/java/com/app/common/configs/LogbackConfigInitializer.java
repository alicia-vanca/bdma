package com.app.common.configs;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.util.StatusPrinter2;
import org.slf4j.LoggerFactory;

import com.app.common.definitions.AppDataPaths;

import java.io.File;
import java.net.URL;

public final class LogbackConfigInitializer {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(LogbackConfigInitializer.class);

    private LogbackConfigInitializer() {
    }

    public static void initialize() {
        ensureLogsDirectoryExists();
        AppRuntimeInitializer.resolveAppIdentity();

        URL configUrl = getLogbackConfigResource();
        if (configUrl == null) {
            log.error("logback-spring.xml not found in classpath");
            return;
        }

        System.setProperty("logging.config", "classpath:logback-spring.xml");
        reloadLogbackConfiguration(configUrl);
    }

    private static void ensureLogsDirectoryExists() {
        ensureDir(AppDataPaths.logsDir());
    }

    private static void ensureDir(String path) {
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("Failed to create directory: {}", path);
        }
    }

    private static URL getLogbackConfigResource() {
        URL resource = LogbackConfigInitializer.class.getClassLoader().getResource("logback-spring.xml");
        if (resource == null) {
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            if (contextClassLoader != null) {
                resource = contextClassLoader.getResource("logback-spring.xml");
            }
        }
        return resource;
    }

    @SuppressWarnings("java:S106") // Logger not yet initialized at this stage
    private static void reloadLogbackConfiguration(URL configUrl) {
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.reset();

            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(loggerContext);
            configurator.doConfigure(configUrl);

            new StatusPrinter2().print(loggerContext);

        } catch (Exception e) {
            System.err.println("Failed to configure logback: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }
}
