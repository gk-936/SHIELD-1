package com.dearmoon.shield.security;

import android.util.Log;
import java.io.File;
import java.io.IOException;

/**
 * RootShell — central utility for resilient su command execution.
 * Addresses the "error=2, No such file or directory" issue by searching
 * multiple absolute paths for modern Android (Magisk/KernelSU).
 */
public class RootShell {
    private static final String TAG = "RootShell";
    
    // Multi-path search strategy for modern Android
    private static final String[] SU_CANDIDATES = {
        "su",                         // Default PATH
        "/system/bin/su",             // Conventional Android
        "/system/xbin/su",            // Conventional Android
        "/sbin/su",                   // Legacy Magisk
        "/data/adb/magisk/su",        // Potential Magisk core
        "/debug_ramdisk/su",          // Newer Magisk
        "/data/local/xbin/su",
        "/data/local/bin/su"
    };

    private static String cachedSuPath = null;

    /**
     * Finds a working su binary path. Caches the result after the first success.
     */
    public static synchronized String findSuPath() {
        if (cachedSuPath != null) return cachedSuPath;

        for (String candidate : SU_CANDIDATES) {
            if (candidate.equals("su")) {
                // Check if 'su' already works in the default environment
                if (testSuRaw()) {
                    cachedSuPath = "su";
                    return cachedSuPath;
                }
                continue;
            }

            File file = new File(candidate);
            if (file.exists()) {
                Log.d(TAG, "Found su binary at: " + candidate);
                // Even if file exists, verify execution permissions (or test with id command)
                if (testSuPath(candidate)) {
                    cachedSuPath = candidate;
                    Log.i(TAG, "Su binary confirmed working: " + candidate);
                    return cachedSuPath;
                }
            }
        }

        Log.w(TAG, "No su binary found in any candidate path");
        return null;
    }

    private static boolean testSuRaw() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            int rc = p.waitFor();
            return rc == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean testSuPath(String path) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{path, "-c", "id"});
            int rc = p.waitFor();
            return rc == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Executes a command as root using the best available su path.
     */
    public static Process execute(String command) throws IOException {
        String su = findSuPath();
        if (su == null) throw new IOException("No su binary found");
        
        Log.d(TAG, "Executing root command: " + command + " [using " + su + "]");
        return Runtime.getRuntime().exec(new String[]{su, "-c", command});
    }

    /**
     * Executes a command as root with multiple arguments.
     */
    public static Process execute(String[] commandArray) throws IOException {
        String su = findSuPath();
        if (su == null) throw new IOException("No su binary found");

        String[] fullCommand = new String[commandArray.length + 2];
        fullCommand[0] = su;
        fullCommand[1] = "-c";
        System.arraycopy(commandArray, 0, fullCommand, 2, commandArray.length);

        return Runtime.getRuntime().exec(fullCommand);
    }

    public static boolean isAvailable() {
        return findSuPath() != null;
    }
}
