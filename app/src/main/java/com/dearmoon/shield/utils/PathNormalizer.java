package com.dearmoon.shield.utils;

import android.os.Environment;
import java.io.File;

// Normalize Android paths
public class PathNormalizer {
    
    private static String externalStoragePath = null;
    private static final String SDCARD_ALIAS = "/sdcard";

    // Replace path aliases
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) return path;

        // Get external storage
        if (externalStoragePath == null) {
            try {
                externalStoragePath = Environment.getExternalStorageDirectory().getAbsolutePath();
            } catch (Exception e) {
                // Unit test fallback
                externalStoragePath = "/storage/emulated/0";
            }
        }

        String normalized = path;

        // Handle /sdcard alias
        if (normalized.startsWith(SDCARD_ALIAS)) {
            normalized = externalStoragePath + normalized.substring(SDCARD_ALIAS.length());
        }

        // Handle double slashes
        normalized = normalized.replace("//", "/");

        // Remove trailing slash
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }
}
