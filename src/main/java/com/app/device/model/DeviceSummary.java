package com.app.device.model;

import com.app.sync.tracker.SyncProgressTracker;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeviceSummary {

    public enum Status {
        CONNECTED,
        OFFLINE,
        UNVALIDATED
    }

    private ValidatedDevice device;
    private String serial;
    private String displayName; // thêm field này
    private Status status;
    private SyncProgressTracker.SyncProgress syncProgress;

    public boolean isConnected() {
        return status == Status.CONNECTED;
    }

    public boolean isUnvalidated() {
        return status == Status.UNVALIDATED;
    }
}
