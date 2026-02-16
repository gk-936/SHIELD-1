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
    private final ConcurrentHashMap<Integer, String> uidCache = new ConcurrentHashMap<>();
    
    public PackageAttributor(Context context) {
        this.packageManager = context.getPackageManager();
    }
    
    /**
     * Get package name for UID
     * EXTENDS: Existing UID capture with package name resolution
     */
    public String getPackageForUid(int uid) {
        // Check cache first
        if (uidCache.containsKey(uid)) {
            return uidCache.get(uid);
        }
        
        // Lookup package name
        String packageName = "unknown";
        try {
            String[] packages = packageManager.getPackagesForUid(uid);
            if (packages != null && packages.length > 0) {
                packageName = packages[0]; // Use first package
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve UID " + uid, e);
        }
        
        // Cache result
        uidCache.put(uid, packageName);
        Log.d(TAG, "UID " + uid + " → " + packageName);
        
        return packageName;
    }
    
    /**
     * Clear cache (call periodically to avoid memory leak)
     */
    public void clearCache() {
        uidCache.clear();
    }
}
