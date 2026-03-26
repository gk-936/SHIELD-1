package com.dearmoon.shield.detection;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Package Attributor layer
public class PackageAttributor {
    private static final String TAG = "PackageAttributor";
    private final Context context;
    private final PackageManager packageManager;
    private final ConcurrentHashMap<Integer, AppInfo> uidCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pathUidCache = new ConcurrentHashMap<>();
    
    public static class AppInfo {
        public final String packageName;
        public final String appLabel;

        public AppInfo(String packageName, String appLabel) {
            this.packageName = packageName;
            this.appLabel = appLabel;
        }
    }

    public PackageAttributor(Context context) {
        this.context = context;
        this.packageManager = context.getPackageManager();
    }
    
    // Get UID app info
    public AppInfo getAppInfoForUid(int uid) {
        if (uid < 0) return new AppInfo("unknown", "System/Unknown");
        if (uid == 0) return new AppInfo("root", "Root/Kernel");
        if (uid < 1000) return new AppInfo("system_service", "OS Service/Internal (" + uid + ")");
        if (uid == 1000) return new AppInfo("android", "System Server");
        if (uid < 2000) return new AppInfo("system_process", "OS Process (" + uid + ")");

        // Check UID cache
        if (uidCache.containsKey(uid)) {
            return uidCache.get(uid);
        }
        
        // Lookup package name
        String packageName = "unknown";
        String appLabel = "Unknown App (" + uid + ")";

        try {
            String[] packages = packageManager.getPackagesForUid(uid);
            if (packages != null && packages.length > 0) {
                packageName = packages[0]; // Use first package

                try {
                    android.content.pm.ApplicationInfo ai = packageManager.getApplicationInfo(packageName, 0);
                    appLabel = packageManager.getApplicationLabel(ai).toString();

                    // Handle multi-package UIDs
                    if (packages.length > 1) {
                        appLabel += " (+" + (packages.length - 1) + ")";
                    }
                } catch (Exception e) {
                    appLabel = packageName;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve UID " + uid, e);
        }
        
        AppInfo info = new AppInfo(packageName, appLabel);
        // Cache UID info
        uidCache.put(uid, info);
        Log.d(TAG, "UID " + uid + " → " + packageName + " (" + appLabel + ")");
        
        return info;
    }

    @Deprecated
    public String getPackageForUid(int uid) {
        return getAppInfoForUid(uid).packageName;
    }
    
    // Resolve path UID
    public int resolveUidFromPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) return -1;
        
        // Check cache
        if (pathUidCache.containsKey(filePath)) {
            return pathUidCache.get(filePath);
        }

        int uid = -1;

        // Strategy 1: Private storage
        String pkg = extractPackageFromPrivatePath(filePath);
        if (pkg != null) {
            uid = getUidForPackage(pkg);
        }

        // Shared storage strategy
        if (uid == -1) {
            pkg = extractPackageFromSharedStoragePath(filePath);
            if (pkg != null) {
                uid = getUidForPackage(pkg);
            }
        }

        // Foreground heuristic strategy
        if (uid == -1) {
            uid = getForegroundUid();
        }

        if (uid != -1) {
            pathUidCache.put(filePath, uid);
        } else {
            Log.w(TAG, "Attribution failed for path: " + filePath);
        }
        return uid;
    }

    private int getUidForPackage(String packageName) {
        try {
            ApplicationInfo ai = packageManager.getApplicationInfo(packageName, 0);
            return ai.uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private String extractPackageFromPrivatePath(String filePath) {
        // /data/data/<pkg>/... or /data/user/N/<pkg>/...
        Matcher m = Pattern.compile("^/data/(?:data|user/\\d+)/([a-zA-Z][a-zA-Z0-9_.]+)(?:/|$)").matcher(filePath);
        if (m.find()) return m.group(1);
        return null;
    }

    private String extractPackageFromSharedStoragePath(String filePath) {
        // Shared path pattern
        // Standard Android directories
        Matcher m = Pattern.compile(
            "(?:/storage/emulated/\\d+|/sdcard|/storage/[^/]+|/data/media/\\d+)/Android/(?:data|obb)/([a-zA-Z][a-zA-Z0-9_.]+)(?:/|$)"
        ).matcher(filePath);
        if (m.find()) {
            String pkg = m.group(1);
            Log.d(TAG, "Extracted package '" + pkg + "' from shared path: " + filePath);
            return pkg;
        }
        return null;
    }

    private int getForegroundUid() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo proc : procs) {
                        if (proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                            && !proc.processName.equals(context.getPackageName())) {
                            return proc.uid;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Foreground lookup failed", e);
        }
        return -1;
    }

    // Clear attribution cache
    public void clearCache() {
        uidCache.clear();
        pathUidCache.clear();
    }
}
