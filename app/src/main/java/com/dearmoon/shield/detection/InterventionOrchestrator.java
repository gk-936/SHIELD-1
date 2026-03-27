package com.dearmoon.shield.detection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.dearmoon.shield.R;
import com.dearmoon.shield.snapshot.SnapshotManager;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Orchestrates the framework's response to detected threats.
 * Separates the intervention UI/Logic from the analysis phase.
 */
public class InterventionOrchestrator {
    private static final String TAG = "InterventionOrchestrator";
    private final Context context;
    private final SnapshotManager snapshotManager;
    private final Executor killExecutor;
    private final Set<String> killInProgress = ConcurrentHashMap.newKeySet();

    // System packages that should NEVER be killed (Safety Guard)
    private static final java.util.List<String> SYSTEM_WHITELIST = java.util.Arrays.asList(
        "com.android.launcher",
        "com.android.launcher3",
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.googlequicksearchbox",
        "com.sec.android.app.launcher",   // Samsung TouchWiz/OneUI
        "com.oppo.launcher",              // Oplus/OnePlus/Oppo
        "com.miui.home"                  // Xiaomi/MIUI
    );

    public InterventionOrchestrator(Context context, SnapshotManager manager, Executor executor) {
        this.context = context;
        this.snapshotManager = manager;
        this.killExecutor = executor;
    }

    /**
     * Triggers the manual intervention flow for a high-risk process.
     */
    public void triggerManualIntervention(String packageName, int confidenceScore, int infectionTimeSec) {
        if (killInProgress.contains(packageName)) return;

        showHighRiskAlert("unknown", confidenceScore, infectionTimeSec, packageName, 0, 0, false);
    }

    /**
     * Executes the process termination and automated restoration sequence.
     */
    public void executeProcessKill(String packageName) {
        // If attribution failed, ask LockerShieldService to identify the active locker
        // from the window stack (the accessibility service can see all windows).
        if (packageName == null || packageName.equals("unknown") || packageName.isEmpty()) {
            com.dearmoon.shield.lockerguard.LockerShieldService locker =
                    com.dearmoon.shield.lockerguard.LockerShieldService.getInstance();
            if (locker != null) {
                String resolved = locker.resolveActiveLockerPackage();
                if (resolved != null && !resolved.isEmpty()) {
                    Log.w(TAG, "executeProcessKill: resolved unknown -> " + resolved);
                    packageName = resolved;
                } else {
                    // Critical Final Fallback: use the last known foreground app
                    String lastPkg = locker.getLastForegroundPackage();
                    if (lastPkg != null && !lastPkg.isEmpty()) {
                        Log.w(TAG, "executeProcessKill: package unknown and window inspection failed — using lastForegroundPackage fallback: " + lastPkg);
                        packageName = lastPkg;
                    } else {
                        Log.w(TAG, "executeProcessKill: package unknown and all identification fallbacks failed — aborting kill");
                        return;
                    }
                }
            } else {
                Log.w(TAG, "executeProcessKill: package unknown and LockerGuard unavailable — aborting kill");
                return;
            }
        }

        if (packageName.equals(context.getPackageName())) return;

        // Safety Guard (Check whitelist)
        if (SYSTEM_WHITELIST.contains(packageName)) {
            Log.w(TAG, "Safety Guard: Ignoring coordinated kill for protected system package: " + packageName);
            return;
        }

        if (killInProgress.contains(packageName)) return;
        killInProgress.add(packageName);

        final String targetPackage = packageName;
        killExecutor.execute(() -> {
            try {
                int pid = getPidForPackage(targetPackage);
                Log.w(TAG, "Executing coordinated kill for: " + targetPackage + " (pid: " + pid + ")");

                // Layer 1: Navigation
                attemptAccessibilityKill(targetPackage);

                // Layer 2: SIGKILL
                if (attemptModeAKill(targetPackage, pid)) {
                    awaitDeathAndRestore(targetPackage);
                    return;
                }

                // Layer 3: AppInfo deeplink (Manual intervention guidance)
                launchForceStopDeeplink(targetPackage);
                awaitDeathAndRestore(targetPackage);
            } finally {
                killInProgress.remove(targetPackage);
            }
        });
    }

