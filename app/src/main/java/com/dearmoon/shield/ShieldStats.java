package com.dearmoon.shield;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ShieldStats — Task 3
 *
 * Central persistent counter store for the dashboard stats card.
 * Pre-seeded with real numbers from AndMal2020 / CIC-MalDroid test runs.
 * Persists across sessions so the dashboard never shows zeros.
 */
public class ShieldStats {

    private static final String PREFS_NAME  = "ShieldStats";

    // Keys
    private static final String KEY_FILES_SCANNED   = "files_scanned";
    private static final String KEY_ATTACKS_BLOCKED = "attacks_blocked";
    private static final String KEY_THREATS_FOUND   = "threats_found";
    private static final String KEY_SEEDED          = "seeded_v1";

    // All counters start at 0 — they reflect real device scan activity only.
    // The 14,098 figure was from the CICMalDroid-2020 validation run and is
    // not representative of any individual device's scan history.
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

    // ── Getters ──────────────────────────────────────────────────────────────

    public int getFilesScanned() {
        return prefs.getInt(KEY_FILES_SCANNED, 0);
    }

    public int getAttacksBlocked() {
        return prefs.getInt(KEY_ATTACKS_BLOCKED, 0);
    }

    public int getThreatsFound() {
        return prefs.getInt(KEY_THREATS_FOUND, 0);
    }

    // ── Incrementers ─────────────────────────────────────────────────────────

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

    // ── Called when demo/real detection fires ────────────────────────────────

    public void recordAttackDetected() {
        incrementAttacksBlocked();
        incrementThreatsFound();
    }
}
