package com.dearmoon.shield;

import android.content.Context;
import android.content.SharedPreferences;

// Dashboard statistics storage
public class ShieldStats {

    private static final String PREFS_NAME  = "ShieldStats";

    // Keys
    private static final String KEY_FILES_SCANNED   = "files_scanned";
    private static final String KEY_ATTACKS_BLOCKED = "attacks_blocked";
    private static final String KEY_THREATS_FOUND   = "threats_found";
    private static final String KEY_SEEDED          = "seeded_v1";

    // Counters starts at 0
    // Real device scan activity
    private static final int SEED_FILES_SCANNED   = 0;
    private static final int SEED_ATTACKS_BLOCKED = 0;
    private static final int SEED_THREATS_FOUND   = 0;

    private final SharedPreferences prefs;

    public ShieldStats(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        seedIfFirstRun();
    }

    private void seedIfFirstRun() {
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            prefs.edit()
                .putInt(KEY_FILES_SCANNED, SEED_FILES_SCANNED)
                .putInt(KEY_ATTACKS_BLOCKED, SEED_ATTACKS_BLOCKED)
                .putInt(KEY_THREATS_FOUND, SEED_THREATS_FOUND)
                .putBoolean(KEY_SEEDED, true)
                .apply();
        }
    }

    // Getters

    public int getFilesScanned() {
        return prefs.getInt(KEY_FILES_SCANNED, 0);
    }

    public int getAttacksBlocked() {
        return prefs.getInt(KEY_ATTACKS_BLOCKED, 0);
    }

    public int getThreatsFound() {
        return prefs.getInt(KEY_THREATS_FOUND, 0);
    }

    // Incrementers

    public void incrementFilesScanned(int count) {
        int current = getFilesScanned();
        prefs.edit().putInt(KEY_FILES_SCANNED, current + count).apply();
    }

    public void incrementAttacksBlocked() {
        int current = getAttacksBlocked();
        prefs.edit().putInt(KEY_ATTACKS_BLOCKED, current + 1).apply();
    }

    public void incrementThreatsFound() {
        int current = getThreatsFound();
        prefs.edit().putInt(KEY_THREATS_FOUND, current + 1).apply();
    }

    // Record detection

    public void recordAttackDetected() {
        incrementAttacksBlocked();
        incrementThreatsFound();
    }
}
