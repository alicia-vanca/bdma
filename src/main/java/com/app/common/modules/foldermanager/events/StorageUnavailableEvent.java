package com.app.common.modules.foldermanager.events;

import java.nio.file.Path;

import com.app.common.definitions.enums.FolderType;

import com.app.common.definitions.enums.StorageIssueReason;
import lombok.Getter;

@Getter
public class StorageUnavailableEvent {

    private final FolderType target;
    private final StorageIssueReason reason;
    private final long requiredBytes;
    // The exact directory that triggered the error; null when not applicable.
    private final Path failingDir;
    // Number of files still waiting to be exported (including the triggering file);
    // 0 when not applicable (sync/backup events).
    private final int remainingCount;

    public StorageUnavailableEvent(FolderType target, StorageIssueReason reason) {
        this(target, reason, 0L, null, 0);
    }

    public StorageUnavailableEvent(FolderType target, StorageIssueReason reason, long requiredBytes) {
        this(target, reason, requiredBytes, null, 0);
    }

    public StorageUnavailableEvent(FolderType target, StorageIssueReason reason, Path failingDir) {
        this(target, reason, 0L, failingDir, 0);
    }

    /**
     * Used by the export worker to carry the remaining-file count for the dialog.
     */
    public StorageUnavailableEvent(FolderType target, StorageIssueReason reason, Path failingDir, int remainingCount) {
        this(target, reason, 0L, failingDir, remainingCount);
    }

    public StorageUnavailableEvent(FolderType target, StorageIssueReason reason, long requiredBytes, Path failingDir) {
        this(target, reason, requiredBytes, failingDir, 0);
    }

    private StorageUnavailableEvent(FolderType target, StorageIssueReason reason, long requiredBytes, Path failingDir,
            int remainingCount) {
        this.target = target;
        this.reason = reason;
        this.requiredBytes = Math.max(requiredBytes, 0L);
        this.failingDir = failingDir;
        this.remainingCount = Math.max(remainingCount, 0);
    }
}
