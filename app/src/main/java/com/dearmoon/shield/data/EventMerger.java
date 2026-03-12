package com.dearmoon.shield.data;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;
import java.util.ArrayList;

/**
 * EventMerger: Aggregates and deduplicates events from Mode A (eBPF/daemon) and Mode B (FileObserver).
 * Merges file path (prefer Mode B) and process info (prefer Mode A) for /sdcard/ events.
 */
public class EventMerger {
    private static final long DEDUP_WINDOW_MS = 1000; // 1 second window
    private final ConcurrentLinkedQueue<MergedEvent> recentEvents = new ConcurrentLinkedQueue<>();

    public static class MergedEvent {
        public String filePath;
        public String operation;
        public Long timestamp;
        public Integer uid;
        public Integer pid;
        public String source; // MODE_A, MODE_B, MERGED
        public boolean mergeFlag;
        public TelemetryEvent rawA;
        public TelemetryEvent rawB;
    }

    public synchronized MergedEvent mergeEvent(TelemetryEvent event, String source) {
        long now = System.currentTimeMillis();
        // Try to find a matching event in the deduplication window
        for (MergedEvent e : recentEvents) {
            if (isDuplicate(e, event, source, now)) {
                // Merge details
                if (source.equals("MODE_A")) {
                    e.uid = event.getUid();
                    if (event instanceof FileSystemEvent) {
                        e.pid = ((FileSystemEvent) event).getPid();
                    } else {
                        e.pid = -1;
                    }
                    e.rawA = event;
                } else {
                    if (event instanceof FileSystemEvent) {
                        e.filePath = ((FileSystemEvent) event).getFilePath();
                    } else {
                        e.filePath = null;
                    }
                    e.rawB = event;
                }
                e.mergeFlag = true;
                e.source = "MERGED";
                return e;
            }
        }
        // No duplicate found, create new
        MergedEvent merged = new MergedEvent();
        if (event instanceof FileSystemEvent) {
            merged.filePath = ((FileSystemEvent) event).getFilePath();
            merged.operation = ((FileSystemEvent) event).getOperation();
            merged.pid = ((FileSystemEvent) event).getPid();
        } else {
            merged.filePath = null;
            merged.operation = null;
            merged.pid = -1;
        }
        merged.timestamp = event.getTimestamp();
        merged.uid = event.getUid();
        merged.source = source;
        merged.mergeFlag = false;
        if (source.equals("MODE_A")) merged.rawA = event;
        else merged.rawB = event;
        recentEvents.add(merged);
        // Clean up old events
        cleanOldEvents(now);
        return merged;
    }

    private boolean isDuplicate(MergedEvent e, TelemetryEvent event, String source, long now) {
        String eventOperation = (event instanceof FileSystemEvent) ? ((FileSystemEvent) event).getOperation() : null;
        if (e.operation != null && eventOperation != null && !e.operation.equals(eventOperation)) return false;
        if (Math.abs(e.timestamp - event.getTimestamp()) > DEDUP_WINDOW_MS) return false;
        // If both have filePath, must match
        String eventFilePath = (event instanceof FileSystemEvent) ? ((FileSystemEvent) event).getFilePath() : null;
        if (e.filePath != null && eventFilePath != null && !e.filePath.equals(eventFilePath)) return false;
        // If both have pid, must match
        Integer eventPid = (event instanceof FileSystemEvent) ? ((FileSystemEvent) event).getPid() : null;
        if (e.pid != null && eventPid != null && !e.pid.equals(eventPid)) return false;
        return true;
    }

    private void cleanOldEvents(long now) {
        while (!recentEvents.isEmpty()) {
            MergedEvent e = recentEvents.peek();
            if (now - e.timestamp > DEDUP_WINDOW_MS) recentEvents.poll();
            else break;
        }
    }

    public List<MergedEvent> getRecentEvents() {
        return new ArrayList<>(recentEvents);
    }
}
