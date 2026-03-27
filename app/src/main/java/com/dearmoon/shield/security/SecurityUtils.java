package com.dearmoon.shield.security;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import android.util.Log;
import java.io.File;
import java.security.MessageDigest;

public class SecurityUtils {
    private static final String TAG = "SecurityUtils";

    // Release signature hash
    private static String EXPECTED_SIGNATURE_HASH = "bd09700d069d3f4f29ae9396857df178403c298cae52644b4dbb91c60eac4a76";

    public static boolean checkSecurity(Context context) {
        boolean isSafe = true;

        if (isDebuggerAttached()) {
            Log.w(TAG, "Debugger detected");
            isSafe = false;
        }

        if (isEmulator()) {
            Log.w(TAG, "Emulator detected");
            isSafe = false;
        }

        if (isRooted()) {
            Log.w(TAG, "Root detected");
            isSafe = false;
        }

        if (isHooked()) {
            Log.w(TAG, "Hook framework detected");
            isSafe = false;
        }

        if (!verifySignature(context)) {
            Log.w(TAG, "Signature verification failed");
            isSafe = false;
        }

        return isSafe;
    }

    private static boolean isDebuggerAttached() {
        return Debug.isDebuggerConnected();
    }

    private static boolean isEmulator() {
        String fingerprint = Build.FINGERPRINT != null ? Build.FINGERPRINT : "";
        String model = Build.MODEL != null ? Build.MODEL : "";
        return fingerprint.contains("generic")
                || model.contains("Emulator")
                || model.contains("Android SDK");
    }

    // Public root check (passive — filesystem only)
    public static boolean isDeviceRooted() {
        return isRooted();
    }

    // ------------------------------------------------------------------
    // Root manager detection — always reliable via PackageManager
    // ------------------------------------------------------------------

    /**
     * Returns true if a known root manager (Magisk, KernelSU, SuperSU, etc.)
     * is installed. PackageManager is always accessible to app processes
     * regardless of mount namespace, PATH, or Zygisk isolation — so this works
     * consistently on every call, unlike su exec or filesystem checks.
     */
    public static boolean isRootManagerInstalled(Context context) {
        if (context == null) return false;
        PackageManager pm = context.getPackageManager();
        String[] rootPackages = {
            "com.topjohnwu.magisk",      // Magisk official
            "io.github.vvb2060.magisk",  // Magisk hidden stub
            "me.weishu.kernelsu",        // KernelSU
            "com.noshufou.android.su",   // SuperUser (AOSP)
            "eu.chainfire.supersu",      // SuperSU
            "com.koushikdutta.superuser" // ChainsDD
        };
        for (String pkg : rootPackages) {
            try {
                pm.getPackageInfo(pkg, 0);
                Log.i(TAG, "Root manager detected: " + pkg);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Active root check (for mode selection in MainActivity)
    // ------------------------------------------------------------------

    /**
     * Determines whether Root Mode (Mode A) should be offered to the user.
     *
     * Strategy 1 — PackageManager (primary, always consistent):
     *   Checks whether a root manager package is installed. This never has
     *   mount-namespace, PATH, or timing issues.
     *
     * Strategy 2 — ProcessBuilder su exec (secondary, triggers Magisk dialog):
     *   Attempts {@code su -c id} to confirm root and trigger the Magisk grant
     *   dialog if not yet granted.  Runs only if Strategy 1 finds a root manager.
     *
     * If Strategy 1 succeeds but Strategy 2 fails (su not yet in PATH / not
     * yet granted), we still return {@code true} so the ROOT MODE confirm screen
     * is shown. The Magisk dialog will appear inside ModeAService when
     * ModeAController.isRootAvailable() makes its own su call.
     *
     * MUST be called off the main thread.
     *
     * @param context the application context (used for PackageManager)
     * @return true if root is available or likely on this device
     */
    /**
     * Determines whether Root Mode (Mode A) should be offered to the user.
     * Use RootShell for resilient checking of su binary availability.
     */
    public static boolean tryExecRoot(Context context) {
        // --- Strategy 1: PackageManager root manager check ---
        boolean rootManagerFound = isRootManagerInstalled(context);
        if (!rootManagerFound) {
            // Also try passive filesystem check as last resort
            rootManagerFound = isDeviceRooted();
        }

        // --- Strategy 2: RootShell active check ---
        // This is the robust way to check for 'su' availability across multiple paths.
        boolean suWorked = RootShell.isAvailable();
        if (suWorked) {
            Log.i(TAG, "tryExecRoot: su confirmed working via RootShell -> ROOTED");
            return true;
        }

        if (rootManagerFound) {
            Log.i(TAG, "tryExecRoot: root manager installed; deferring su grant to ModeAService");
            return true;
        }

        Log.w(TAG, "tryExecRoot: no root indicators found -> non-rooted");
        return false;
    }

    // ------------------------------------------------------------------
    // Root detection logic (passive filesystem check)
    // ------------------------------------------------------------------

    private static boolean isRooted() {
        String[] suPaths = {
            "/system/app/Superuser.apk",
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        };
        for (String path : suPaths) {
            if (new File(path).exists()) {
                Log.w(TAG, "Root detected: " + path);
                return true;
            }
        }

        String[] magiskPaths = {
            "/data/adb/magisk",
            "/sbin/.magisk",
            "/cache/.disable_magisk"
        };
        for (String path : magiskPaths) {
            if (new File(path).exists()) {
                Log.w(TAG, "Magisk detected: " + path);
                return true;
            }
        }

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(":")) {
                File suFile = new File(dir, "su");
                if (suFile.exists() && suFile.canExecute()) {
                    Log.w(TAG, "su found in PATH: " + suFile.getAbsolutePath());
                    return true;
                }
            }
        }

        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) {
            Log.w(TAG, "Test-keys build detected (custom ROM)");
            return true;
        }

        return false;
    }

