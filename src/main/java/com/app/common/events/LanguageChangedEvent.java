package com.app.common.events;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class LanguageChangedEvent extends ApplicationEvent {

    private final String languageTag;

    public LanguageChangedEvent(Object source, String languageTag) {
        super(source);
        this.languageTag = languageTag;
    }

}
