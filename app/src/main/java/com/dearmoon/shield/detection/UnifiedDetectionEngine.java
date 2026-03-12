package com.dearmoon.shield.detection;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import com.dearmoon.shield.data.FileSystemEvent;
import com.dearmoon.shield.data.EventDatabase;
import com.dearmoon.shield.data.WhitelistManager;
import java.io.File;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UnifiedDetectionEngine {
    private static final String TAG = "UnifiedDetectionEngine";

    private final Context context;
    private final EventDatabase database;
    private final EntropyAnalyzer entropyAnalyzer;
    private final KLDivergenceCalculator klCalculator;
    private final SPRTDetector sprtDetector;
    private final BehaviorCorrelationEngine correlationEngine;
    private final WhitelistManager whitelistManager;
    private com.dearmoon.shield.snapshot.SnapshotManager snapshotManager;

    private final HandlerThread detectionThread;
    private final Handler detectionHandler;

    private final ConcurrentLinkedQueue<Long> recentModifications = new ConcurrentLinkedQueue<>();
    private long lastEventTimestamp = 0;
    private static final long TIME_WINDOW_MS = 1000; // 1 second window

    public UnifiedDetectionEngine(Context context) {
        this(context, null);
    }

    public UnifiedDetectionEngine(Context context, com.dearmoon.shield.snapshot.SnapshotManager snapshotManager) {
        this.context = context;
        this.database = EventDatabase.getInstance(context);
        this.entropyAnalyzer = new EntropyAnalyzer();
        this.klCalculator = new KLDivergenceCalculator();
        this.sprtDetector = new SPRTDetector();
        this.correlationEngine = new BehaviorCorrelationEngine(context);
        this.whitelistManager = new WhitelistManager(context);
        this.snapshotManager = snapshotManager;

        detectionThread = new HandlerThread("DetectionThread");
        detectionThread.start();
        detectionHandler = new Handler(detectionThread.getLooper());

        // Initialise lastEventTimestamp to now so the SPRT's first call to
        // recordTimePassed() receives a real inter-event delta rather than being
        // skipped by the `lastEventTimestamp > 0` guard.  Without this, the very
        // first MODIFY event fires recordEvent() alone, pushing the log-likelihood
        // ratio above the ACCEPT_H1 boundary on event #1 every session.
        lastEventTimestamp = System.currentTimeMillis();
    }

    public void setSnapshotManager(com.dearmoon.shield.snapshot.SnapshotManager manager) {
        this.snapshotManager = manager;
    }

    public void processFileEvent(FileSystemEvent event) {
        detectionHandler.post(() -> analyzeFileEvent(event));
    }

    private void analyzeFileEvent(FileSystemEvent event) {
        try {
            String operation = event.toJSON().optString("operation", "");
            String filePath = event.toJSON().optString("filePath", "");

            Log.d(TAG, "Analyzing file event: " + operation + " on " + filePath);

            // Resolve the UID of the process that modified this file using three strategies
            // in priority order:
            //   1. Path-based attribution: parse /data/data/<pkg>/ or /data/user/N/<pkg>/ to
            //      determine the owning package deterministically via PackageManager.
            //   2. Shared-storage path attribution: parse Android/data/<pkg>/ prefix from
            //      /sdcard/ or /storage/emulated/N/ paths.
            //   3. Foreground-process heuristic: query ActivityManager for the current
            //      foreground non-SHIELD process as a time-correlated best guess.
            // Returns -1 (unattributed) if none of the above yields a confident answer.
            // LIMITATION: FileObserver does not expose the writing PID/UID at the kernel
            // level. Attribution for files in arbitrary shared-storage locations without a
            // package-name path segment is inherently ambiguous without root or eBPF.
            int attributedUid = resolveAttributionUid(filePath);
            CorrelationResult correlation = correlationEngine.correlateFileEvent(
                filePath, event.getTimestamp(), attributedUid,
                sprtDetector.getLastResetTimestamp());  // M-02: bound file query by SPRT reset
            String suspectPackageName = correlation.getPackageName();

            // Check Whitelist
            if (whitelistManager.isWhitelisted(suspectPackageName)) {
                Log.i(TAG, "Skipping analysis for whitelisted app: " + suspectPackageName);
                return;
            }

            // Persist the raw file-system event for UI display and audit trail.
            // Mode-B does this via TelemetryStorage; Mode-A events arrive here
            // directly, so we must write to the DB ourselves.
            try {
                database.insertFileSystemEvent(event);
            } catch (Exception e) {
                Log.e(TAG, "Failed to persist FileSystemEvent", e);
            }

            // Only analyze modifications
            if (!operation.equals("MODIFY")) {
                Log.d(TAG, "Skipping operation: " + operation);
                return;
            }

            // Update SPRT with actual Poisson arrival math — always, even without a file path.
            // For Mode-A events with an unknown filename the write-rate signal is still valid.
            long currentTime = System.currentTimeMillis();
            if (lastEventTimestamp > 0) {
                double deltaSeconds = (currentTime - lastEventTimestamp) / 1000.0;
                // Cap delta to avoid massive drift if service was suspended
                sprtDetector.recordTimePassed(Math.min(deltaSeconds, 5.0));
            }
            sprtDetector.recordEvent();
            lastEventTimestamp = currentTime;

            // Update modification rate for legacy monitoring
            updateModificationRate(currentTime);

            // Calculate entropy and KL-divergence only when a real file is readable.
            // When the path is empty or the file no longer exists (process exited before
            // the daemon's 500ms poll), fall back to SPRT + behavior score only.
            double entropy = 0.0;
            double klDivergence = 0.0;
            File file = new File(filePath);
            boolean hasFileData = !filePath.isEmpty() && file.exists() && file.length() >= 100;
            if (hasFileData) {
                Log.d(TAG, "Calculating entropy for: " + filePath);
                entropy = entropyAnalyzer.calculateEntropy(file);
                klDivergence = klCalculator.calculateDivergence(file);
                Log.d(TAG, "Entropy: " + entropy + ", KL: " + klDivergence);
            } else {
                Log.d(TAG, "Skipping entropy (file unavailable): "
                        + (filePath.isEmpty() ? "<unknown path>" : filePath));
            }

            // Get SPRT state
            SPRTDetector.SPRTState sprtState = sprtDetector.getCurrentState();

            // Calculate composite confidence score
            int confidenceScore = calculateConfidenceScore(entropy, klDivergence, sprtState);
            
            // PSEUDO-KERNEL: Use existing behavior correlation result
            int behaviorScore = correlation.getBehaviorScore();
            int totalScore = Math.min(confidenceScore + behaviorScore, 130); // Max 130 (100 file + 30 behavior)

            Log.i(TAG, "Detection: entropy=" + entropy + ", kl=" + klDivergence + ", sprt=" + sprtState + 
                    ", fileScore=" + confidenceScore + ", behaviorScore=" + behaviorScore + ", total=" + totalScore);

            DetectionResult result = new DetectionResult(
                    entropy, klDivergence, sprtState.name(), totalScore, filePath);

            logDetectionResult(result);
            
            // Proactive Data Protection: Start attack tracking at medium confidence
            if (totalScore >= 40 && snapshotManager != null && snapshotManager.getActiveAttackId() == 0) {
                Log.w(TAG, "SUSPICIOUS ACTIVITY: Starting proactive data tracking (Score: " + totalScore + ")");
                snapshotManager.startAttackTracking();
            }

            // Store correlation result
            try {
                database.insertCorrelationResult(correlation.toJSON());
            } catch (Exception e) {
                Log.e(TAG, "Failed to store correlation result", e);
            }

            if (result.isHighRisk()) {
                Log.w(TAG, "HIGH RISK DETECTED: " + result.toJSON().toString());

                // SAFETY FIRST: Kill the ransomware process immediately to stop further encryption
                killMaliciousProcess(suspectPackageName);

                // Then block network to stop C2 communication
                triggerNetworkBlock();

                // AUTOMATED RESTORE: Revert damages after a short delay to ensure termination
                finalizeMitigationAndRestore(suspectPackageName);

                // Calculate estimated time to total infection
                int totalFiles = snapshotManager != null ?
                    snapshotManager.getTotalMonitoredFileCount(new String[]{
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS).getAbsolutePath(),
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES).getAbsolutePath(),
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM).getAbsolutePath()
                    }) : 1000; // Fallback

                double maliciousRate = recentModifications.size() / (TIME_WINDOW_MS / 1000.0);
                int infectionTimeSec = maliciousRate > 0 ? (int)(totalFiles / maliciousRate) : -1;

                // Inform user and suggest recovery
                showHighRiskAlert(filePath, totalScore, infectionTimeSec);
            }

            // Reset SPRT only on ACCEPT_H0 (normal behavior confirmed)
            if (sprtState == SPRTDetector.SPRTState.ACCEPT_H0) {
                Log.i(TAG, "SPRT Decision: Normal behavior confirmed, resetting");
                sprtDetector.reset();
            }
            // Keep SPRT state on ACCEPT_H1 to maintain high alert
        } catch (Exception e) {
            Log.e(TAG, "Error analyzing file event", e);
        }
    }

    private void updateModificationRate(long currentTime) {
        // Add current modification
        recentModifications.add(currentTime);

        // Remove modifications outside time window
        while (!recentModifications.isEmpty()) {
            Long oldTime = recentModifications.peek();
            if (oldTime != null && currentTime - oldTime > TIME_WINDOW_MS) {
                recentModifications.poll();
            } else {
                break;
            }
        }
    }

    private int calculateConfidenceScore(double entropy, double klDivergence,
            SPRTDetector.SPRTState sprtState) {
        int score = 0;

        // Entropy contribution (0-40 points)
        if (entropy > 7.8)
            score += 40;
        else if (entropy > 7.5)
            score += 30;
        else if (entropy > 7.0)
            score += 20;
        else if (entropy > 6.0)
            score += 10;

        // KL-divergence contribution (0-30 points)
        if (klDivergence < 0.05)
            score += 30; // Very uniform (encrypted)
        else if (klDivergence < 0.1)
            score += 20;
        else if (klDivergence < 0.2)
            score += 10;

        // SPRT contribution (0-30 points)
        if (sprtState == SPRTDetector.SPRTState.ACCEPT_H1)
            score += 30;
        else if (sprtState == SPRTDetector.SPRTState.CONTINUE)
            score += 10;

        return Math.min(score, 100);
    }

    private void logDetectionResult(DetectionResult result) {
        try {
            database.insertDetectionResult(result.toJSON());
            Log.i(TAG, "Detection logged to SQLite: " + result.toJSON().toString());

            if (result.isHighRisk()) {
                Log.w(TAG, "HIGH RISK DETECTED: " + result.toJSON().toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to log detection result", e);
        }
    }

    @Deprecated
    public File getDetectionLogFile() {
        // Legacy method for compatibility
        return new File(context.getFilesDir(), "detection_results.json");
    }

    public void shutdown() {
        detectionThread.quitSafely();
    }

    public Context getContext() {
        return context;
    }

    // -------------------------------------------------------------------------
    // Process Attribution
    // -------------------------------------------------------------------------

    /**
     * Attempt to identify the UID of the process responsible for a file event.
     *
     * <p>Strategy 1 — private storage path parsing: paths under /data/data/<pkg>/ or
     * /data/user/N/<pkg>/ encode the owning package name directly. This is deterministic
     * and covers the most common ransomware attack surface (app-private or downloaded
     * files staged in app directories).
     *
     * <p>Strategy 2 — shared-storage subdirectory parsing: paths that contain the
     * segment Android/data/<pkg>/ or Android/obb/<pkg>/ expose the package name
     * regardless of which storage volume they sit on.
     *
     * <p>Strategy 3 — foreground process heuristic: if neither path pattern matched,
     * the foreground non-SHIELD app at event time is the most likely candidate. This
     * is a heuristic and will be wrong for background writers, but is far more accurate
     * than returning SHIELD's own UID.
     *
     * <p>Returns -1 when no confident attribution is possible (unattributed event).
     */
    private int resolveAttributionUid(String filePath) {
        if (filePath == null) return -1;

        // --- Strategy 1: app-private storage ---
        String pkg = extractPackageFromPrivatePath(filePath);
        if (pkg != null) {
            try {
                ApplicationInfo ai = context.getPackageManager()
                        .getApplicationInfo(pkg, 0);
                Log.d(TAG, "Attribution(private-path): " + filePath + " → " + pkg
                        + " uid=" + ai.uid);
                return ai.uid;
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Attribution: package '" + pkg + "' from path not found");
            }
        }

        // --- Strategy 2: shared-storage Android/data or Android/obb segment ---
        pkg = extractPackageFromSharedStoragePath(filePath);
        if (pkg != null) {
            try {
                ApplicationInfo ai = context.getPackageManager()
                        .getApplicationInfo(pkg, 0);
                Log.d(TAG, "Attribution(shared-path): " + filePath + " → " + pkg
                        + " uid=" + ai.uid);
                return ai.uid;
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Attribution: package '" + pkg + "' from shared path not found");
            }
        }

        // --- Strategy 3: foreground process heuristic ---
        try {
            ActivityManager am = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                java.util.List<ActivityManager.RunningAppProcessInfo> procs =
                        am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo proc : procs) {
                        if (proc.importance
                                == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                                && !proc.processName.equals(context.getPackageName())) {
                            Log.d(TAG, "Attribution(foreground-heuristic): "
                                    + filePath + " → " + proc.processName
                                    + " uid=" + proc.uid);
                            return proc.uid;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Attribution: foreground lookup failed", e);
        }

        // Unattributed — cannot determine writer without root or eBPF
        Log.d(TAG, "Attribution(unresolved): " + filePath + " → uid=-1");
        return -1;
    }

    /**
     * Extract the package name from an app-private storage path.
     * Handles both /data/data/<pkg>/ and /data/user/<userId>/<pkg>/ layouts.
     */
    private String extractPackageFromPrivatePath(String filePath) {
        // /data/data/<pkg>/...
        Matcher m1 = Pattern.compile("^/data/data/([a-zA-Z][a-zA-Z0-9_.]+)/").matcher(filePath);
        if (m1.find()) return m1.group(1);

        // /data/user/<userId>/<pkg>/...
        Matcher m2 = Pattern.compile("^/data/user/\\d+/([a-zA-Z][a-zA-Z0-9_.]+)/").matcher(filePath);
        if (m2.find()) return m2.group(1);

        return null;
    }

    /**
     * Extract the package name from a shared-storage path that contains the
     * Android/data/<pkg>/ or Android/obb/<pkg>/ directory segment.
     * Covers /sdcard/Android/data/<pkg>/ and /storage/emulated/N/Android/data/<pkg>/.
     */
    private String extractPackageFromSharedStoragePath(String filePath) {
        Matcher m = Pattern.compile(
                "(?:/storage/emulated/\\d+|/sdcard)/Android/(?:data|obb)/([a-zA-Z][a-zA-Z0-9_.]+)/"
        ).matcher(filePath);
        if (m.find()) return m.group(1);
        return null;
    }
    
    private void triggerNetworkBlock() {
        android.content.Intent intent = new android.content.Intent("com.dearmoon.shield.EMERGENCY_MODE");
        context.sendBroadcast(intent);
        Log.e(TAG, "Emergency mode triggered - broadcast sent");
    }
    
    private void killMaliciousProcess(String packageName) {
        if (packageName == null || packageName.equals("unknown") || packageName.equals(context.getPackageName())) {
            return;
        }

        try {
            // Step 1 – terminate via ActivityManager (KILL_BACKGROUND_PROCESSES permission declared)
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(packageName);
                Log.w(TAG, "killBackgroundProcesses issued for: " + packageName);
            }

            // Step 2 – kernel kill via Mode A JNI (if Mode A is running); resolve PID first
            int pid = getPidForPackage(packageName);
            if (pid > 0) {
                try {
                    android.content.Intent killIntent = new android.content.Intent(
                            com.dearmoon.shield.modea.ModeAService.ACTION_KILL_PID);
                    killIntent.putExtra("pid", pid);
                    context.sendBroadcast(killIntent);
                    Log.w(TAG, "ModeA kill broadcast sent for PID " + pid);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send ModeAService kill intent", e);
                }
            }

            // Step 3 – open App Info as user-facing fallback so they can confirm Force Stop
            android.content.Intent appInfo = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            appInfo.setData(android.net.Uri.parse("package:" + packageName));
            appInfo.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(appInfo);

            // Log the action to the database
            try {
                database.insertLockerShieldEvent(new com.dearmoon.shield.lockerguard.LockerShieldEvent(
                    packageName, "PROCESS_KILL_INITIATED", 100, "Automated response to high-risk detection"
                ));
            } catch (Exception e) {
                Log.e(TAG, "Failed to log process kill", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error killing process: " + packageName, e);
        }
    }

    private void finalizeMitigationAndRestore(String suspectPackageName) {
        if (snapshotManager == null) return;

        // Instead of a fixed delay, poll for process death (up to 10 seconds)
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            boolean dead = waitForProcessDeath(suspectPackageName, 10_000);
            if (dead) {
                try {
                    Log.e(TAG, "Finalizing mitigation: Stopping tracking and initiating restore");
                    snapshotManager.stopAttackTracking();
                    sprtDetector.reset(); // Clear ACCEPT_H1 latch now that attack session is resolved
                    com.dearmoon.shield.snapshot.RestoreEngine.RestoreResult result =
                        snapshotManager.performAutomatedRestore();

                    Log.i(TAG, "AUTOMATED RESTORE COMPLETED: " + result.restoredCount + " files recovered");

                    // Notify system of completion
                    android.content.Intent intent = new android.content.Intent("com.dearmoon.shield.RESTORE_COMPLETE");
                    intent.putExtra("restored_count", result.restoredCount);
                    context.sendBroadcast(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Automated restore failed", e);
                }
            } else {
                // Notify user: manual Force Stop required before restore
                showManualStopRequiredAlert(suspectPackageName);
            }
        });
        executor.shutdown(); // Release thread; task is already submitted
    }

    // Helper: poll for process death
    private boolean waitForProcessDeath(String pkg, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isProcessRunning(pkg)) return true;
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        return false;  // timed out — process still alive
    }

    private boolean isProcessRunning(String pkg) {
        ActivityManager am = (ActivityManager)
            context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        java.util.List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) return false;
        for (ActivityManager.RunningAppProcessInfo p : procs) {
            if (java.util.Arrays.asList(p.pkgList).contains(pkg)) return true;
        }
        return false;
    }

    private int getPidForPackage(String pkg) {
        ActivityManager am = (ActivityManager)
            context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return -1;
        java.util.List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) return -1;
        for (ActivityManager.RunningAppProcessInfo p : procs) {
            if (java.util.Arrays.asList(p.pkgList).contains(pkg)) return p.pid;
        }
        return -1;
    }

    // Placeholder for user notification
    private void showManualStopRequiredAlert(String pkg) {
        Log.w(TAG, "Manual Force Stop required for package: " + pkg);
        // TODO: Implement user-facing alert/notification
    }

    private void showHighRiskAlert(String filePath, int score, int infectionTimeSec) {
        android.content.Intent intent = new android.content.Intent("com.dearmoon.shield.HIGH_RISK_ALERT");
        intent.putExtra("file_path", filePath);
        intent.putExtra("confidence_score", score);
        intent.putExtra("infection_time", infectionTimeSec);
        intent.putExtra("instructions", "Ransomware activity terminated and automated data recovery initiated. Check logs for restoration status.");
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        context.sendBroadcast(intent);
        Log.e(TAG, "High-risk alert broadcast sent with recovery instructions");

        // Requirement 3: System notification for ransomware found
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                // Ensure channel exists
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        "shield_alerts", "High Risk Alerts", android.app.NotificationManager.IMPORTANCE_HIGH);
                    nm.createNotificationChannel(channel);
                }

                android.content.Intent uiIntent = new android.content.Intent(context, com.dearmoon.shield.MainActivity.class);
                uiIntent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    context, 0, uiIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

                androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(context, "shield_alerts")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("⚠️ RANSOMWARE BLOCKED")
                    .setContentText("Malicious process terminated. Recovering data for: " + (filePath != null ? new File(filePath).getName() : "Unknown"))
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

                nm.notify(2002, builder.build());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to post high risk system notification", e);
        }
    }
}
