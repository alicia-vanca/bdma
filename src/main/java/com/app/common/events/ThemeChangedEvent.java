package com.app.common.events;

import org.springframework.context.ApplicationEvent;

public class ThemeChangedEvent extends ApplicationEvent {

    private final String theme;

    public ThemeChangedEvent(Object source, String theme) {
        super(source);
        this.theme = theme;
    }

    public String getTheme() {
        return theme;
    }
}
