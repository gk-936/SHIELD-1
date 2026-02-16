package com.dearmoon.shield.collectors;

import android.content.Context;
import android.os.FileObserver;
import android.util.Log;
import androidx.annotation.Nullable;
import com.dearmoon.shield.data.HoneyfileEvent;
import com.dearmoon.shield.data.TelemetryStorage;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class HoneyfileCollector {
    private static final String TAG = "HoneyfileCollector";
    private final TelemetryStorage storage;
    private final List<HoneyfileObserver> observers = new ArrayList<>();
    private final List<File> honeyfiles = new ArrayList<>();
    private final int appUid;
    private final String appPackageName;

    public HoneyfileCollector(TelemetryStorage storage, Context context) {
        this.storage = storage;
        this.appUid = android.os.Process.myUid();
        this.appPackageName = context.getPackageName();
        Log.i(TAG, "HoneyfileCollector initialized - App UID: " + appUid + ", Package: " + appPackageName);
    }

    public void createHoneyfiles(Context context, String[] directories) {
        String[] honeyfileNames = {
            "IMPORTANT_BACKUP.txt",
            "PRIVATE_KEYS.dat",
            "CREDENTIALS.txt",
            "SECURE_VAULT.bin",
            "FINANCIAL_DATA.xlsx",
            "PASSWORDS.txt"
        };
        
        for (String dir : directories) {
            File directory = new File(dir);
            if (!directory.exists()) continue;

            for (String name : honeyfileNames) {
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

    private class HoneyfileObserver extends FileObserver {
        private final String filePath;

        HoneyfileObserver(String path) {
            super(path, OPEN | MODIFY | DELETE | CLOSE_WRITE);
            this.filePath = path;
        }

        @Override
        public void onEvent(int event, @Nullable String path) {
            if (event == OPEN || event == MODIFY || event == DELETE || event == CLOSE_WRITE) {
                String accessType = getAccessType(event);
                int callingUid = android.os.Binder.getCallingUid();
                
                // Prevent self-logging: Skip if event is from our own app
                if (callingUid == appUid) {
                    Log.d(TAG, "Skipping self-generated honeyfile event (UID match): " + filePath);
                    return;
                }
                
                HoneyfileEvent honeyEvent = new HoneyfileEvent(
                    filePath, accessType, callingUid, "uid:" + callingUid
                );
                storage.store(honeyEvent);
                Log.w(TAG, "⚠️ HONEYFILE TRAP TRIGGERED: " + filePath + " (" + accessType + ") by UID " + callingUid);
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
