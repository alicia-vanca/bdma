package com.app.common.dtos;

import com.app.common.definitions.enums.SyncDirection;
import lombok.Getter;

@Getter
public class StorageProgress {

    private SyncDirection direction;
    private String operation;
    private int total;
    private int success;
    private int failed;

    // chỉ dùng cho final message
    private String status;

    public synchronized void init(SyncDirection direction, int total) {

        this.direction = direction;
        this.operation = direction == SyncDirection.BACKUP_TO_DATA
                ? "setting.storage.btn.restore"
                : "setting.storage.btn.rebuild";
        this.total = total;
        this.success = 0;
        this.failed = 0;
        this.status = null;
    }

    public synchronized void incrementSuccess() {
        success++;
    }

    public synchronized void incrementFailed() {
        failed++;
    }

    public synchronized void completed() {
        this.status = "setting.storage.status.completed";
    }

    public synchronized void failed() {
        this.status = "setting.storage.status.failed";
    }

    public synchronized Object[] buildProgressArgs() {
        return new Object[]{
                total,
                success,
                failed
        };
    }

    public synchronized Object[] buildFinalArgs() {
        return new Object[]{
                status == null ? "" : status,
                success,
                total,
                failed
        };
    }

    public synchronized void reset() {
        operation = "";
        total = 0;
        success = 0;
        failed = 0;
        status = null;
    }
}