    private boolean attemptAccessibilityKill(String packageName) {
        com.dearmoon.shield.lockerguard.LockerShieldService service =
                com.dearmoon.shield.lockerguard.LockerShieldService.getInstance();
        if (service != null) {
            service.performNavigationEscape();
            Log.i(TAG, "Layer 1: Accessibility navigation attempted for " + packageName);
            return true;
        }
        return false;
    }

    private boolean attemptModeAKill(String packageName, int pid) {
        if (com.dearmoon.shield.modea.ModeAService.isConnected()) {
            // Layer 2a: Weapon-First Stripping (Revoke permissions)
            Log.w(TAG, "Layer 2a: Stripping storage permissions for " + packageName);
            Intent revokeIntent = new Intent(com.dearmoon.shield.modea.ModeAService.ACTION_REVOKE_PERMISSIONS);
            revokeIntent.putExtra("package", packageName);
            context.sendBroadcast(revokeIntent, "com.dearmoon.shield.RESTART_PERMISSION");

            // Layer 2b: Deep Freeze (Suspend app)
            Log.w(TAG, "Layer 2b: Formally suspending " + packageName);
            Intent suspendIntent = new Intent(com.dearmoon.shield.modea.ModeAService.ACTION_SUSPEND_PACKAGE);
            suspendIntent.putExtra("package", packageName);
            context.sendBroadcast(suspendIntent, "com.dearmoon.shield.RESTART_PERMISSION");

            // Layer 2c: PID-based kill (Legacy/Native)
            if (pid > 0) {
                Intent killPidIntent = new Intent(com.dearmoon.shield.modea.ModeAService.ACTION_KILL_PID);
                killPidIntent.putExtra("pid", pid);
                killPidIntent.putExtra("package", packageName);
                context.sendBroadcast(killPidIntent, "com.dearmoon.shield.RESTART_PERMISSION");
            }

            // Layer 2d: Package-based force-stop (Reliable for modern Android)
            Intent forceStopIntent = new Intent(com.dearmoon.shield.modea.ModeAService.ACTION_FORCE_STOP_PACKAGE);
            forceStopIntent.putExtra("package", packageName);
            context.sendBroadcast(forceStopIntent, "com.dearmoon.shield.RESTART_PERMISSION");

            return waitForProcessDeath(packageName, 5000);
        }
        return false;
    }

    private void launchForceStopDeeplink(String packageName) {
        // Guard: if package is unknown we can't open meaningful app-info.
        // Doing so causes Android to fall back to the foreground app (the legitimate
        // app under the overlay), which confuses the user.
        if (packageName == null || packageName.equals("unknown") || packageName.isEmpty()) {
            Log.w(TAG, "launchForceStopDeeplink: package unknown — skipping app-info deeplink");
            // Show generic guidance overlay instead
            com.dearmoon.shield.ui.KillGuidanceOverlay.getInstance(context)
                    .show("unknown", "the ransomware app");
            return;
        }

        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.parse("package:" + packageName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        String appName = packageName;
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            appName = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception ignored) {}

        com.dearmoon.shield.lockerguard.LockerShieldService lockerService =
                com.dearmoon.shield.lockerguard.LockerShieldService.getInstance();
        if (lockerService != null) {
            lockerService.setCurrentSuspectPackage(packageName);
        }

        com.dearmoon.shield.ui.KillGuidanceOverlay.getInstance(context).show(packageName, appName);
    }

    private void awaitDeathAndRestore(String packageName) {
        long startTime = System.currentTimeMillis();
        try {
            while (System.currentTimeMillis() - startTime < 30000) {
                if (!isProcessRunning(packageName)) {
                    Log.i(TAG, "Process " + packageName + " is dead. Waiting for filesystem flush...");
                    // Safety Delay: Allow time for file-system buffers to flush
                    Thread.sleep(1000); 

                    Log.i(TAG, "Initiating restoration for " + packageName);
                    if (snapshotManager != null) {
                        snapshotManager.stopAttackTracking();
                        AppSecurityContextManager.getInstance().resetContext(getUidForPackage(packageName));
                        snapshotManager.performAutomatedRestore();
                    }
                    com.dearmoon.shield.ui.KillGuidanceOverlay.getInstance(context).dismiss();
                    showNotification("Data Integrity Restored", "Automated recovery complete.", 2003);
                    return;
                }
                Thread.sleep(300);
            }
            showNotification("Manual Stop Required", "Suspect process " + packageName + " is still running.", 2004);
        } catch (Exception ignored) {}
    }

