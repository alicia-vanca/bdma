package com.app.common.services;

import com.app.common.modules.datasync.queues.DeviceSyncQueue;
import javafx.application.Platform;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class DeviceTracker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeviceTracker.class);

    private final DeviceSyncQueue queue;
    private final AdbClient adbClient;
    private final Set<String> currentDevices = new HashSet<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean running = true;

    @Setter
    @Getter
    private Runnable onDeviceChanged;
    @Setter
    private Consumer<String> onNewSerial;

    private Process trackProcess;
    private Thread trackerThread;

    private final Object processLock = new Object();
    private final Object threadLock = new Object();
    // Holds the active adb track-devices process so stop() can destroy it
    // immediately.
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    public DeviceTracker(DeviceSyncQueue queue,
                         AdbClient adbClient) {
        this.queue = queue;
        this.adbClient = adbClient;
    }

    @Override
    public void run() {
        if (!started.compareAndSet(false, true)) {
            log.warn("DeviceTracker is already running; ignore duplicate start");
            return;
        }

        setTrackerThread(Thread.currentThread());

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                track();
            } catch (Exception e) {
                if (running) {
                    log.warn("Device tracker loop error: {}", e.getMessage());
                    sleep(2000);
                }
            }
        }

        clearTrackerThread();
        started.set(false);
    }

    // Signals the tracker to stop and destroys the active adb process so the
    // OS-level process exits instead of being abandoned in the task manager.
    public void stop() {
        running = false;
        Process p = activeProcess.getAndSet(null);
        if (p != null) {
            p.destroyForcibly();
        }
    }

    private void track() {
        try {
            Process p = adbClient.startTrackDevicesProcess();
            setTrackProcess(p);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        sleep(200);
                        handle(getDevices());
                    }
                }
            }

            p.waitFor();

        } catch (Exception e) {
            if (running) {
                sleep(2000);
            }
        } finally {
            destroyProcess();
        }
    }

    private List<String> getDevices() {
        try {
            return adbClient.listConnectedSerials();
        } catch (Exception e) {
            log.error("Failed to get devices via adb", e);
            return List.of();
        }
    }

    private void handle(List<String> newDevices) {
        Set<String> newSet = new HashSet<>(newDevices);
        boolean changed = false;

        for (String serial : newSet) {
            if (!currentDevices.contains(serial)) {
                changed = true;

                if (onNewSerial != null) {
                    onNewSerial.accept(serial);
                }
            }
        }

        for (String serial : new HashSet<>(currentDevices)) {
            if (!newSet.contains(serial)) {
                queue.remove(serial);
                changed = true;
            }
        }

        currentDevices.clear();
        currentDevices.addAll(newSet);

        if (changed && onDeviceChanged != null) {
            Platform.runLater(onDeviceChanged);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        running = false;
        destroyProcess();
        interruptTrackerThread();
    }

    private void setTrackProcess(Process p) {
        synchronized (processLock) {
            this.trackProcess = p;
        }
    }

    private void destroyProcess() {
        synchronized (processLock) {
            if (trackProcess != null && trackProcess.isAlive()) {
                trackProcess.destroy();
            }
            trackProcess = null;
        }
    }

    private void setTrackerThread(Thread t) {
        synchronized (threadLock) {
            this.trackerThread = t;
        }
    }

    private void interruptTrackerThread() {
        synchronized (threadLock) {
            if (trackerThread != null) {
                trackerThread.interrupt();
            }
        }
    }

    private void clearTrackerThread() {
        synchronized (threadLock) {
            trackerThread = null;
        }
    }
}
