package com.dearmoon.shield.collectors;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.FileObserver;
import android.util.Log;
import androidx.annotation.Nullable;
import com.dearmoon.shield.data.HoneyfileEvent;
import com.dearmoon.shield.data.TelemetryStorage;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class HoneyfileCollector {
    private static final String TAG = "HoneyfileCollector";
    private final TelemetryStorage storage;
    private final List<HoneyfileObserver> observers = new ArrayList<>();
    private final List<File> honeyfiles = new ArrayList<>();
    private final String appPackageName;
    private final Context context;
    private final UnifiedDetectionEngine detectionEngine;
    private static final long CREATION_GRACE_PERIOD_MS = 5000; // 5 seconds after creation

    public HoneyfileCollector(TelemetryStorage storage, Context context, UnifiedDetectionEngine detectionEngine) {
        this.storage = storage;
        this.context = context;
        this.detectionEngine = detectionEngine;
        this.appPackageName = context.getPackageName();
        Log.i(TAG, "HoneyfileCollector initialized - Package: " + appPackageName);
    }

    public void createHoneyfiles(Context context, String[] directories) {
        final int HONEYFILE_COUNT = 6;

        for (String dir : directories) {
            File directory = new File(dir);
            if (!directory.exists()) continue;

            for (int i = 0; i < HONEYFILE_COUNT; i++) {
                // L-03: Use device-fingerprint-derived name instead of static predictable names
                String name = generateHoneyfileName(dir, i);
                File honeyfile = new File(directory, name);
                try (FileWriter writer = new FileWriter(honeyfile)) {
                    writer.write("SHIELD HONEYFILE - DO NOT ACCESS\n");
                    writer.write("This is a decoy file for ransomware detection.\n");
                    honeyfiles.add(honeyfile);
                    
                    // Make readable to trigger access attempts
                    honeyfile.setReadable(true, false);
                    honeyfile.setWritable(true, false);
                    
                    HoneyfileObserver observer = new HoneyfileObserver(honeyfile.getAbsolutePath());
                    observer.startWatching();
                    observers.add(observer);
                    
                    Log.d(TAG, "Created honeyfile: " + honeyfile.getAbsolutePath());
                } catch (Exception e) {
                    Log.e(TAG, "Failed to create honeyfile", e);
                }
            }
        }
        
        Log.i(TAG, "Honeyfile creation complete. Grace period: " + CREATION_GRACE_PERIOD_MS + "ms");
    }

    public void stopWatching() {
        for (HoneyfileObserver observer : observers) {
            observer.stopWatching();
        }
        observers.clear();
    }

    public void clearAllHoneyfiles() {
        Log.i(TAG, "Clearing all honeyfiles...");
        int deletedCount = 0;
        for (File honeyfile : honeyfiles) {
            if (honeyfile.exists() && honeyfile.delete()) {
                Log.d(TAG, "Deleted honeyfile: " + honeyfile.getAbsolutePath());
                deletedCount++;
            }
        }
        honeyfiles.clear();
        Log.i(TAG, "Cleared " + deletedCount + " honeyfiles");
    }

    // L-03: Derive an unpredictable but stable honeyfile name by hashing device + app
    // fingerprint. Names are stored in SharedPreferences so they survive process restarts.
    private String generateHoneyfileName(String dir, int index) {
        String key = "honeyfile_" + dir.hashCode() + "_" + index;
        SharedPreferences prefs = context.getSharedPreferences("ShieldHoneyfiles", Context.MODE_PRIVATE);
        if (prefs != null) {
            String stored = prefs.getString(key, null);
            if (stored != null) return stored;
        }

        String[] extensions = {".txt", ".dat", ".bin", ".bak", ".key", ".db"};
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = (pm != null) ? pm.getPackageInfo(context.getPackageName(), 0) : null;
            long installTime = (pi != null) ? pi.firstInstallTime : 0L;
            String seed = Build.FINGERPRINT + dir + index + installTime;
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            String hex = String.format("%02x%02x%02x%02x", hash[0], hash[1], hash[2], hash[3]);
            String name = hex + extensions[index % extensions.length];
            if (prefs != null) {
                prefs.edit().putString(key, name).apply();
            }
            return name;
        } catch (Exception e) {
            return "shield_" + Math.abs(dir.hashCode()) + "_" + index + ".dat";
        }
    }

    private class HoneyfileObserver extends FileObserver {
        private final String filePath;
        // L-02: Per-file creation time so each observer has its own grace window
        private final long fileCreationTime;

        HoneyfileObserver(String path) {
            super(path, OPEN | MODIFY | DELETE | CLOSE_WRITE);
            this.filePath = path;
            this.fileCreationTime = new File(path).lastModified();
        }

        @Override
        public void onEvent(int event, @Nullable String path) {
            if (event == OPEN || event == MODIFY || event == DELETE || event == CLOSE_WRITE) {
                String accessType = getAccessType(event);

                // L-02: Use per-file creation time so one file's grace period doesn't
                // mask alerts on other honeyfiles created at different times
                long timeSinceCreation = System.currentTimeMillis() - fileCreationTime;
                if (timeSinceCreation < CREATION_GRACE_PERIOD_MS) {
                    Log.d(TAG, "Skipping honeyfile event during grace period: " + filePath + " (" + timeSinceCreation + "ms since creation)");
                    return;
                }
                
                // ALL other access is suspicious - log it
                int callingUid = android.os.Binder.getCallingUid(); // For informational purposes only
                HoneyfileEvent honeyEvent = new HoneyfileEvent(
                    filePath, accessType, callingUid, "uid:" + callingUid
                );
                storage.store(honeyEvent);
                Log.w(TAG, "⚠️ HONEYFILE TRAP TRIGGERED: " + filePath + " (" + accessType + ") by UID " + callingUid);

                // EMERGENCY KILL: Honeyfile access bypassed SPRT/Entropy requirements
                if (detectionEngine != null) {
                    detectionEngine.triggerHoneyfileKill(filePath, callingUid);
                }
            }
        }

        private String getAccessType(int event) {
            switch (event) {
                case OPEN: return "OPEN";
                case MODIFY: return "MODIFY";
                case DELETE: return "DELETE";
                case CLOSE_WRITE: return "WRITE";
                default: return "UNKNOWN";
            }
        }
    }
}
