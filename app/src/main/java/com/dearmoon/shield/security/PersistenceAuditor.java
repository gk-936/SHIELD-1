package com.dearmoon.shield.security;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;

import com.dearmoon.shield.data.EventDatabase;

import java.util.List;

/**
 * Best-effort persistence audit for a suspect package.
 *
 * Locker ransomware commonly registers BOOT_COMPLETED receivers to restart on reboot.
 * This auditor checks whether the suspect package has a BOOT_COMPLETED receiver (enabled or disabled)
 * and logs a finding into config_audit_events.
 */
public final class PersistenceAuditor {
    private static final String TAG = "PersistenceAuditor";

    private PersistenceAuditor() {}

    public static void auditBootPersistence(Context context, String suspectPackage) {
        if (suspectPackage == null || suspectPackage.isEmpty() || "unknown".equals(suspectPackage)) return;
        Context appCtx = context.getApplicationContext();
        EventDatabase db = EventDatabase.getInstance(appCtx);
        PackageManager pm = appCtx.getPackageManager();

        boolean hasBootReceiver = false;
        int count = 0;
        try {
            Intent boot = new Intent(Intent.ACTION_BOOT_COMPLETED);
            int flags = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                flags |= PackageManager.MATCH_DISABLED_COMPONENTS;
            }
            List<ResolveInfo> receivers = pm.queryBroadcastReceivers(boot, flags);
            if (receivers != null) {
                for (ResolveInfo ri : receivers) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (suspectPackage.equals(pkg)) {
                        hasBootReceiver = true;
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "BOOT_COMPLETED query failed", e);
        }

        String severity = hasBootReceiver ? "WARN" : "PASS";
        String resultType = hasBootReceiver ? "FINDING" : "PASS";
        String detail = hasBootReceiver
                ? (suspectPackage + " declares BOOT_COMPLETED receivers (count=" + count + "). Revoke overlay + uninstall to prevent re-lock.")
                : (suspectPackage + " has no BOOT_COMPLETED receiver detected.");

        try {
            db.insertConfigAuditEvent("BOOT_PERSISTENCE", severity, resultType, detail);
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist boot persistence finding", e);
        }
    }
}

