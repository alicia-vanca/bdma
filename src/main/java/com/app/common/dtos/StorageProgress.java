package com.app.common.dtos;

import lombok.Getter;

@Getter
public class StorageProgress {
    private int total;
    private int success;
    private int failed;
    private int checksumInvalid;
    private int skipped;

    public synchronized void init(int total) {
        this.total = total;
        this.success = 0;
        this.failed = 0;
        this.checksumInvalid = 0;
        this.skipped = 0;
    }

    public synchronized void incrementSuccess() {
        success++;
    }

    public synchronized void incrementFailed() {
        failed++;
    }

    public synchronized void incrementChecksumInvalid() {
        checksumInvalid++;
    }

    public synchronized void incrementSkipped(int count) {
        skipped += count;
    }

    public synchronized Object[] buildProgressArgs() {
        return new Object[]{total, success, failed, checksumInvalid, skipped};
    }

    public synchronized void reset() {
        total = 0;
        success = 0;
        failed = 0;
        checksumInvalid = 0;
        skipped = 0;
    }
}
