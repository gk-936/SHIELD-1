package com.dearmoon.shield.data;

import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight internal event bus for decoupling sensors from analysis logic.
 * Ensures consistent delivery of security events across the framework.
 */
public class ShieldEventBus {
    private static final String TAG = "ShieldEventBus";
    private static ShieldEventBus instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ShieldEventListener> listeners = new CopyOnWriteArrayList<>();

    public interface ShieldEventListener {
        void onFileSystemEvent(FileSystemEvent event);
        void onNetworkEvent(NetworkEvent event);
        void onHoneyfileEvent(HoneyfileEvent event);
        void onLockerEvent(com.dearmoon.shield.lockerguard.LockerShieldEvent event);
    }

    private ShieldEventBus() {}

    public static synchronized ShieldEventBus getInstance() {
        if (instance == null) {
            instance = new ShieldEventBus();
        }
        return instance;
    }

    public void register(ShieldEventListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unregister(ShieldEventListener listener) {
        listeners.remove(listener);
    }

    public void publishFileSystemEvent(FileSystemEvent event) {
        for (ShieldEventListener l : listeners) {
            l.onFileSystemEvent(event);
        }
    }

    public void publishNetworkEvent(NetworkEvent event) {
        for (ShieldEventListener l : listeners) {
            l.onNetworkEvent(event);
        }
    }

    public void publishHoneyfileEvent(HoneyfileEvent event) {
        for (ShieldEventListener l : listeners) {
            l.onHoneyfileEvent(event);
        }
    }

    public void publishLockerEvent(com.dearmoon.shield.lockerguard.LockerShieldEvent event) {
        for (ShieldEventListener l : listeners) {
            l.onLockerEvent(event);
        }
    }
}
