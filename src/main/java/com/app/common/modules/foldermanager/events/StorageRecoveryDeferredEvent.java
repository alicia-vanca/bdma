package com.app.common.modules.foldermanager.events;

import com.app.common.definitions.enums.FolderType;

import lombok.Getter;

@Getter
public class StorageRecoveryDeferredEvent {

    private final FolderType target;

    public StorageRecoveryDeferredEvent(FolderType target) {
        this.target = target;
    }
}