package com.dearmoon.shield.data;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

public class WhitelistManager {
    private static final String PREF_NAME = "shield_whitelist";
    private static final String KEY_WHITELIST = "trusted_packages";
    private final SharedPreferences prefs;

    public WhitelistManager(Context context) {
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
        return getWhitelist().contains(packageName);
    }

    private Set<String> getDefaultWhitelist() {
        Set<String> defaults = new HashSet<>();
        defaults.add("com.android.systemui");
        defaults.add("com.google.android.apps.nexuslauncher");
        defaults.add("com.android.launcher3");
        defaults.add("com.dearmoon.shield");
        return defaults;
    }
}
