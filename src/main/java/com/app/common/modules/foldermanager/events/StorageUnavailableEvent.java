package com.app.common.modules.foldermanager.events;

import com.app.common.definitions.enums.FolderType;

import lombok.Getter;

@Getter
public class StorageUnavailableEvent {

    private final FolderType target;
    private final StorageIssueReason reason;
    private final long requiredBytes;

    public StorageUnavailableEvent(FolderType target, StorageIssueReason reason) {
        this(target, reason, 0L);
    }

    public StorageUnavailableEvent(FolderType target, StorageIssueReason reason, long requiredBytes) {
        this.target = target;
        this.reason = reason;
        this.requiredBytes = Math.max(requiredBytes, 0L);
    }
}
