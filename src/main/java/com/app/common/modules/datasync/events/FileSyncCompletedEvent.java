package com.app.common.modules.datasync.events;

import lombok.Getter;

@Getter
public class FileSyncCompletedEvent {

    private final String hardwareId;
    private final String syncedPath;

    public FileSyncCompletedEvent(String hardwareId, String syncedPath) {
        this.hardwareId = hardwareId;
        this.syncedPath = syncedPath;
    }
}
