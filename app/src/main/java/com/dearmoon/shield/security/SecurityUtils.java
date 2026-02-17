package com.dearmoon.shield.security;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.util.Log;
import java.io.File;

public class SecurityUtils {
    private static final String TAG = "SecurityUtils";
    
    // SECURITY FIX: Expected signature hash for production builds
    // This should be set to your actual release signature hash
    // To get your hash: adb shell pm list packages -f com.dearmoon.shield
    // Then: keytool -printcert -jarfile app.apk
    private static final String EXPECTED_SIGNATURE_HASH = null;  // Set in production

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
        // Robust check for null Build fields (can happen in test environments)
        String fingerprint = Build.FINGERPRINT != null ? Build.FINGERPRINT : "";
        String model = Build.MODEL != null ? Build.MODEL : "";

        return fingerprint.contains("generic")
                || model.contains("Emulator")
                || model.contains("Android SDK");
    }

    /**
     * SECURITY FIX: Improved root detection
     * Checks for:
     * 1. Traditional su binaries in common paths
     * 2. Modern root tools (Magisk, KernelSU)
     * 3. su in PATH environment variable
     * 4. Test-keys build (indicates custom ROM)
     */
    private static boolean isRooted() {
        // Check traditional su paths
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
        
        // Check PATH for su
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
        
        // Check for test-keys (custom ROM indicator)
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

    /**
     * SECURITY FIX: Proper signature verification
     * Compares actual signature hash against expected value.
     * Returns true if EXPECTED_SIGNATURE_HASH is null (development mode).
     */
    public static boolean verifySignature(Context context) {
        try {
            android.content.pm.PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 
                    android.content.pm.PackageManager.GET_SIGNATURES);
            
            android.content.pm.Signature[] signatures = packageInfo.signatures;
            if (signatures == null || signatures.length == 0) {
                Log.w(TAG, "No signatures found");
                return false;
            }
            
            int signatureHash = signatures[0].hashCode();
            String signatureHashStr = String.valueOf(signatureHash);
            Log.i(TAG, "App signature hash: " + signatureHashStr);
            
            // If expected hash not set (development), allow
            if (EXPECTED_SIGNATURE_HASH == null) {
                Log.i(TAG, "Signature verification skipped (development mode)");
                return true;
            }
            
            // Compare against expected hash
            boolean isValid = EXPECTED_SIGNATURE_HASH.equals(signatureHashStr);
            if (!isValid) {
                Log.e(TAG, "Signature mismatch! Expected: " + EXPECTED_SIGNATURE_HASH + 
                          ", Got: " + signatureHashStr);
            }
            return isValid;
        } catch (Exception e) {
            Log.e(TAG, "Signature verification failed", e);
            return false;
        }
    }
}
