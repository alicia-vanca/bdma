package com.app.common.modules.foldermanager.events;

import com.app.common.definitions.enums.FolderType;
import com.app.common.definitions.enums.StorageIssueReason;

import lombok.Getter;

@Getter
public class StorageRecoveryDeferredEvent {

    private final FolderType target;
    private final StorageIssueReason reason;

    public StorageRecoveryDeferredEvent(FolderType target, StorageIssueReason reason) {
        this.target = target;
        this.reason = reason;
    }
}
