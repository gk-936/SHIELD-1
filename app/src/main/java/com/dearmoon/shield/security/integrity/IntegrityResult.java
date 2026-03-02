package com.dearmoon.shield.security.integrity;

/**
 * Represents the outcome of a TEE-anchored APK integrity verification pass.
 *
 * CLEAN                  – APK hash matches the HMAC-signed baseline; no action needed.
 * APK_TAMPERED           – Recomputed APK hash differs from the stored baseline.
 * BASELINE_FORGED        – Stored baseline HMAC fails re-verification; record manipulated externally.
 * TEE_KEY_MISSING        – Keystore alias not found; first-run or key deleted outside the app.
 * KEY_INVALIDATED        – KeyPermanentlyInvalidatedException thrown (new biometric / lock-screen
 *                          change). Regenerate key + baseline and proceed.
 * STRONGBOX_UNAVAILABLE  – Device lacks StrongBox; fell back to TEE-only key. Non-fatal.
 */
public enum IntegrityResult {
    CLEAN,
    APK_TAMPERED,
    BASELINE_FORGED,
    TEE_KEY_MISSING,
    KEY_INVALIDATED,
    STRONGBOX_UNAVAILABLE
}
