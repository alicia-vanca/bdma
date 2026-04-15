package com.app.common.configs;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback converter that reads device ID or app version directly from the
 * static AppContext, making them available on every thread without MDC
 * initialization.
 * Usage in pattern: %appCtx{deviceId} or %appCtx{version}
 */
public class LogParamConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String option = getFirstOption();
        if (option == null) {
            return "";
        }
        switch (option) {
            case "version":
                String v = AppContext.getVersion();
                return v != null ? v : "";
            case "deviceId":
                String id = AppContext.getDeviceId();
                return id != null ? id : "";
            default:
                return "";
        }
    }
}