    public void showHighRiskAlert(String filePath, int score, int infectionTimeSec, String suspectPackage, 
                                  int entropyScore, int kldScore, boolean sprtH1) {
        Log.w(TAG, "showHighRiskAlert: pkg=" + suspectPackage + " score=" + score);

        // Auto-kill immediately — don't rely on the user tapping the notification.
        if (suspectPackage != null
                && !suspectPackage.isEmpty()
                && !suspectPackage.equals(context.getPackageName())) {
            
            // Check if a kill/alert is already active for this package to avoid spam
            if (killInProgress.contains(suspectPackage)) {
                Log.d(TAG, "showHighRiskAlert: already alerting/killing " + suspectPackage);
                return;
            }

            // Record attack metadata broadcast for MainActivity/IncidentActivity
            Intent alertIntent = new Intent("com.dearmoon.shield.HIGH_RISK_ALERT");
            alertIntent.putExtra("file_path", filePath);
            alertIntent.putExtra("confidence_score", score);
            alertIntent.putExtra("infection_time", infectionTimeSec);
            alertIntent.putExtra("suspect_package", suspectPackage);
            alertIntent.putExtra("entropy_score", entropyScore);
            alertIntent.putExtra("kld_score", kldScore);
            alertIntent.putExtra("sprt_h1", sprtH1);
            context.sendBroadcast(alertIntent);
            
            executeProcessKill(suspectPackage);
            
            // Send exactly ONE notification (ID 2002) for this threat session
            sendAttackNotification(suspectPackage);
        }
    }

    private void sendAttackNotification(String suspectPackage) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        createChannel(nm);

        Intent killIntent = new Intent("com.dearmoon.shield.ACTION_INTERVENE_CRYPTO");
        killIntent.putExtra("package", suspectPackage);
        PendingIntent killPI = PendingIntent.getBroadcast(context, 0, killIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = suspectPackage != null && !suspectPackage.equals("unknown")
                ? "\u26a0\ufe0f ATTACK DETECTED: " + suspectPackage
                : "\u26a0\ufe0f RANSOMWARE ATTACK DETECTED";

        Notification n = new NotificationCompat.Builder(context, "shield_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText("Encryption detected. Stopping attacker automatically.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "PROTECT NOW", killPI)
            .build();

        nm.notify(2002, n);
    }

    private void createChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel("shield_alerts", "SHIELD Alerts", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(c);
        }
    }

    private void showNotification(String title, String text, int id) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(id, new NotificationCompat.Builder(context, "shield_alerts")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true).build());
        }
    }

    private boolean isProcessRunning(String pkg) {
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) return false;
        for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
            if (p.pkgList != null && java.util.Arrays.asList(p.pkgList).contains(pkg)) return true;
        }
        return false;
    }

    private int getPidForPackage(String pkg) {
        // Tier 1: Standard Android API
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.pkgList != null && java.util.Arrays.asList(p.pkgList).contains(pkg)) return p.pid;
                }
            }
        }
        
        // Tier 2: Root Fallback (pidof)
        if (com.dearmoon.shield.modea.ModeAService.isConnected()) {
            try {
                java.lang.Process p = com.dearmoon.shield.security.RootShell.execute("pidof " + pkg);
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    // pidof can return multiple PIDs space-separated; take the first
                    String firstPid = line.trim().split("\\s+")[0];
                    return Integer.parseInt(firstPid);
                }
            } catch (Exception e) {
                Log.w(TAG, "Root PID resolution failed: " + e.getMessage());
            }
        }
        
        return -1;
    }

    private int getUidForPackage(String pkg) {
        try { return context.getPackageManager().getPackageUid(pkg, 0); } catch (Exception e) { return -1; }
    }

    private boolean waitForProcessDeath(String pkg, long timeout) {
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            if (!isProcessRunning(pkg)) return true;
            try { Thread.sleep(300); } catch (Exception ignored) {}
        }
        return false;
    }
}