    private static boolean isHooked() {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // APK signature verification
    // ------------------------------------------------------------------

    public static boolean verifySignature(Context context) {
        try {
            android.content.pm.Signature cert;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.content.pm.PackageInfo info = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(),
                                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                android.content.pm.SigningInfo signingInfo = info.signingInfo;
                if (signingInfo == null) {
                    Log.w(TAG, "SigningInfo is null");
                    return false;
                }
                android.content.pm.Signature[] certs = signingInfo.hasMultipleSigners()
                        ? signingInfo.getApkContentsSigners()
                        : signingInfo.getSigningCertificateHistory();
                if (certs == null || certs.length == 0) {
                    Log.w(TAG, "No signing certificates found");
                    return false;
                }
                cert = certs[0];
            } else {
                @SuppressWarnings("deprecation")
                android.content.pm.PackageInfo info = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(),
                                android.content.pm.PackageManager.GET_SIGNATURES);
                android.content.pm.Signature[] sigs = info.signatures;
                if (sigs == null || sigs.length == 0) {
                    Log.w(TAG, "No signatures found");
                    return false;
                }
                cert = sigs[0];
            }

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cert.toByteArray());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            String actualHash = sb.toString();

            Log.i(TAG, "Cert SHA-256: " + actualHash);

            if (EXPECTED_SIGNATURE_HASH == null) {
                boolean isDebug = (context.getApplicationInfo().flags
                        & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                if (isDebug) {
                    Log.w(TAG, "EXPECTED_SIGNATURE_HASH not set — skipped (dev mode). Hash: " + actualHash);
                    return true;
                } else {
                    Log.e(TAG, "EXPECTED_SIGNATURE_HASH is null in release build. Hash: " + actualHash);
                    return false;
                }
            }

            boolean valid = EXPECTED_SIGNATURE_HASH.equalsIgnoreCase(actualHash);
            if (!valid) {
                Log.e(TAG, "Cert SHA-256 mismatch. Expected: " + EXPECTED_SIGNATURE_HASH + " Got: " + actualHash);
            }
            return valid;

        } catch (Exception e) {
            Log.e(TAG, "Signature verification failed", e);
            return false;
        }
    }
}
