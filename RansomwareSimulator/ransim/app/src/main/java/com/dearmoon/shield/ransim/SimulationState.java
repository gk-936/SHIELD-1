/**
 * SHIELD RANSOMWARE SIMULATOR — SECURITY RESEARCH ONLY
 * =====================================================
 * Package: com.dearmoon.shield.ransim
 *
 * SAFETY CONSTRAINTS:
 * - All file operations confined to sandbox directory only
 * - XOR cipher only (key 0x5A) — NOT real encryption  
 * - Locker overlay always shows password (TEST PASSWORD: 1234)
 * - STOP TEST button always accessible, no password needed
 * - Network simulation targets localhost only (127.0.0.1)
 * - Cleanup/restore runs automatically on stop or app exit
 *
 * SANDBOX PATH:
 * /sdcard/Android/data/com.dearmoon.shield.ransim/shield_ransim_sandbox/
 *
 * TO FORCE CLEANUP IF APP CRASHES:
 * adb shell rm -rf /sdcard/Android/data/com.dearmoon.shield.ransim/
 *
 * FILTER LOGS:
 * adb logcat -s SHIELD_RANSIM
 */
package com.dearmoon.shield.ransim;

import android.os.PowerManager;
import android.view.View;
import java.util.*;
import java.util.concurrent.*;

public class SimulationState {
    public enum Scenario { NONE, CRYPTO, LOCKER, HYBRID, RECON }
    public Scenario activeScenario = Scenario.NONE;
    public boolean isRunning = false;
    public long startTimeMs = 0;
    public int filesEncrypted = 0;
    public int filesTotal = 0;
    public boolean shieldDetected = false;
    public long detectionTimeMs = 0;
    public final Map<String, byte[]> originalFiles = new ConcurrentHashMap<>();
    public View overlayView = null;
    public PowerManager.WakeLock wakeLock = null;
    public final List<Thread> activeThreads = new CopyOnWriteArrayList<>();
    public void reset() {
        activeScenario = Scenario.NONE;
        isRunning = false;
        startTimeMs = 0;
        filesEncrypted = 0;
        filesTotal = 0;
        shieldDetected = false;
        detectionTimeMs = 0;
        originalFiles.clear();
        overlayView = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        for (Thread t : activeThreads) {
            t.interrupt();
        }
        activeThreads.clear();
    }
}
