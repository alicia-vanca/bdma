package com.app.common.modules.datasync.queues;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.common.dtos.SyncContext;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class DeviceSyncQueue {

    private static final Logger log = LoggerFactory.getLogger(DeviceSyncQueue.class);

    public record Entry(String serial, SyncContext context) {
    }

    private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private final Set<String> inQueue = ConcurrentHashMap.newKeySet();

    private volatile String current = null;

    public void add(String serial, SyncContext context) {
        if (serial.equals(current) || !inQueue.add(serial))
            return;

        if (queue.offer(new Entry(serial, context))) {
            log.info("Queue add: {}", serial);
        } else {
            inQueue.remove(serial);
        }
    }

    public Entry take() throws InterruptedException {
        Entry entry = queue.take();
        current = entry.serial();
        inQueue.remove(entry.serial());
        return entry;
    }

    public void done(String serial) {
        if (serial.equals(current)) {
            current = null;
        }
    }

    public void remove(String serial) {
        queue.removeIf(e -> e.serial().equals(serial));
        inQueue.remove(serial);

        if (serial.equals(current)) {
            current = null;
        }
    }
}
