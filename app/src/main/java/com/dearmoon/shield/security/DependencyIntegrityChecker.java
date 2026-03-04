package com.dearmoon.shield.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import com.dearmoon.shield.data.EventDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * M2 — Inadequate Supply Chain Security
 *
 * <p>Performs two complementary supply-chain checks on every service start:
 *
 * <ol>
 *   <li><strong>Certificate pinning:</strong> Computes the SHA-256 digest of the first signing
 *       certificate in the APK and compares it against a known-good baseline stored in
 *       {@link android.content.SharedPreferences} on the first clean run. If the certificate
 *       changes (i.e., the APK was re-signed by a different party), a {@code CERT_CHANGED}
 *       finding is emitted. On the very first run the current certificate hash is enrolled as the
 *       baseline — subsequent runs validate against it.
 *   <li><strong>Native library hash pinning:</strong> Computes the SHA-256 digest of every
 *       {@code .so} file in the application's {@code lib/} directory and records a baseline of all
 *       hashes on the first clean run. On subsequent runs the current hashes are compared to the
 *       stored baseline. Any native library that is added, removed, or whose bytes have changed
 *       after installation generates a {@code NATIVE_LIB_TAMPERED} finding.
 * </ol>
 *
 * <p>All findings are written to the {@code config_audit_events} table in the existing
 * {@link EventDatabase} singleton so they appear in the unified log viewer alongside other
 * telemetry. A broadcast with action {@code com.dearmoon.shield.SUPPLY_CHAIN_ALERT} is sent
 * whenever a critical finding is detected.
 *
 * <p>This class is non-instantiable (static API only). Call {@link #check(Context)} once per
 * service start.
 */
public final class DependencyIntegrityChecker {

    private static final String TAG = "DependencyIntegrityChecker";

    // SharedPreferences file that holds the persisted baseline
    private static final String PREFS_FILE = "shield_supply_chain";
    private static final String KEY_CERT_HASH = "cert_hash_baseline";
    private static final String KEY_LIB_PREFIX = "lib_hash_";

    // Broadcast action emitted on critical findings
    public static final String ACTION_SUPPLY_CHAIN_ALERT = "com.dearmoon.shield.SUPPLY_CHAIN_ALERT";
    public static final String EXTRA_FINDING = "finding";

    /** Possible outcome of the supply-chain check. */
    public enum Finding {
        /** All checks passed — certificate and libraries match the stored baseline. */
        CLEAN,
        /** First run — baseline enrolled, no comparison performed. */
        BASELINE_ENROLLED,
        /** The APK signing certificate has changed since the baseline was enrolled. */
        CERT_CHANGED,
        /** One or more native {@code .so} files have been added, removed, or modified. */
        NATIVE_LIB_TAMPERED,
        /** A {@code PackageManager} or I/O error prevented the check from completing. */
        CHECK_FAILED
    }

    private DependencyIntegrityChecker() { /* non-instantiable */ }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the full supply-chain check and logs the result to {@link EventDatabase}.
     *
     * @param context any valid {@link Context}; the application context will be used internally.
     * @return the most severe {@link Finding} encountered during the check.
     */
    public static Finding check(Context context) {
        Context appCtx = context.getApplicationContext();

        Finding certFinding = checkSigningCertificate(appCtx);
        Finding libFinding  = checkNativeLibraries(appCtx);

        Finding worst = worstOf(certFinding, libFinding);

        // Persist to EventDatabase
        logFinding(appCtx, worst, certFinding, libFinding);

        // Fire broadcast for critical findings
        if (worst == Finding.CERT_CHANGED || worst == Finding.NATIVE_LIB_TAMPERED) {
            android.content.Intent intent = new android.content.Intent(ACTION_SUPPLY_CHAIN_ALERT);
            intent.putExtra(EXTRA_FINDING, worst.name());
            // Use FLAG_RECEIVER_REGISTERED_ONLY so only in-process receivers can handle it
            intent.addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            appCtx.sendBroadcast(intent);
            Log.w(TAG, "Supply-chain alert broadcast sent: " + worst.name());
        }

        return worst;
    }

    // -------------------------------------------------------------------------
    // Certificate baseline check
    // -------------------------------------------------------------------------

    private static Finding checkSigningCertificate(Context ctx) {
        try {
            String currentHash = computeCertHash(ctx);
            if (currentHash == null) {
                Log.e(TAG, "Unable to compute certificate hash");
                return Finding.CHECK_FAILED;
            }

            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
            String baseline = prefs.getString(KEY_CERT_HASH, null);

            if (baseline == null) {
                // First run — enroll baseline
                prefs.edit().putString(KEY_CERT_HASH, currentHash).apply();
                Log.i(TAG, "Certificate baseline enrolled: " + currentHash);
                return Finding.BASELINE_ENROLLED;
            }

            if (!baseline.equals(currentHash)) {
                Log.w(TAG, "Certificate CHANGED — expected " + baseline + " got " + currentHash);
                return Finding.CERT_CHANGED;
            }

            Log.d(TAG, "Certificate check CLEAN");
            return Finding.CLEAN;

        } catch (Exception e) {
            Log.e(TAG, "Certificate check failed", e);
            return Finding.CHECK_FAILED;
        }
    }

    /**
     * Returns the Base64-encoded SHA-256 of the first signing certificate, or {@code null} on
     * error.
     */
    private static String computeCertHash(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            //noinspection deprecation
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), flags);

            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && pi.signingInfo != null
                    && pi.signingInfo.getApkContentsSigners() != null) {
                sigs = pi.signingInfo.getApkContentsSigners();
            } else if (pi.signatures != null) {
                //noinspection deprecation
                sigs = pi.signatures;
            }

            if (sigs == null || sigs.length == 0) return null;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sigs[0].toByteArray());
            return Base64.encodeToString(digest, Base64.NO_WRAP);

        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
            Log.e(TAG, "computeCertHash error", e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Native library baseline check
    // -------------------------------------------------------------------------

    private static Finding checkNativeLibraries(Context ctx) {
        try {
            String nativeLibDir = ctx.getApplicationInfo().nativeLibraryDir;
            if (nativeLibDir == null) {
                Log.d(TAG, "No native library directory — skipping lib check");
                return Finding.CLEAN;
            }

            File libDir = new File(nativeLibDir);
            if (!libDir.exists() || !libDir.isDirectory()) {
                Log.d(TAG, "Native library directory does not exist — skipping lib check");
                return Finding.CLEAN;
            }

            // Collect current hashes
            Map<String, String> currentHashes = hashDirectory(libDir);
            if (currentHashes == null) {
                return Finding.CHECK_FAILED;
            }

            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);

            // Collect stored baseline
            Map<String, String> baseline = new HashMap<>();
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (entry.getKey().startsWith(KEY_LIB_PREFIX)) {
                    String libName = entry.getKey().substring(KEY_LIB_PREFIX.length());
                    baseline.put(libName, (String) entry.getValue());
                }
            }

            if (baseline.isEmpty()) {
                // Enroll baseline
                android.content.SharedPreferences.Editor editor = prefs.edit();
                for (Map.Entry<String, String> e : currentHashes.entrySet()) {
                    editor.putString(KEY_LIB_PREFIX + e.getKey(), e.getValue());
                }
                editor.apply();
                Log.i(TAG, "Native library baseline enrolled: " + currentHashes.size() + " libs");
                return Finding.BASELINE_ENROLLED;
            }

            // Compare
            boolean tampered = false;

            // Check for removed or changed libs
            for (Map.Entry<String, String> stored : baseline.entrySet()) {
                String current = currentHashes.get(stored.getKey());
                if (current == null) {
                    Log.w(TAG, "Native lib REMOVED: " + stored.getKey());
                    tampered = true;
                } else if (!current.equals(stored.getValue())) {
                    Log.w(TAG, "Native lib MODIFIED: " + stored.getKey());
                    tampered = true;
                }
            }

            // Check for added libs
            for (String libName : currentHashes.keySet()) {
                if (!baseline.containsKey(libName)) {
                    Log.w(TAG, "Native lib ADDED: " + libName);
                    tampered = true;
                }
            }

            if (tampered) {
                return Finding.NATIVE_LIB_TAMPERED;
            }

            Log.d(TAG, "Native library check CLEAN (" + currentHashes.size() + " libs)");
            return Finding.CLEAN;

        } catch (Exception e) {
            Log.e(TAG, "Native library check failed", e);
            return Finding.CHECK_FAILED;
        }
    }

    /**
     * Returns a map of {@code filename -> SHA-256 Base64} for all {@code .so} files directly
     * inside {@code dir}. Returns {@code null} if the directory cannot be read.
     */
    private static Map<String, String> hashDirectory(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".so"));
        if (files == null) return null;

        Map<String, String> result = new HashMap<>();
        for (File f : files) {
            String hash = hashFile(f);
            if (hash != null) {
                result.put(f.getName(), hash);
            }
        }
        return result;
    }

    /** Returns the Base64-encoded SHA-256 of {@code file}, or {@code null} on error. */
    private static String hashFile(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    md.update(buf, 0, read);
                }
            }
            return Base64.encodeToString(md.digest(), Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException | IOException e) {
            Log.e(TAG, "hashFile failed for " + file.getName(), e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Finding worstOf(Finding a, Finding b) {
        int[] severity = new int[Finding.values().length];
        severity[Finding.CLEAN.ordinal()]               = 0;
        severity[Finding.BASELINE_ENROLLED.ordinal()]   = 1;
        severity[Finding.CHECK_FAILED.ordinal()]        = 2;
        severity[Finding.NATIVE_LIB_TAMPERED.ordinal()] = 3;
        severity[Finding.CERT_CHANGED.ordinal()]        = 4;
        return severity[a.ordinal()] >= severity[b.ordinal()] ? a : b;
    }

    private static void logFinding(Context ctx,
                                   Finding overall,
                                   Finding certFinding,
                                   Finding libFinding) {
        try {
            EventDatabase db = EventDatabase.getInstance(ctx);
            String detail = "cert=" + certFinding.name() + " libs=" + libFinding.name();
            db.insertConfigAuditEvent(
                    "SUPPLY_CHAIN",
                    overall == Finding.CLEAN || overall == Finding.BASELINE_ENROLLED ? "PASS" : "FAIL",
                    overall.name(),
                    detail
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to log supply-chain finding to EventDatabase", e);
        }
    }

}
