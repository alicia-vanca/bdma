package com.app.common.modules.databackup.events;

import lombok.Getter;

@Getter
public class FileBackupCompletedEvent {

    private final String localPath;

    public FileBackupCompletedEvent(String localPath) {
        this.localPath = localPath;
    }

}
