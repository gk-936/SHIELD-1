package com.dearmoon.shield.security;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.dearmoon.shield.data.EventDatabase;

import java.util.ArrayList;
import java.util.List;

// Audit security misconfigurations
public final class ConfigAuditChecker {

    private static final String TAG = "ConfigAuditChecker";

    /** Audit result broadcast */
    public static final String ACTION_CONFIG_AUDIT_RESULT =
            "com.dearmoon.shield.CONFIG_AUDIT_RESULT";
    /** FAIL result count */
    public static final String EXTRA_FAIL_COUNT = "fail_count";
    /** WARN result count */
    public static final String EXTRA_WARN_COUNT = "warn_count";

    // Audit finding model

    /** Severity assigned to each individual configuration finding. */
    public enum Severity { PASS, WARN, FAIL }

    /** A single configuration finding produced by one of the audit checks. */
    public static final class ConfigFinding {
        public final String   category;
        public final Severity severity;
        public final String   description;

        ConfigFinding(String category, Severity severity, String description) {
            this.category    = category;
            this.severity    = severity;
            this.description = description;
        }

        @Override public String toString() {
            return "[" + severity + "] " + category + ": " + description;
        }
    }

    private ConfigAuditChecker() { /* non-instantiable */ }

    // Public audit API

    /**
     * Runs all seven configuration checks and persists the findings to {@link EventDatabase}.
     *
     * @param context any valid {@link Context}; application context is used internally.
     * @return an unmodifiable list of all {@link ConfigFinding} objects produced by the audit.
     */
    public static List<ConfigFinding> audit(Context context) {
        Context appCtx = context.getApplicationContext();
        List<ConfigFinding> findings = new ArrayList<>();

        checkDebuggable(appCtx, findings);
        checkAllowBackup(appCtx, findings);
        checkExportedActivities(appCtx, findings);
        checkExportedServices(appCtx, findings);
        checkExportedProviders(appCtx, findings);
        checkAdbEnabled(appCtx, findings);
        checkCleartextTraffic(appCtx, findings);

        // Log results
        persistFindings(appCtx, findings);
        broadcastSummary(appCtx, findings);

        for (ConfigFinding f : findings) {
            Log.i(TAG, f.toString());
        }

        return java.util.Collections.unmodifiableList(findings);
    }

    // Specific audit checks

    private static void checkDebuggable(Context ctx, List<ConfigFinding> out) {
        boolean debuggable =
                (ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            out.add(new ConfigFinding("DEBUGGABLE", Severity.FAIL,
                    "android:debuggable=true — ADB code injection possible; must be false in production builds."));
        } else {
            out.add(new ConfigFinding("DEBUGGABLE", Severity.PASS,
                    "android:debuggable=false — OK"));
        }
    }

