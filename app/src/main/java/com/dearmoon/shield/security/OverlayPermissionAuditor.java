package com.dearmoon.shield.security;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.dearmoon.shield.data.EventDatabase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Audit overlay abuse
public final class OverlayPermissionAuditor {
    private static final String TAG = "OverlayPermissionAuditor";

    private OverlayPermissionAuditor() {}

    public static final class Result {
        public final int scanned;
        public final int requested;
        public final int allowed;
        public final int flagged;

        Result(int scanned, int requested, int allowed, int flagged) {
            this.scanned = scanned;
            this.requested = requested;
            this.allowed = allowed;
            this.flagged = flagged;
        }
    }

    public static Result auditInstalledApps(Context context) {
        Context appCtx = context.getApplicationContext();
        PackageManager pm = appCtx.getPackageManager();
        EventDatabase db = EventDatabase.getInstance(appCtx);

        List<PackageInfo> pkgs;
        try {
            pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
        } catch (Exception e) {
            Log.e(TAG, "getInstalledPackages failed", e);
            return new Result(0, 0, 0, 0);
        }

        Set<String> allow = defaultAllowlist();
        int scanned = 0, requested = 0, allowed = 0, flagged = 0;

        for (PackageInfo pi : pkgs) {
            if (pi == null || pi.packageName == null) continue;
            scanned++;

            boolean req = requestsOverlay(pi);
            if (!req) continue;
            requested++;

            // Filter system packages
            String pkg = pi.packageName;
            if (pkg.startsWith("com.android.") || pkg.startsWith("android.")
                    || pkg.startsWith("com.google.android.")) {
                continue;
            }
            if (allow.contains(pkg)) continue;

            int mode = queryOverlayAppOpMode(appCtx, pkg, pi.applicationInfo != null ? pi.applicationInfo.uid : -1);
            boolean isAllowed = (mode == AppOpsManager.MODE_ALLOWED);
            if (isAllowed) allowed++;

            String severity = isAllowed ? "FAIL" : "WARN";
            String detail = isAllowed
                    ? (pkg + " can draw overlays (allowed). High locker-risk if untrusted.")
                    : (pkg + " requests overlay permission. Verify it is legitimate.");

            try {
                db.insertConfigAuditEvent("OVERLAY_APP", severity, "FINDING", detail);
            } catch (Exception e) {
                Log.w(TAG, "Failed to persist overlay finding for " + pkg, e);
            }
            flagged++;
        }

        // Log audit summary
        try {
            db.insertConfigAuditEvent(
                    "OVERLAY_AUDIT",
                    flagged > 0 ? "WARN" : "PASS",
                    flagged > 0 ? "FINDING" : "PASS",
                    "Scanned=" + scanned + " requested=" + requested + " allowed=" + allowed + " flagged=" + flagged
            );
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist overlay audit summary", e);
        }

        Log.i(TAG, "Overlay audit: scanned=" + scanned + " requested=" + requested
                + " allowed=" + allowed + " flagged=" + flagged);
        return new Result(scanned, requested, allowed, flagged);
    }

    private static boolean requestsOverlay(PackageInfo pi) {
        String[] req = pi.requestedPermissions;
        if (req == null) return false;
        for (String p : req) {
            if ("android.permission.SYSTEM_ALERT_WINDOW".equals(p)) return true;
        }
        return false;
    }

    private static int queryOverlayAppOpMode(Context ctx, String packageName, int uid) {
        if (uid <= 0) return AppOpsManager.MODE_IGNORED;
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return AppOpsManager.MODE_IGNORED;

            // Prefer safe call paths; cross-app access may be restricted on some builds.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, packageName);
            }
            // Legacy fallback: may throw SecurityException depending on ROM.
            return aom.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, packageName);
        } catch (Throwable t) {
            return AppOpsManager.MODE_IGNORED;
        }
    }

    private static Set<String> defaultAllowlist() {
        // Minimal allowlist
        Set<String> s = new HashSet<>();
        // Legitimate overlay holders
        s.add("com.google.android.apps.nexuslauncher"); // some launchers
        s.add("com.microsoft.launcher");
        s.add("com.facebook.orca"); // chat heads
        s.add("com.whatsapp");
        // OEM / AOSP UI (varies)
        s.add("com.android.systemui");
        return s;
    }
}

