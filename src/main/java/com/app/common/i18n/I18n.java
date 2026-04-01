package com.app.common.i18n;

import com.app.common.config.AppPaths;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;

public class I18n {

    private I18n() {
    }

    private static final Logger log = LoggerFactory.getLogger(I18n.class);
    private static final String LOCALE_FILE = "language.properties";

    @Getter
    private static Locale locale = Locale.forLanguageTag("vi");
    @Getter
    private static ResourceBundle bundle = load();

    private static ResourceBundle load() {
        return ResourceBundle.getBundle("i18n/messages", locale);
    }

    public static void loadSavedLocale() {
        try {
            Path localeFile = Path.of(AppPaths.configDir()).resolve(LOCALE_FILE);
            if (Files.exists(localeFile)) {
                Properties props = new Properties();
                try (InputStream is = Files.newInputStream(localeFile)) {
                    props.load(is);
                    String langTag = props.getProperty("language", "vi");
                    setLocale(Locale.forLanguageTag(langTag));
                    log.debug("Loaded saved locale: {}", langTag);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load saved locale, using default", e);
        }
    }

    public static void setLocale(Locale newLocale) {
        locale = newLocale;
        bundle = load();
        saveLocale();
    }

    private static void saveLocale() {
        try {
            Path configPath = Path.of(AppPaths.configDir());
            Files.createDirectories(configPath);
            Path localeFile = configPath.resolve(LOCALE_FILE);

            Properties props = new Properties();
            props.setProperty("language", locale.toLanguageTag());

            try (OutputStream os = Files.newOutputStream(localeFile)) {
                props.store(os, "Language Preference");
            }
        } catch (IOException e) {
            log.error("Failed to save locale preference", e);
        }
    }

    public static String get(String key) {
        return bundle.getString(key);
    }

    public static String get(String key, Object... args) {
        String pattern = bundle.getString(key);
        return MessageFormat.format(pattern, args);
    }
}