package com.dearmoon.shield.security;

import android.content.Context;
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
        // Check Build fields
        String fingerprint = Build.FINGERPRINT != null ? Build.FINGERPRINT : "";
        String model = Build.MODEL != null ? Build.MODEL : "";

        return fingerprint.contains("generic")
                || model.contains("Emulator")
                || model.contains("Android SDK");
    }

    // Public root check
    public static boolean isDeviceRooted() {
        return isRooted();
    }

    // Root detection logic
    private static boolean isRooted() {
        // Check su paths
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
        
        // Check for Magisk
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
        
        // Check PATH su
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
        
        // Check test-keys
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

    // Verify APK signature
    public static boolean verifySignature(Context context) {
        try {
            android.content.pm.Signature cert;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+ SigningInfo
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
                // Legacy API path
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

            // Certificate SHA-256 digest
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
                    // Dev mode log
                    Log.w(TAG, "EXPECTED_SIGNATURE_HASH not set — signature check skipped (dev mode). "
                            + "Set SecurityUtils.EXPECTED_SIGNATURE_HASH to: " + actualHash);
                    return true;
                } else {
                    // Release build failure
                    Log.e(TAG, "EXPECTED_SIGNATURE_HASH is null in a release build — "
                            + "set it before shipping. Actual hash: " + actualHash);
                    return false;
                }
            }

            boolean valid = EXPECTED_SIGNATURE_HASH.equalsIgnoreCase(actualHash);
            if (!valid) {
                Log.e(TAG, "Cert SHA-256 mismatch — possible APK tampering. "
                        + "Expected: " + EXPECTED_SIGNATURE_HASH + "  Got: " + actualHash);
            }
            return valid;

        } catch (Exception e) {
            Log.e(TAG, "Signature verification failed", e);
            return false;
        }
    }
}
