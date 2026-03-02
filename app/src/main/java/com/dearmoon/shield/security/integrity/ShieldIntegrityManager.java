package com.dearmoon.shield.security.integrity;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * TEE-Anchored APK Integrity Manager (singleton via static methods).
 *
 * Generates an HMAC-SHA256 key inside the Android Keystore (StrongBox-backed when available,
 * TEE-backed otherwise), computes a SHA-256 digest of the installed APK, signs that digest with
 * the Keystore key, and stores both values in private SharedPreferences.  On every subsequent
 * service start the same computation is performed and both the raw hash and the HMAC are compared
 * against the stored values.
 *
 * KEY ALIAS   : shield_integrity_key
 * PREFS FILE  : shield_integrity
 * PREFS KEYS  : apk_hash_b64  – Base64(SHA-256(APK bytes))
 *               baseline_hmac – Base64(HMAC-SHA256(apk_hash_b64 bytes))
 *
 * BOUNDARY CONDITION — Device Admin key wipe:
 *   If an MDM profile or the user's Device Admin wipes all Keystore keys, any subsequent call to
 *   Mac.init(key) will throw KeyPermanentlyInvalidatedException.  This is caught SEPARATELY
 *   before the general Exception catch in verify(), returning KEY_INVALIDATED so the caller
 *   can regenerate credentials and continue instead of falsely flagging APK_TAMPERED.
 */
public final class ShieldIntegrityManager {

    private static final String TAG = "SHIELD_INTEGRITY";
    private static final String KEY_ALIAS = "shield_integrity_key";
    private static final String PREFS_NAME = "shield_integrity";
    private static final String PREF_APK_HASH = "apk_hash_b64";
    private static final String PREF_BASELINE_HMAC = "baseline_hmac";
    private static final String PREF_FIRST_RUN = "first_run_complete";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private ShieldIntegrityManager() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates the Keystore key (StrongBox first, TEE fallback), computes the APK hash,
     * signs it, and persists both values.  Safe to call multiple times.
     */
    public static void initialize(Context context) {
        try {
            generateKey(context);
            String apkHashB64 = computeApkHashB64(context);
            String baselineHmac = computeHmac(apkHashB64);
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_APK_HASH, apkHashB64)
                    .putString(PREF_BASELINE_HMAC, baselineHmac)
                    .putBoolean(PREF_FIRST_RUN, true)
                    .apply();
            Log.i(TAG, "Integrity baseline established (strongbox=" + hasStrongBox(context) + ")");
        } catch (Exception e) {
            Log.e(TAG, "initialize() failed", e);
        }
    }

    /**
     * Verifies the current APK against the stored baseline.
     *
     * Steps:
     *  1. Load stored apk_hash_b64 and baseline_hmac from SharedPreferences.
     *  2. Recompute current APK SHA-256 → currentHashB64.
     *  3. Recompute HMAC of currentHashB64 using the Keystore key → currentHmac.
     *  4. Compare currentHmac to baseline_hmac.
     *  5. Additionally compare raw hashes to detect a forged baseline.
     */
    public static IntegrityResult verify(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String storedHash = prefs.getString(PREF_APK_HASH, null);
        String storedHmac = prefs.getString(PREF_BASELINE_HMAC, null);

        if (storedHash == null || storedHmac == null) {
            Log.w(TAG, "No baseline found — key missing");
            return IntegrityResult.TEE_KEY_MISSING;
        }

        try {
            String currentHashB64 = computeApkHashB64(context);
            String currentHmac = computeHmac(currentHashB64);

            boolean hmacMatch = currentHmac.equals(storedHmac);
            boolean hashMatch = currentHashB64.equals(storedHash);

            if (hmacMatch && !hashMatch) {
                // HMAC ok but hash differs — stored hash was replaced
                return IntegrityResult.BASELINE_FORGED;
            } else if (!hmacMatch && !hashMatch) {
                // Both differ — APK was modified
                return IntegrityResult.APK_TAMPERED;
            } else if (!hmacMatch) {
                // HMAC fails but hash same — HMAC record was tampered
                return IntegrityResult.BASELINE_FORGED;
            } else {
                return IntegrityResult.CLEAN;
            }
        } catch (KeyPermanentlyInvalidatedException e) {
            // SEPARATELY caught — Device Admin wipe or screen-lock removal invalidated the key.
            Log.e(TAG, "Keystore key permanently invalidated", e);
            return IntegrityResult.KEY_INVALIDATED;
        } catch (Exception e) {
            Log.e(TAG, "verify() unexpected error", e);
            return IntegrityResult.TEE_KEY_MISSING;
        }
    }

    /**
     * Deletes the Keystore entry and SharedPreferences, then re-initializes.
     * Use after KEY_INVALIDATED or TEE_KEY_MISSING.
     */
    public static void regenerate(Context context) {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS);
                Log.i(TAG, "Old Keystore entry deleted");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete old key", e);
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        initialize(context);
    }

    /** Returns true if initialize() has never completed successfully on this device. */
    public static boolean isFirstRun(Context context) {
        return !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_FIRST_RUN, false);
    }

    /** Returns true if the device hardware supports a StrongBox-backed Keystore. */
    public static boolean hasStrongBox(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void generateKey(Context context) throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return;  // Already exists

        boolean generated = tryGenerateKey(hasStrongBox(context));
        if (!generated) {
            Log.w(TAG, "StrongBox unavailable — using TEE-backed key");
            tryGenerateKey(false);
        }
    }

    private static boolean tryGenerateKey(boolean strongBox) {
        try {
            KeyGenParameterSpec.Builder specBuilder = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(256)
                    .setDigests(KeyProperties.DIGEST_SHA256);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBox) {
                specBuilder.setIsStrongBoxBacked(true);
            }

            KeyGenerator keyGen = KeyGenerator.getInstance(HMAC_ALGORITHM, ANDROID_KEYSTORE);
            keyGen.init(specBuilder.build());
            keyGen.generateKey();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Key generation failed (strongBox=" + strongBox + ")", e);
            return false;
        }
    }

    private static String computeApkHashB64(Context context) throws Exception {
        File apkFile = new File(context.getPackageCodePath());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (FileInputStream fis = new FileInputStream(apkFile)) {
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP);
    }

    private static String computeHmac(String data) throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(key);
        return Base64.encodeToString(mac.doFinal(data.getBytes("UTF-8")), Base64.NO_WRAP);
    }
}
