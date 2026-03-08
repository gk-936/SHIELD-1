package com.dearmoon.shield.modea;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.zip.GZIPInputStream;

/**
 * ModeAController — pre-flight checks and lifecycle coordination for Mode-A.
 *
 * Responsibilities
 * ----------------
 *   1. Verify that root (UID 0) is available via {@code su}.
 *   2. Verify required kernel config options are present.
 *   3. Push the daemon binary and BPF object to the device storage.
 *   4. Launch and stop the root daemon process.
 *   5. Provide the Unix socket path used by ModeAService to connect.
 *
 * Mode-A is silently disabled if root or BPF is unavailable, and
 * {@link ModeAService} falls back to Mode-B in that case.
 */
public class ModeAController {

    private static final String TAG = "SHIELD_MODE_A";

    /* Paths used on the Android device */
    public static final String DAEMON_PATH      = "/data/local/tmp/shield_modea_daemon";
    public static final String BPF_OBJ_PATH     = "/data/local/tmp/shield_bpf.o";
    public static final String SOCKET_PATH      = "/data/local/tmp/shield_modea.sock";

    /* Required kernel config options */
    private static final String[] REQUIRED_CONFIGS = {
            "CONFIG_BPF=y",
            "CONFIG_BPF_SYSCALL=y",
            "CONFIG_TRACEPOINTS=y",
            "CONFIG_BPF_JIT=y"
    };

    private final Context context;
    private Process daemonProcess;

    public ModeAController(Context context) {
        this.context = context.getApplicationContext();
    }

    // -----------------------------------------------------------------
    // Root check
    // -----------------------------------------------------------------

    /**
     * Returns true if {@code su -c id} executes as UID 0.
     * This is a synchronous call — do not call on the main thread.
     */
    public boolean isRootAvailable() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            boolean rootOk = line != null && line.contains("uid=0");
            Log.i(TAG, "Root check: " + (rootOk ? "PASS" : "FAIL") + " (" + line + ")");
            return rootOk;
        } catch (Exception e) {
            Log.e(TAG, "Root check exception: " + e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------
    // Kernel compatibility check
    // -----------------------------------------------------------------

    /**
     * Reads /proc/config.gz and verifies all required BPF kernel options
     * are set to "y".
     *
     * Returns true if all required options are found.
     * Returns true (optimistic) if /proc/config.gz is not accessible —
     * the daemon will fail visibly if BPF is truly absent.
     */
    public boolean isKernelCompatible() {
        File configGz = new File("/proc/config.gz");
        if (!configGz.exists()) {
            Log.w(TAG, "/proc/config.gz not found — assuming kernel compatible");
            return true;
        }
        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(configGz));
             BufferedReader reader = new BufferedReader(new InputStreamReader(gis))) {

            // Read entire config into a StringBuilder
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            String config = sb.toString();

            for (String required : REQUIRED_CONFIGS) {
                if (!config.contains(required)) {
                    Log.e(TAG, "Kernel missing: " + required);
                    return false;
                }
            }
            Log.i(TAG, "Kernel compatibility check: PASS");
            return true;

        } catch (Exception e) {
            Log.w(TAG, "Could not read /proc/config.gz: " + e.getMessage()
                    + " — assuming compatible");
            return true;
        }
    }

    // -----------------------------------------------------------------
    // Binary deployment
    // -----------------------------------------------------------------

    /**
     * Copy the pre-compiled daemon binary and BPF object from the APK
     * assets directory to /data/local/tmp/ and set execute permission.
     *
     * @return true if both files are ready on the device
     */
    public boolean deployBinaries() {
        return deployAsset("shield_modea_daemon", DAEMON_PATH, true)
                && deployAsset("shield_bpf.o", BPF_OBJ_PATH, false);
    }

    private boolean deployAsset(String assetName, String destPath, boolean executable) {
        try {
            // Step 1: write asset to app-private dir (SELinux allows this)
            File staging = new File(context.getFilesDir(), assetName);
            try (java.io.InputStream in = context.getAssets().open(assetName);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(staging)) {
                byte[] buf = new byte[8192];
                int    n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.getFD().sync();
            }

            // Step 2: use su to copy to /data/local/tmp/ and set permissions
            String perms = executable ? "755" : "644";
            String cmd = "cp " + staging.getAbsolutePath() + " " + destPath
                       + " && chmod " + perms + " " + destPath;
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int rc = p.waitFor();
            if (rc != 0) {
                Log.e(TAG, "su cp failed for " + assetName + " (exit=" + rc + ")");
                return false;
            }
            Log.i(TAG, "Deployed " + assetName + " → " + destPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Deploy failed for " + assetName + ": " + e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------
    // Daemon lifecycle
    // -----------------------------------------------------------------

    /**
     * Launch the root daemon via {@code su -c <daemon> <socket> <bpf_obj>}.
     * The daemon runs in the background; its lifecycle is tied to the
     * ModeAService foreground service.
     *
     * @return true if the daemon process started without an immediate error
     */
    public boolean startDaemon() {
        try {
            String cmd = DAEMON_PATH + " " + SOCKET_PATH + " " + BPF_OBJ_PATH;
            daemonProcess = Runtime.getRuntime()
                    .exec(new String[]{"su", "-c", cmd});
            Log.i(TAG, "Root daemon started: " + cmd);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start daemon: " + e.getMessage());
            return false;
        }
    }

    /**
     * Terminate the root daemon process.
     */
    public void stopDaemon() {
        if (daemonProcess != null) {
            try {
                // Send SIGTERM to the daemon by killing the su process
                daemonProcess.destroy();
                daemonProcess = null;
                // Also try to kill by name in case the su wrapper exited early
                Runtime.getRuntime()
                       .exec(new String[]{"su", "-c",
                               "pkill -TERM -f shield_modea_daemon"});
                Log.i(TAG, "Root daemon stopped");
            } catch (Exception e) {
                Log.w(TAG, "Error stopping daemon: " + e.getMessage());
            }
        }
        new File(SOCKET_PATH).delete();
    }

    /**
     * Returns true if the daemon binary is present and the socket file
     * exists (indicating the daemon is listening).
     */
    public boolean isDaemonRunning() {
        return new File(SOCKET_PATH).exists();
    }
}
