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
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import com.dearmoon.shield.utils.PathNormalizer;
import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.dearmoon.shield.data.ShieldEventBus;

public class UnifiedDetectionEngine implements ShieldEventBus.ShieldEventListener {
    private static final String TAG = "UnifiedDetectionEngine";

    private final Context context;
    private final EventDatabase database;
    private final FileAnomalyAnalyzer anomalyAnalyzer;
    private final BehaviorMonitor behaviorMonitor;
    private final InterventionOrchestrator orchestrator;
    private final WhitelistManager whitelistManager;
    private final PackageAttributor attributor;
    private com.dearmoon.shield.snapshot.SnapshotManager snapshotManager;

    private final HandlerThread detectionThread;
    private final Handler detectionHandler;
    private final Executor killExecutor;
    // Kill synchronization set
    private final Set<String> killInProgress = ConcurrentHashMap.newKeySet();

    private final AppSecurityContextManager contextManager = AppSecurityContextManager.getInstance();
    private static final long TIME_WINDOW_MS = 1000; // 1 second window

    public UnifiedDetectionEngine(Context context) {
        this(context, null, null);
    }

    public UnifiedDetectionEngine(Context context, com.dearmoon.shield.snapshot.SnapshotManager snapshotManager) {
        this(context, snapshotManager, null);
    }

    public UnifiedDetectionEngine(Context context, 
                                 com.dearmoon.shield.snapshot.SnapshotManager snapshotManager,
                                 Executor executor) {
        this.context = context;
        this.database = EventDatabase.getInstance(context);
        this.anomalyAnalyzer = new FileAnomalyAnalyzer();
        this.behaviorMonitor = new BehaviorMonitor(context);
        this.killExecutor = executor != null ? executor : createDefaultExecutor();
        this.orchestrator = new InterventionOrchestrator(context, snapshotManager, this.killExecutor);
        this.whitelistManager = new WhitelistManager(context);
        this.snapshotManager = snapshotManager;
        this.attributor = new PackageAttributor(context);

        detectionThread = new HandlerThread("DetectionThread");
        detectionThread.start();
        detectionHandler = new Handler(detectionThread.getLooper());

        // Subscribe to global events
        ShieldEventBus.getInstance().register(this);
    }

    @Override
    public void onFileSystemEvent(FileSystemEvent event) {
        processFileEvent(event);
    }

    @Override
    public void onNetworkEvent(com.dearmoon.shield.data.NetworkEvent event) {
        // Network events are persisted securely via TelemetryStorage in NetworkGuardService.
        // Forensic reporting isolates blocked connections at export via EventDatabase.
    }

    @Override
    public void onHoneyfileEvent(com.dearmoon.shield.data.HoneyfileEvent event) {
        detectionHandler.post(() -> triggerHoneyfileKill(event.getFilePath(), event.getUid()));
    }

    @Override
    public void onLockerEvent(com.dearmoon.shield.lockerguard.LockerShieldEvent event) {
        if ("MANUAL_KILL".equals(event.getThreatType())) {
            detectionHandler.post(() -> orchestrator.executeProcessKill(event.getPackageName()));
        }
    }

    private AppSecurityContextManager.AppDetectionContext getOrCreateContext(int uid) {
        return contextManager.getContext(uid);
    }

    public void processFileEvent(FileSystemEvent event) {
        detectionHandler.post(() -> analyzeFileEvent(event));
    }

