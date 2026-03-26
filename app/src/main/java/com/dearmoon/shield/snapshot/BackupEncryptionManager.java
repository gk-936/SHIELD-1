package com.dearmoon.shield.snapshot;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// Backup encryption manager
public class BackupEncryptionManager {

    private static final String TAG = "BackupEncryptionMgr";

    // Keystore aliases
    private static final String MASTER_KEY_ALIAS   = "shield_backup_master_v1";
    private static final String DB_COL_KEY_ALIAS   = "shield_db_column_v1";

    private static final String KEYSTORE_PROVIDER  = "AndroidKeyStore";
    private static final String TRANSFORMATION     = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM       = KeyProperties.KEY_ALGORITHM_AES;
    private static final String BLOCK_MODE          = KeyProperties.BLOCK_MODE_GCM;
    private static final String PADDING             = KeyProperties.ENCRYPTION_PADDING_NONE;

    private static final int IV_SIZE   = 12;   // 96-bit IV for GCM
    private static final int TAG_BITS  = 128;  // 128-bit GCM authentication tag
    private static final int AES_BITS  = 256;

    // -------------------------------------------------------------------------

    public BackupEncryptionManager(Context context) {
        try {
            ensureKeystoreKey(MASTER_KEY_ALIAS);
            ensureKeystoreKey(DB_COL_KEY_ALIAS);
        } catch (Exception e) {
            Log.e(TAG, "Keystore init failed – encryption unavailable", e);
        }
    }

    // Per-snapshot keys

    // Generate fresh key
    public byte[] generateAndWrapSnapshotKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(AES_BITS, new SecureRandom());
        SecretKey snapshotKey = kg.generateKey();
        return wrapWithMasterKey(snapshotKey.getEncoded());
    }

    // Recover snapshot key
    public SecretKey unwrapSnapshotKey(byte[] wrappedKey) throws Exception {
        byte[] rawKey = unwrapWithMasterKey(wrappedKey);
        return new SecretKeySpec(rawKey, "AES");
    }

    // File encryption logic

    // Encrypt source file
    public void encryptFile(File src, File dst, SecretKey key) throws Exception {
        byte[] iv = randomIV();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

        dst.getParentFile().mkdirs();
        try (FileInputStream  fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            fos.write(iv);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                byte[] out = cipher.update(buf, 0, n);
                if (out != null) fos.write(out);
            }
            byte[] fin = cipher.doFinal();
            if (fin != null) fos.write(fin);
        }
    }

    // Decrypt backup file
    public void decryptFile(File encryptedSrc, File dst, byte[] wrappedKey) throws Exception {
        SecretKey key = unwrapSnapshotKey(wrappedKey);

        try (FileInputStream fis = new FileInputStream(encryptedSrc)) {
            byte[] iv = new byte[IV_SIZE];
            int read = fis.read(iv);
            if (read != IV_SIZE) throw new IOException("Truncated IV in encrypted backup: " + encryptedSrc);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

            dst.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    byte[] out = cipher.update(buf, 0, n);
                    if (out != null) fos.write(out);
                }
                byte[] fin = cipher.doFinal();   // throws AEADBadTagException if tampered
                if (fin != null) fos.write(fin);
            }
        }
    }

    // DB column encryption

    // Encrypt DB column
    public String encryptColumn(String plaintext) throws Exception {
        if (plaintext == null) return null;
        SecretKey key = getKeystoreKey(DB_COL_KEY_ALIAS);
        byte[] iv = randomIV();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes("UTF-8"));

        ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
        buf.put(iv);
        buf.put(ct);
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP);
    }

    // Decrypt DB column
    public String decryptColumn(String encoded) throws Exception {
        if (encoded == null) return null;
        byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
        ByteBuffer buf = ByteBuffer.wrap(data);
        byte[] iv = new byte[IV_SIZE];
        buf.get(iv);
        byte[] ct = new byte[buf.remaining()];
        buf.get(ct);

        SecretKey key = getKeystoreKey(DB_COL_KEY_ALIAS);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(ct), "UTF-8");
    }

    // Internal Helpers

    private void ensureKeystoreKey(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        if (!ks.containsAlias(alias)) {
            KeyGenerator kg = KeyGenerator.getInstance(KEY_ALGORITHM, KEYSTORE_PROVIDER);
            kg.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(BLOCK_MODE)
                    .setEncryptionPaddings(PADDING)
                    .setKeySize(AES_BITS)
                    .setRandomizedEncryptionRequired(false)  // Allow manual IV injection (required for our architecture)
                    .setUserAuthenticationRequired(false)
                    .build());
            kg.generateKey();
            Log.i(TAG, "Created Keystore key: " + alias);
        }
    }

    private SecretKey getKeystoreKey(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        return (SecretKey) ks.getKey(alias, null);
    }

    // Wrap raw key
    private byte[] wrapWithMasterKey(byte[] rawKey) throws Exception {
        SecretKey master = getKeystoreKey(MASTER_KEY_ALIAS);
        byte[] iv = randomIV();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, master, new GCMParameterSpec(TAG_BITS, iv));
        byte[] wrapped = cipher.doFinal(rawKey);

        ByteBuffer buf = ByteBuffer.allocate(iv.length + wrapped.length);
        buf.put(iv);
        buf.put(wrapped);
        return buf.array();
    }

    // Unwrap raw key
    private byte[] unwrapWithMasterKey(byte[] data) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(data);
        byte[] iv = new byte[IV_SIZE];
        buf.get(iv);
        byte[] wrapped = new byte[buf.remaining()];
        buf.get(wrapped);

        SecretKey master = getKeystoreKey(MASTER_KEY_ALIAS);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, master, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(wrapped);
    }

    private byte[] randomIV() {
        byte[] iv = new byte[IV_SIZE];
        new SecureRandom().nextBytes(iv);
        return iv;
    }
}
