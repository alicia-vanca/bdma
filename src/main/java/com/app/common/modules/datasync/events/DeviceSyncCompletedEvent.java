package com.app.common.modules.datasync.events;

import lombok.Getter;

@Getter
public class DeviceSyncCompletedEvent {

    private final String hardwareId;

    public DeviceSyncCompletedEvent(String hardwareId) {
        this.hardwareId = hardwareId;
    }

}
