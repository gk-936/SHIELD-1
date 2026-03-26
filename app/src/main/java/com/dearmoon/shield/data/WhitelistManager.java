package com.dearmoon.shield.data;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.HashSet;
import java.util.Set;

public class WhitelistManager {
    private static final String PREF_NAME = "shield_whitelist";
    private static final String KEY_WHITELIST = "trusted_packages";
    private final SharedPreferences prefs;
    private final Context context;

    public WhitelistManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void addPackage(String packageName) {
        Set<String> whitelist = getWhitelist();
        whitelist.add(packageName);
        prefs.edit().putStringSet(KEY_WHITELIST, whitelist).apply();
    }

    public void removePackage(String packageName) {
        Set<String> whitelist = getWhitelist();
        whitelist.remove(packageName);
        prefs.edit().putStringSet(KEY_WHITELIST, whitelist).apply();
    }

    public Set<String> getWhitelist() {
        return new HashSet<>(prefs.getStringSet(KEY_WHITELIST, getDefaultWhitelist()));
    }

    public boolean isWhitelisted(String packageName) {
        if (packageName == null) return false;
        
        // Check whitelist cache
        if (getWhitelist().contains(packageName)) return true;
        
        // Check system apps
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || 
                (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Package not found
        } catch (Exception e) {
            // Whitelist check fallback
        }
        
        return false;
    }

    private Set<String> getDefaultWhitelist() {
        Set<String> defaults = new HashSet<>();
        defaults.add("com.android.systemui");
        defaults.add("com.google.android.apps.nexuslauncher");
        defaults.add("com.android.launcher3");
        defaults.add("com.android.launcher");
        defaults.add("com.android.settings");
        defaults.add("com.dearmoon.shield");
        
        // Trusted keyboard IMEs
        defaults.add("com.google.android.inputmethod.latin"); // Gboard
        defaults.add("com.android.inputmethod.latin");        // AOSP Keyboard
        defaults.add("com.samsung.android.honeyboard");       // Samsung Keyboard
        
        // Trusted large apps
        defaults.add("com.google.android.youtube");
        defaults.add("com.android.vending");                  // Play Store
        defaults.add("com.google.android.apps.docs");         // Drive
        defaults.add("com.google.android.gm");                // Gmail
        defaults.add("com.google.android.googlequicksearchbox"); // Google Search/Assistant
        
        // Add current launcher
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            PackageManager pm = context.getPackageManager();
            ResolveInfo res = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (res != null && res.activityInfo != null) {
                defaults.add(res.activityInfo.packageName);
            }
        } catch (Exception ignored) {}
        
        return defaults;
    }
}
