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
        SharedPreferences prefs = context.getSharedPreferences("ShieldHoneyfiles", Context.MODE_PRIVATE);

        for (String dir : directories) {
            File directory = new File(dir);
            if (!directory.exists()) continue;

            for (int i = 0; i < HONEYFILE_COUNT; i++) {
                String name = generateHoneyfileName(dir, i);
                File honeyfile = new File(directory, name);
                deploySingleHoneyfile(honeyfile, prefs);
            }
        }
        
        Log.i(TAG, "Honeyfile creation complete. Grace period: " + CREATION_GRACE_PERIOD_MS + "ms");
    }

    private void deploySingleHoneyfile(File honeyfile, SharedPreferences prefs) {
        try (FileWriter writer = new FileWriter(honeyfile)) {
            writer.write("SHIELD HONEYFILE - DO NOT ACCESS\n");
            writer.write("This is a decoy file for ransomware detection.\n");
            
            honeyfile.setReadable(true, false);
            honeyfile.setWritable(true, false);
            
            String originalHash = calculateFileHash(honeyfile);
            if (prefs != null && originalHash != null) {
                prefs.edit().putString("hash_" + honeyfile.getAbsolutePath(), originalHash).apply();
            }
            
            // Avoid duplicate observers
            boolean alreadyObserved = false;
            for (HoneyfileObserver o : observers) {
                if (o.getFilePath().equals(honeyfile.getAbsolutePath())) {
                    alreadyObserved = true;
                    break;
                }
            }
            
            if (!alreadyObserved) {
                HoneyfileObserver observer = new HoneyfileObserver(honeyfile.getAbsolutePath());
                observer.startWatching();
                observers.add(observer);
            }
            
            if (!honeyfiles.contains(honeyfile)) {
                honeyfiles.add(honeyfile);
            }
            
            Log.d(TAG, "Deployed honeyfile: " + honeyfile.getAbsolutePath() + " (Hash: " + originalHash + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to deploy honeyfile: " + honeyfile.getAbsolutePath(), e);
        }
    }

    public void verifyAndRedeploy(Context context, String[] directories) {
        Log.i(TAG, "Watchdog: Starting periodic honeyfile integrity check...");
        SharedPreferences prefs = context.getSharedPreferences("ShieldHoneyfiles", Context.MODE_PRIVATE);
        
        for (String dir : directories) {
            File directory = new File(dir);
            if (!directory.exists()) continue;

            for (int i = 0; i < 6; i++) {
                String name = generateHoneyfileName(dir, i);
                File honeyfile = new File(directory, name);
                
                if (!honeyfile.exists()) {
                    Log.w(TAG, "Watchdog: Honeyfile MISSING! Redeploying: " + honeyfile.getAbsolutePath());
                    deploySingleHoneyfile(honeyfile, prefs);
                } else {
                    // Check integrity of existing file
                    String currentHash = calculateFileHash(honeyfile);
                    String originalHash = prefs.getString("hash_" + honeyfile.getAbsolutePath(), null);
                    
                    if (currentHash != null && !currentHash.equals(originalHash)) {
                        Log.e(TAG, "Watchdog: Honeyfile TAMPERED! [Content Mismatch] " + honeyfile.getAbsolutePath());
                        // Secondary trigger if observer missed it
                        if (detectionEngine != null) {
                            detectionEngine.triggerHoneyfileKill(honeyfile.getAbsolutePath(), -1);
                        }
                    }
                }
            }
        }
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

    // Generate stable hash
    // Persist name preferences
    private String calculateFileHash(File file) {
        if (!file.exists()) return null;
        try (java.io.InputStream is = new java.io.FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to calculate hash for: " + file.getAbsolutePath(), e);
            return null;
        }
    }

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
        // Per-file grace window
        private final long fileCreationTime;

        HoneyfileObserver(String path) {
            super(path, MODIFY | DELETE | CLOSE_WRITE);
            this.filePath = path;
            this.fileCreationTime = new File(path).lastModified();
        }

        public String getFilePath() {
            return filePath;
        }

        @Override
        public void onEvent(int event, @Nullable String path) {
            if (event == MODIFY || event == DELETE || event == CLOSE_WRITE) {
                String accessType = getAccessType(event);

                // Check individual grace
                long timeSinceCreation = System.currentTimeMillis() - fileCreationTime;
                if (timeSinceCreation < CREATION_GRACE_PERIOD_MS) {
                    Log.d(TAG, "Skipping honeyfile event during grace period: " + filePath + " (" + timeSinceCreation + "ms since creation)");
                    return;
                }
                
                // Log suspicious access
                int callingUid = android.os.Binder.getCallingUid(); // For informational purposes only
                HoneyfileEvent honeyEvent = new HoneyfileEvent(
                    filePath, accessType, callingUid, "uid:" + callingUid
                );
                storage.store(honeyEvent);
                Log.w(TAG, "⚠️ HONEYFILE TRAP TRIGGERED: " + filePath + " (" + accessType + ") by UID " + callingUid);

                // Immediate honeyfile kill (Verified by Hash if modified/written)
                if (detectionEngine != null) {
                    if (event == MODIFY || event == CLOSE_WRITE) {
                        // RE-VERIFY HASH
                        String currentHash = calculateFileHash(new File(filePath));
                        SharedPreferences prefs = context.getSharedPreferences("ShieldHoneyfiles", Context.MODE_PRIVATE);
                        String originalHash = prefs.getString("hash_" + filePath, null);
                        
                        if (currentHash != null && currentHash.equals(originalHash)) {
                            Log.d(TAG, "Honeyfile access detected but content HAS NOT changed (Hash match) - Skipping alert");
                            return; 
                        }
                        Log.w(TAG, "Honeyfile content CHANGED! (Old: " + originalHash + ", New: " + currentHash + ")");
                    }
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
