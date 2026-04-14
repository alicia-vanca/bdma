package com.app.sync.tracker;

import com.app.common.session.Session;
import com.app.common.adb.AdbService;
import com.app.device.repository.ValidatedDeviceRepository;
import com.app.sync.model.SyncContext;
import com.app.sync.queue.DeviceQueue;
import javafx.application.Platform;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class DeviceTracker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeviceTracker.class);

    private final DeviceQueue queue;
    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final Session session;
    private final AdbService adbService;

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

    public DeviceTracker(DeviceQueue queue,
                         ValidatedDeviceRepository validatedDeviceRepository,
                         Session session,
                         AdbService adbService) {
        this.queue = queue;
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.session = session;
        this.adbService = adbService;
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

    private void track() {
        try {
            Process p = adbService.startTrackDevicesProcess();
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
        return new ArrayList<>(adbService.getConnectedSerials());
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

                if (validatedDeviceRepository.isAllowed(serial)
                        && session.getUser() != null) {
                    queue.add(serial, new SyncContext(
                            session.getUser().getUsername(),
                            session.isAdmin()));
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