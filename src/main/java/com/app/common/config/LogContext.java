package com.app.common.config;

import org.slf4j.MDC;

public class LogContext {
    private LogContext() {
    }


    public static void init() {
        MDC.put("deviceId", AppContext.getDeviceId());
        MDC.put("appVersion", AppContext.getVersion());
    }

    public static void clear() {
        MDC.clear();
    }
}