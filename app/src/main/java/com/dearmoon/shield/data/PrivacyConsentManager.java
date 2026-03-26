package com.dearmoon.shield.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

// Privacy management layer
public final class PrivacyConsentManager {

    private static final String TAG = "PrivacyConsentManager";

    // Privacy policy version
    public static final int POLICY_VERSION = 1;

    private static final String PREFS_FILE       = "shield_privacy";
    private static final String KEY_CONSENTED     = "user_consented";
    private static final String KEY_CONSENT_TIME  = "consent_timestamp";
    private static final String KEY_POLICY_VER    = "policy_version";

    private PrivacyConsentManager() { /* non-instantiable */ }

    // Check user consent

    // Check user consent
    public static boolean hasConsent(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        boolean consented    = prefs.getBoolean(KEY_CONSENTED, false);
        int     policyVer    = prefs.getInt(KEY_POLICY_VER, -1);
        return consented && (policyVer >= POLICY_VERSION);
    }

    // Record consent choice
    public static void recordConsent(Context ctx, boolean accepted) {
        long now = System.currentTimeMillis();
        prefs(ctx).edit()
                .putBoolean(KEY_CONSENTED,    accepted)
                .putLong(KEY_CONSENT_TIME,    now)
                .putInt(KEY_POLICY_VER,       POLICY_VERSION)
                .apply();

        Log.i(TAG, "Consent recorded: accepted=" + accepted + " policyVersion=" + POLICY_VERSION);

        // Audit consent decision
        try {
            EventDatabase db = EventDatabase.getInstance(ctx);
            JSONObject detail = new JSONObject();
            detail.put("accepted",      accepted);
            detail.put("policyVersion", POLICY_VERSION);
            detail.put("timestamp",     now);
            db.insertPrivacyConsentEvent(
                    accepted ? "CONSENT_GRANTED" : "CONSENT_DENIED",
                    "POLICY_V" + POLICY_VERSION,
                    detail.toString()
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to write consent audit row", e);
        }
    }

    // Get consent timestamp
    public static long getConsentTimestamp(Context ctx) {
        return prefs(ctx).getLong(KEY_CONSENT_TIME, -1L);
    }

    // Get telemetry summary

    // Get event counts
    public static Map<String, Long> getTelemetrySummary(Context ctx) {
        Map<String, Long> summary = new LinkedHashMap<>();
        try {
            EventDatabase db = EventDatabase.getInstance(ctx);
            summary.put("File system events",  db.countEvents("file_system_events"));
            summary.put("Network events",       db.countEvents("network_events"));
            summary.put("Honeyfile events",     db.countEvents("honeyfile_events"));
            summary.put("Detection results",    db.countEvents("detection_results"));
            summary.put("Locker events",        db.countEvents("locker_shield_events"));
            summary.put("Correlation results",  db.countEvents("correlation_results"));
            summary.put("Integrity events",     db.countEvents("integrity_events"));
            summary.put("Config audit events",  db.countEvents("config_audit_events"));
        } catch (Exception e) {
            Log.e(TAG, "getTelemetrySummary failed", e);
        }
        return summary;
    }

    // Purge all telemetry

    // Purge all telemetry
    public static int purgeAllTelemetry(Context ctx) {
        int total = 0;
        try {
            EventDatabase db = EventDatabase.getInstance(ctx);
            total += db.purgeTable("file_system_events");
            total += db.purgeTable("network_events");
            total += db.purgeTable("honeyfile_events");
            total += db.purgeTable("detection_results");
            total += db.purgeTable("locker_shield_events");
            total += db.purgeTable("correlation_results");
            total += db.purgeTable("config_audit_events");

            Log.i(TAG, "Telemetry purge completed: " + total + " rows deleted");

            // Audit telemetry purge
            try {
                JSONObject detail = new JSONObject();
                detail.put("rows_deleted", total);
                detail.put("timestamp",    System.currentTimeMillis());
                db.insertPrivacyConsentEvent(
                        "TELEMETRY_PURGE",
                        "USER_INITIATED",
                        detail.toString()
                );
            } catch (Exception e) {
                Log.e(TAG, "Failed to write purge audit row", e);
            }

        } catch (Exception e) {
            Log.e(TAG, "purgeAllTelemetry failed", e);
        }
        return total;
    }

    // Privacy helper methods

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                  .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }
}
