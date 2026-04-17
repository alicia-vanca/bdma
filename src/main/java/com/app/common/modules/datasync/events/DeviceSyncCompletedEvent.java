package com.app.common.modules.datasync.events;

import lombok.Getter;

@Getter
public class DeviceSyncCompletedEvent {

    private final String serial;

    public DeviceSyncCompletedEvent(String serial) {
        this.serial = serial;
    }

}
