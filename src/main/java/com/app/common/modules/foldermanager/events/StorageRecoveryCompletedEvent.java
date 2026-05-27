package com.app.common.modules.foldermanager.events;

import lombok.Getter;

@Getter
public class StorageRecoveryCompletedEvent {
    private final Object source;

    public StorageRecoveryCompletedEvent(Object source) {
        this.source = source;
    }

}
