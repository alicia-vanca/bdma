package com.app.common.modules.datasync;

import com.app.common.services.DeviceTracker;
import com.app.common.modules.datasync.workers.SyncWorker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SyncRunner implements CommandLineRunner {

    private final SyncWorker worker;
    private final DeviceTracker tracker;

    public SyncRunner(SyncWorker worker, DeviceTracker tracker) {
        this.worker = worker;
        this.tracker = tracker;
    }

    @Override
    public void run(String... args) {
        Thread syncThread = new Thread(worker, "sync-worker");
        syncThread.start();

        Thread trackerThread = new Thread(tracker, "device-tracker");
        trackerThread.start();
    }
}
