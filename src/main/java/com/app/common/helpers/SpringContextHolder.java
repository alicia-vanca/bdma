package com.app.common.helpers;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

@SuppressWarnings("java:S1118")
@Component
public class SpringContextHolder {
    // Guard context assignment so static bean lookups never run with a null
    // container.
    public static void setContext(@NonNull ApplicationContext context) {
        SpringContextHolder.context = Objects.requireNonNull(context, "ApplicationContext must not be null");
    }

    private static ApplicationContext context;

    // Capture Spring context at startup to support controller factory lookups from
    // static call sites.
    @Autowired
    public SpringContextHolder(@NonNull ApplicationContext context) {
        setContext(context);
    }

    // Match Spring's non-null Class contract and fail fast with a clear message
    // when context is uninitialized.
    public static <T> T getBean(@NonNull Class<T> clazz) {
        ApplicationContext currentContext = context;
        if (currentContext == null) {
            throw new IllegalStateException("ApplicationContext is not initialized yet");
        }
        return currentContext.getBean(Objects.requireNonNull(clazz, "Bean class must not be null"));
    }

    public static ApplicationContext getContext() {
        ApplicationContext currentContext = context;
        if (currentContext == null) {
            throw new IllegalStateException("ApplicationContext is not initialized yet");
        }
        return currentContext;
    }
}