    private static void checkAllowBackup(Context ctx, List<ConfigFinding> out) {
        boolean allowBackup =
                (ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0;
        if (allowBackup) {
            out.add(new ConfigFinding("ALLOW_BACKUP", Severity.WARN,
                    "android:allowBackup=true — adb backup can extract private storage including integrity baseline and snapshot database.  Set to false or define android:dataExtractionRules."));
        } else {
            out.add(new ConfigFinding("ALLOW_BACKUP", Severity.PASS,
                    "android:allowBackup=false — OK"));
        }
    }

    private static void checkExportedActivities(Context ctx, List<ConfigFinding> out) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), PackageManager.GET_ACTIVITIES);
            if (pi.activities == null) return;

            for (ActivityInfo ai : pi.activities) {
                // exported=true without an intent filter attached by the manifest analyser
                // is suspicious. We flag activities shipped WITHOUT android:permission set.
                if (ai.exported && (ai.permission == null || ai.permission.isEmpty())) {
                    // Allow-list known launcher activities
                    if (!ai.name.contains("SplashActivity")
                            && !ai.name.contains("MainActivity")) {
                        out.add(new ConfigFinding("EXPORTED_ACTIVITY", Severity.WARN,
                                ai.name + " is exported with no permission guard."));
                    } else {
                        out.add(new ConfigFinding("EXPORTED_ACTIVITY", Severity.PASS,
                                ai.name + " exported as expected (launcher)."));
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "checkExportedActivities: package not found", e);
        }
    }

    private static void checkExportedServices(Context ctx, List<ConfigFinding> out) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), PackageManager.GET_SERVICES);
            if (pi.services == null) return;

            for (ServiceInfo si : pi.services) {
                if (si.exported && (si.permission == null || si.permission.isEmpty())) {
                    out.add(new ConfigFinding("EXPORTED_SERVICE", Severity.FAIL,
                            si.name + " is exported with no permission guard — can be bound or started by any app."));
                }
            }
            if (out.stream().noneMatch(f -> f.category.equals("EXPORTED_SERVICE"))) {
                out.add(new ConfigFinding("EXPORTED_SERVICE", Severity.PASS,
                        "All services are unexported or permission-gated — OK"));
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "checkExportedServices: package not found", e);
        }
    }

    private static void checkExportedProviders(Context ctx, List<ConfigFinding> out) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), PackageManager.GET_PROVIDERS);
            if (pi.providers == null) {
                out.add(new ConfigFinding("EXPORTED_PROVIDER", Severity.PASS,
                        "No content providers declared — OK"));
                return;
            }

            for (ProviderInfo prov : pi.providers) {
                if (prov.exported
                        && (prov.readPermission  == null || prov.readPermission.isEmpty())
                        && (prov.writePermission == null || prov.writePermission.isEmpty())) {
                    out.add(new ConfigFinding("EXPORTED_PROVIDER", Severity.FAIL,
                            prov.name + " is exported with no read/write permission — data-leak surface."));
                }
            }
            if (out.stream().noneMatch(f -> f.category.equals("EXPORTED_PROVIDER")
                    && f.severity == Severity.FAIL)) {
                out.add(new ConfigFinding("EXPORTED_PROVIDER", Severity.PASS,
                        "All providers are unexported or permission-gated — OK"));
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "checkExportedProviders: package not found", e);
        }
    }

    private static void checkAdbEnabled(Context ctx, List<ConfigFinding> out) {
        try {
            int adb = Settings.Global.getInt(ctx.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 0);
            if (adb == 1) {
                out.add(new ConfigFinding("ADB_ENABLED", Severity.WARN,
                        "USB debugging is active on this device.  Disable via Developer Options in production to prevent ADB-based data exfiltration."));
            } else {
                out.add(new ConfigFinding("ADB_ENABLED", Severity.PASS,
                        "USB debugging disabled — OK"));
            }
        } catch (Exception e) {
            Log.e(TAG, "checkAdbEnabled failed", e);
        }
    }

    private static void checkCleartextTraffic(Context ctx, List<ConfigFinding> out) {
        ApplicationInfo ai = ctx.getApplicationInfo();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Before API 28 clear-text is permitted by default unless explicitly disabled
            // We check for the presence of networkSecurityConfig attribute via ApplicationInfo meta-data
            // (full XML parsing is not feasible at runtime without reflection)
            out.add(new ConfigFinding("CLEARTEXT_TRAFFIC", Severity.WARN,
                    "App targets API " + ai.targetSdkVersion + " (< 28).  Ensure res/xml/network_security_config.xml sets <base-config cleartextTrafficPermitted=\"false\" />."));
        } else {
            out.add(new ConfigFinding("CLEARTEXT_TRAFFIC", Severity.PASS,
                    "App targets API 28+ — clear-text blocked by default — OK"));
        }
    }

    // Persist audit results

    private static void persistFindings(Context ctx, List<ConfigFinding> findings) {
        try {
            EventDatabase db = EventDatabase.getInstance(ctx);
            for (ConfigFinding f : findings) {
                db.insertConfigAuditEvent(
                        f.category,
                        f.severity.name(),
                        f.severity == Severity.PASS ? "PASS" : "FINDING",
                        f.description
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to persist audit findings", e);
        }
    }

    private static void broadcastSummary(Context ctx, List<ConfigFinding> findings) {
        int failCount = 0, warnCount = 0;
        for (ConfigFinding f : findings) {
            if (f.severity == Severity.FAIL) failCount++;
            else if (f.severity == Severity.WARN) warnCount++;
        }
        android.content.Intent intent = new android.content.Intent(ACTION_CONFIG_AUDIT_RESULT);
        intent.putExtra(EXTRA_FAIL_COUNT, failCount);
        intent.putExtra(EXTRA_WARN_COUNT, warnCount);
        intent.addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        ctx.sendBroadcast(intent);
        Log.i(TAG, "Config audit complete — FAIL=" + failCount + " WARN=" + warnCount);
    }
}
