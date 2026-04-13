package com.app.common.modules.i18n;

import com.app.common.definitions.AppConstants;
import com.app.common.helpers.SpringContextHolder;
import com.app.common.services.AppConfigService;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

public class I18n {

    private I18n() {
    }

    private static final Logger log = LoggerFactory.getLogger(I18n.class);
    private static final String KEY_LANGUAGE = AppConstants.KEY_LANGUAGE;

    @Getter
    private static Locale locale = Locale.forLanguageTag("vi");
    @Getter
    private static ResourceBundle bundle = load();

    private static ResourceBundle load() {
        return ResourceBundle.getBundle("i18n/messages", locale);
    }

    public static void loadSavedLocale() {
        try {
            AppConfigService appConfigService = resolveConfigService();
            if (appConfigService == null) {
                return;
            }
            String langTag = appConfigService.getConfigValue(KEY_LANGUAGE);
            if (langTag == null || langTag.isBlank())
                langTag = "vi";
            setLocale(Locale.forLanguageTag(langTag));
            log.debug("Loaded saved locale from DB: {}", langTag);
        } catch (Exception e) {
            log.warn("Failed to load saved locale from DB, using default", e);
        }
    }

    public static void setLocale(Locale newLocale) {
        if (newLocale == null) {
            return;
        }

        if (newLocale.equals(locale)) {
            return;
        }

        locale = newLocale;
        bundle = load();
        AppConfigService appConfigService = resolveConfigService();
        if (appConfigService != null) {
            appConfigService.saveConfigValue(KEY_LANGUAGE, locale.toLanguageTag());
        }
    }

    private static AppConfigService resolveConfigService() {
        try {
            return SpringContextHolder.getBean(AppConfigService.class);
        } catch (Exception ignored) {
            return null;
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