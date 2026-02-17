package com.dearmoon.shield.detection;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pseudo-Kernel Detection Layer: Package Attributor
 * 
 * REUSE STRATEGY:
 * - Uses existing UID fields from NetworkEvent, HoneyfileEvent
 * - Adds PackageManager lookup (missing functionality)
 * - Caches results for performance
 */
public class PackageAttributor {
    private static final String TAG = "PackageAttributor";
    private final PackageManager packageManager;
    private final ConcurrentHashMap<Integer, AppInfo> uidCache = new ConcurrentHashMap<>();
    
    public static class AppInfo {
        public final String packageName;
        public final String appLabel;

        public AppInfo(String packageName, String appLabel) {
            this.packageName = packageName;
            this.appLabel = appLabel;
        }
    }

    public PackageAttributor(Context context) {
        this.packageManager = context.getPackageManager();
    }
    
    /**
     * Get package name and app label for UID
     */
    public AppInfo getAppInfoForUid(int uid) {
        if (uid < 0) return new AppInfo("unknown", "System/Unknown");
        if (uid == 0) return new AppInfo("root", "Root/Kernel");
        if (uid == 1000) return new AppInfo("android", "System Server");

        // Check cache first
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
        // Cache result
        uidCache.put(uid, info);
        Log.d(TAG, "UID " + uid + " → " + packageName + " (" + appLabel + ")");
        
        return info;
    }

    @Deprecated
    public String getPackageForUid(int uid) {
        return getAppInfoForUid(uid).packageName;
    }
    
    /**
     * Clear cache (call periodically to avoid memory leak)
     */
    public void clearCache() {
        uidCache.clear();
    }
}