    private void analyzeFileEvent(FileSystemEvent event) {
        try {
            String operation = event.getOperation();
            String rawPath = event.getFilePath();
            String filePath = PathNormalizer.normalize(rawPath);

            Log.d(TAG, "Analyzing file event: " + operation + " on " + filePath);

            // FIXED: Persist event IMMEDIATELY before any filtering/whitelist
            database.insertFileSystemEvent(event);

            // Resolve process UID
            int attributedUid = event.getUid();
            if (attributedUid == -1) {
                attributedUid = attributor.resolveUidFromPath(filePath);
            }

            // Get app-specific context
            AppSecurityContextManager.AppDetectionContext appCtx = getOrCreateContext(attributedUid);

            // 1. Analyze Behavior (UID-SCOPED)
            CorrelationResult correlation = behaviorMonitor.analyzeBehavior(
                filePath, event.getTimestamp(), attributedUid, appCtx
            );
            String suspectPackageName = correlation.getPackageName();

            // 2. Whitelist Check
            if (whitelistManager.isWhitelisted(suspectPackageName)) return;

            if (!operation.equals("MODIFY")) return;

            // 3. Analyze File Anomalies (Entropy/KL)
            FileAnomalyAnalyzer.AnomalyResult anomaly = anomalyAnalyzer.analyze(filePath);
            
            // --- XOR / Low-Entropy Cipher Detection ---
            // High KL Divergence (> 0.25) + Low-ish Entropy (< 7.8) = Structured Shift (XOR)
            boolean isStructuredShift = anomaly.klDivergence > 0.25 && anomaly.entropy < 7.8;
            
            // 5. Calculate Composite Risk
            SPRTDetector.SPRTState sprtState = appCtx.sprtDetector.getCurrentState();
            double totalScore = calculateConfidenceScore(
                anomaly.entropy, 
                anomaly.klDivergence, 
                sprtState, 
                isStructuredShift
            );
            
            // False Positive Mitigation: Discount known high-entropy compressed formats
            String ext = filePath.toLowerCase();
            if (ext.endsWith(".apk") || ext.endsWith(".dex")) {
                totalScore *= 0.6;
            } else if (ext.endsWith(".mp4") || ext.endsWith(".mkv") || ext.endsWith(".avi") || 
                       ext.endsWith(".jpg") || ext.endsWith(".jpeg") || ext.endsWith(".png") ||
                       ext.endsWith(".zip") || ext.endsWith(".gz") || ext.endsWith(".7z") ||
                       ext.endsWith(".pdf")) {
                // Compressed formats have high entropy by nature, discount heavily
                totalScore *= 0.4;
            }
            
            // 6. Extension Diversity Analysis (Advanced FP Filter)
            String extension = "";
            int dotIdx = filePath.lastIndexOf('.');
            if (dotIdx > 0) extension = filePath.substring(dotIdx).toLowerCase();
            if (!extension.isEmpty()) appCtx.affectedExtensions.add(extension);

            int typeDiversity = appCtx.affectedExtensions.size();
            // Ransomware destroys DIVERSE types. If only 1 type is touched, it's likely a media recorder.
            if (typeDiversity <= 1) {
                totalScore *= 0.5; // 50% discount for single-type operations
                Log.d(TAG, "FP Shield: Low diversity detected (" + typeDiversity + "). Discounting score.");
            } else if (typeDiversity == 2) {
                totalScore *= 0.8; // Minor discount for 2 types (e.g., .mp4 + .tmp)
            }

            Log.i(TAG, "Analysis Result - File: " + suspectPackageName + " | Score: " + totalScore + " | [Ent:" + anomaly.entropy + " KL:" + anomaly.klDivergence + " Diversity:" + typeDiversity + "]");

            DetectionResult result = new DetectionResult(anomaly.entropy, anomaly.klDivergence, sprtState.name(), (int)totalScore, filePath);
            logDetectionResult(result);

            // 7. Proactive Snapshots
            if (totalScore >= 20 && snapshotManager != null && snapshotManager.getActiveAttackId() == 0) {
                snapshotManager.startAttackTracking();
            }

            // 8. Intervention Trigger
            if (result.isHighRisk()) {
                int totalFiles = snapshotManager != null ? snapshotManager.getTotalMonitoredFileCount(null) : 1000;
                double maliciousRate = appCtx.recentModifications.size() / (TIME_WINDOW_MS / 1000.0);
                int infectionTimeSec = maliciousRate > 0 ? (int)(totalFiles / maliciousRate) : -1;

                // Map raw REAL metrics to normalized forensic scores (0-40, 0-30 scale)
                int entropyScoreValue = (anomaly.entropy > 7.9) ? 25 : (anomaly.entropy > 7.5) ? 15 : (anomaly.entropy > 7.0) ? 5 : 0;
                int kldScoreValue     = (anomaly.klDivergence < 0.04) ? 15 : (anomaly.klDivergence < 0.08) ? 10 : 0;

                orchestrator.showHighRiskAlert(filePath, (int)totalScore, infectionTimeSec, suspectPackageName, 
                        entropyScoreValue, kldScoreValue, sprtState == SPRTDetector.SPRTState.ACCEPT_H1);
                triggerNetworkBlock();
            }

            // 8. Auto-Reset
            behaviorMonitor.resetScoring(appCtx, suspectPackageName);
            // Keep SPRT state on ACCEPT_H1 to maintain high alert
        } catch (Exception e) {
            Log.e(TAG, "Error analyzing file event", e);
        }
    }

