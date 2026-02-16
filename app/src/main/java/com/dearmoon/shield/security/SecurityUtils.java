package com.dearmoon.shield.security;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.util.Log;
import java.io.File;

public class SecurityUtils {
    private static final String TAG = "SecurityUtils";

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
        return Build.FINGERPRINT.contains("generic")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK");
    }

    private static boolean isRooted() {
        String[] paths = {
            "/system/app/Superuser.apk",
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su"
        };
        for (String path : paths) {
            if (new File(path).exists()) return true;
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
            
            String signatureHash = String.valueOf(signatures[0].hashCode());
            Log.i(TAG, "App signature hash: " + signatureHash);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Signature verification failed", e);
            return false;
        }
    }
}
