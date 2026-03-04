package com.dearmoon.shield.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M6 — Inadequate Privacy Controls
 *
 * <p>Manages user consent and data minimisation for all telemetry SHIELD collects. Three
 * responsibilities:
 *
 * <ol>
 *   <li><strong>Consent gating:</strong> Tracks whether the user has explicitly accepted the
 *       data-collection disclosure. Call {@link #hasConsent(Context)} before starting collectors;
 *       call {@link #recordConsent(Context, boolean)} from the consent dialog callback. Consent
 *       decisions are persisted in a private {@link SharedPreferences} file
 *       {@code shield_privacy} with a version stamp so future policy changes can re-prompt the
 *       user.
 *   <li><strong>Telemetry summary:</strong> {@link #getTelemetrySummary(Context)} returns a
 *       human-readable map of how many events of each type are stored, used for the privacy
 *       disclosure UI in {@code SettingsActivity}.
 *   <li><strong>Purge:</strong> {@link #purgeAllTelemetry(Context)} deletes every row from every
 *       telemetry table in {@link EventDatabase} and logs the purge action to the
 *       {@code privacy_consent_events} audit table. The audit table itself is intentionally
 *       excluded from purges so there is always a record that a purge occurred.
 * </ol>
 *
 * <p>Consent records and purge events are also written to {@link EventDatabase} so they appear in
 * the audit trail alongside other security events.
 */
public final class PrivacyConsentManager {

    private static final String TAG = "PrivacyConsentManager";

    /** Current version of the privacy policy. Bump this to re-prompt users after policy changes. */
    public static final int POLICY_VERSION = 1;

    private static final String PREFS_FILE       = "shield_privacy";
    private static final String KEY_CONSENTED     = "user_consented";
    private static final String KEY_CONSENT_TIME  = "consent_timestamp";
    private static final String KEY_POLICY_VER    = "policy_version";

    private PrivacyConsentManager() { /* non-instantiable */ }

    // -------------------------------------------------------------------------
    // Consent gating
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the user has given consent for the current policy version.
     *
     * <p>Returns {@code false} if:
     * <ul>
     *   <li>Consent has never been recorded, or
     *   <li>Consent was recorded for an older policy version than {@link #POLICY_VERSION}.
     * </ul>
     */
    public static boolean hasConsent(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        boolean consented    = prefs.getBoolean(KEY_CONSENTED, false);
        int     policyVer    = prefs.getInt(KEY_POLICY_VER, -1);
        return consented && (policyVer >= POLICY_VERSION);
    }

    /**
     * Records the user's consent decision.
     *
     * @param ctx       any valid {@link Context}.
     * @param accepted  {@code true} if the user accepted; {@code false} if they declined.
     */
    public static void recordConsent(Context ctx, boolean accepted) {
        long now = System.currentTimeMillis();
        prefs(ctx).edit()
                .putBoolean(KEY_CONSENTED,    accepted)
                .putLong(KEY_CONSENT_TIME,    now)
                .putInt(KEY_POLICY_VER,       POLICY_VERSION)
                .apply();

        Log.i(TAG, "Consent recorded: accepted=" + accepted + " policyVersion=" + POLICY_VERSION);

        // Audit trail
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

    /**
     * Returns the Unix timestamp (ms) at which the user last gave or denied consent, or
     * {@code -1} if no consent decision has been recorded yet.
     */
    public static long getConsentTimestamp(Context ctx) {
        return prefs(ctx).getLong(KEY_CONSENT_TIME, -1L);
    }

    // -------------------------------------------------------------------------
    // Telemetry summary
    // -------------------------------------------------------------------------

    /**
     * Returns a {@link LinkedHashMap} of {@code tableLabel -> eventCount} for every telemetry
     * table SHIELD stores data in, ordered from most privacy-sensitive to least.
     *
     * <p>Intended use: display this map in a "What data SHIELD stores" screen inside
     * {@code SettingsActivity}.
     */
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

    // -------------------------------------------------------------------------
    // Purge
    // -------------------------------------------------------------------------

    /**
     * Deletes all rows from every telemetry table.  The {@code privacy_consent_events} and
     * {@code integrity_events} audit tables are NOT purged so there is always a forensic record
     * that a purge took place.
     *
     * @return the total number of rows deleted across all tables.
     */
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

            // Audit the purge itself
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                  .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }
}
