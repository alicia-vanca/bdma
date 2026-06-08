package com.app.common.modules.foldermanager.events;

import com.app.common.definitions.enums.FolderType;

import lombok.Getter;

@Getter
public class StorageRestoredEvent {

    private final FolderType target;

    public StorageRestoredEvent(FolderType target) {
        this.target = target;
    }
}
