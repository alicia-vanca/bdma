package com.app.common.modules.loggly;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.json.JSONObject;

import com.app.common.configs.AppContext;
import com.app.common.definitions.AppConstants;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;

/**
 * Formats each Logback event as one JSON document for Loggly.
 * Multiline messages and stack traces remain inside a single JSON event.
 */
public class LogglyJsonLayout extends LayoutBase<ILoggingEvent> {

    private static final String APP_NAME = "BDMA";
    private static final ZoneId APP_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Override
    public String doLayout(ILoggingEvent event) {
        JSONObject json = new JSONObject();
        json.put("app", APP_NAME);
        json.put("timestamp", formatTimestamp(event.getTimeStamp()));
        json.put("level", event.getLevel().toString());
        json.put("thread", event.getThreadName());
        json.put("logger", event.getLoggerName());
        json.put("message", event.getFormattedMessage());

        addRuntimeContext(json);
        addThrowable(json, event.getThrowableProxy());

        return json.toString();
    }

    @Override
    public String getContentType() {
        return "application/json";
    }

    private String formatTimestamp(long epochMillis) {
        return TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(APP_TIME_ZONE));
    }

    private void addRuntimeContext(JSONObject json) {
        json.put("deviceId", valueOrEmpty(AppContext.getDeviceId()));
        json.put("deviceName", valueOrEmpty(AppContext.getDeviceName()));
        json.put("version", valueOrDefault(AppContext.getVersion(), AppConstants.VERSION_DEV));
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private void addThrowable(JSONObject json, IThrowableProxy throwableProxy) {
        if (throwableProxy == null) {
            return;
        }

        json.put("exceptionClass", throwableProxy.getClassName());
        json.put("exceptionMessage", throwableProxy.getMessage());
        json.put("stackTrace", ThrowableProxyUtil.asString(throwableProxy));
    }
}