    // Honeyfile kill trigger
    public void triggerHoneyfileKill(String filePath, int uid) {
        int attributedUid = uid;
        String selfPkg = context.getPackageName();
        
        // FileObserver events usually report our own UID. Fallback to heuristic attribution.
        if (attributedUid <= 0 || (attributor != null && selfPkg.equals(attributor.getPackageForUid(attributedUid)))) {
            attributedUid = attributor != null ? attributor.resolveUidFromPath(filePath) : -1;
        }

        String suspectPackageName = attributor != null ? attributor.getPackageForUid(attributedUid) : "unknown";

        // Whitelist check before kill
        if (whitelistManager.isWhitelisted(suspectPackageName)) {
            Log.i(TAG, "Honeyfile access detected but package is whitelisted: " + suspectPackageName);
            return;
        }

        Log.w(TAG, "HONEYFILE DESTRUCTION DETECTED: Bypassing score thresholds for absolute kill by " + suspectPackageName);

        // Immediate attack tracking
        if (snapshotManager != null && snapshotManager.getActiveAttackId() == 0) {
            Log.w(TAG, "SUSPICIOUS ACTIVITY: Starting proactive data tracking (Honeyfile Trigger)");
            snapshotManager.startAttackTracking();
        }

        // Forced detection result
        DetectionResult result = new DetectionResult(
                8.0, 0.01, SPRTDetector.SPRTState.ACCEPT_H1.name(), 100, filePath);
        logDetectionResult(result);

        try {
            Log.w(TAG, "HIGH RISK DETECTED (HONEYFILE): " + result.toJSON().toString());
        } catch (org.json.JSONException e) {
            Log.e(TAG, "Failed to convert DetectionResult to JSON", e);
        }

        // FORCED MANUAL INTERVENTION (Honeyfile Trigger)
        orchestrator.showHighRiskAlert(filePath, 100, -1, suspectPackageName, 8, 1, true);

        // Optional safety block
        triggerNetworkBlock();
    }

    private int calculateConfidenceScore(double entropy, double klDivergence,
            SPRTDetector.SPRTState sprtState, boolean isStructuredShift) {
        int score = 0;
        
        // 1. Data Anomaly Scoring (Max 40)
        // Entropy reflects randomness/compression.
        if (entropy > 7.9) score += 25;
        else if (entropy > 7.5) score += 15;
        else if (entropy > 7.0) score += 5;

        // Structured shift boost (e.g. XOR ciphers)
        if (isStructuredShift) score += 15;

        // KL Divergence (Randomness distribution)
        if (klDivergence < 0.04) score += 15; // Extremely uniform
        else if (klDivergence < 0.08) score += 10;
        
        // Cap data score at 40 points - NOT ENOUGH TO KILL ALONE (Threshold is 43)
        score = Math.min(score, 40);

        // 2. Sequential Analysis (High confidence boost)
        if (sprtState == SPRTDetector.SPRTState.ACCEPT_H1) score += 50;
        
        return Math.min(score, 100);
    }

    private void logDetectionResult(DetectionResult result) {
        try {
            database.insertDetectionResult(result.toJSON());
            Log.i(TAG, "Detection logged: " + result.getSuspectFile());
        } catch (Exception e) {
            Log.e(TAG, "Log failed", e);
        }
    }

    public void shutdown() {
        ShieldEventBus.getInstance().unregister(this);
        detectionThread.quitSafely();
    }

    public Context getContext() {
        return context;
    }

    private Executor createDefaultExecutor() {
        return new ThreadPoolExecutor(
                1, 2,      // core=1, max=2 threads
                5L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(4),  // Max 4 pending ops
                r -> new Thread(r, "KillSequenceThread"),
                new ThreadPoolExecutor.DiscardOldestPolicy() // Drop oldest pending kill
        );
    }

    private void triggerNetworkBlock() {
        android.content.Intent intent = new android.content.Intent("com.dearmoon.shield.EMERGENCY_MODE");
        context.sendBroadcast(intent);
    }
}
