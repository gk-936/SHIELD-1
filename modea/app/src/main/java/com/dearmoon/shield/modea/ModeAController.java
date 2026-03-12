package com.dearmoon.shield.modea;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.zip.GZIPInputStream;

/**
 * ModeAController — pre-flight checks and daemon lifecycle for Mode-A.
 *
 * Merge note: this class has no imports from com.dearmoon.shield.* — it
 * can be moved to the main project as-is.
 */
public class ModeAController {

    private static final String TAG = "SHIELD_MODE_A";

    public static final String DAEMON_PATH       = "/data/local/tmp/shield_modea_daemon";
    public static final String BPF_OBJ_PATH      = "/data/local/tmp/shield_bpf.o";
    // C-04: Moved from world-accessible /data/local/tmp/ to app-private directory
    public static final String SOCKET_PATH       = "/data/data/com.dearmoon.shield/shield_modea.sock";
    public static final String DAEMON_STDERR_PATH = "/data/local/tmp/shield_modea_daemon.log";

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

    // ------------------------------------------------------------------
    // Root check
    // ------------------------------------------------------------------

    /** Returns true if su gives uid=0.  Do NOT call on main thread. */
    public boolean isRootAvailable() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            boolean ok = line != null && line.contains("uid=0");
            Log.i(TAG, "Root check: " + (ok ? "PASS" : "FAIL") + " → " + line);
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "Root check exception: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Kernel compatibility check
    // ------------------------------------------------------------------

    /**
     * Reads /proc/config.gz and verifies required BPF kernel options.
     * Returns true (optimistic) if config.gz is inaccessible.
     */
    public boolean isKernelCompatible() {
        File configGz = new File("/proc/config.gz");
        if (!configGz.exists()) {
            Log.w(TAG, "/proc/config.gz not found — assuming compatible");
            return true;
        }
        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(configGz));
             BufferedReader reader = new BufferedReader(new InputStreamReader(gis))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            String config = sb.toString();

            for (String req : REQUIRED_CONFIGS) {
                if (!config.contains(req)) {
                    Log.e(TAG, "Kernel missing: " + req);
                    return false;
                }
            }
            Log.i(TAG, "Kernel compatibility: PASS");
            return true;

        } catch (Exception e) {
            Log.w(TAG, "Cannot read /proc/config.gz: " + e.getMessage() + " — assuming compatible");
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Binary deployment  (from APK assets → /data/local/tmp/)
    // ------------------------------------------------------------------

    /**
     * Copy daemon binary and BPF object to device storage.
     *
     * Two deployment paths are supported:
     *  1. Via deploy.sh / build_real.sh (WSL): binaries are pushed directly to
     *     /data/local/tmp/ by adb.  If they already exist we skip the asset copy.
     *  2. Via APK assets: binaries were placed in app/src/main/assets/ before
     *     the APK was built (build_real.sh does this automatically).
     */
    public boolean deployBinaries() {
        File daemon = new File(DAEMON_PATH);
        File bpfObj = new File(BPF_OBJ_PATH);

        // If already pre-deployed (e.g. by build_real.sh / deploy.sh via adb push), skip copy.
        if (daemon.exists() && bpfObj.exists() && daemon.length() > 0 && bpfObj.length() > 0) {
            Log.i(TAG, "Binaries already present at target paths — skipping asset deploy");
            return true;
        }

        return deployAsset("shield_modea_daemon", DAEMON_PATH, true)
                && deployAsset("shield_bpf.o",    BPF_OBJ_PATH, false);
    }

    private boolean deployAsset(String assetName, String destPath, boolean executable) {
        try {
            // Step 1: write asset to app-private dir (SELinux allows this)
            File staging = new File(context.getFilesDir(), assetName);
            try (java.io.InputStream in = context.getAssets().open(assetName);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(staging)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
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
            Log.i(TAG, "Deployed asset: " + assetName + " → " + destPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Deploy failed for " + assetName + ": " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Daemon lifecycle
    // ------------------------------------------------------------------

    /** Launch daemon via su. Returns true if process started without immediate error. */
    public boolean startDaemon() {
        try {
            // Inject SELinux rules so untrusted_app can connect to the daemon socket.
            // Required because the socket is created under the magisk domain and
            // untrusted_app is denied write+connectto by default stock policy.
            String policyCmd =
                "magiskpolicy --live " +
                "'allow untrusted_app shell_data_file sock_file { read write }' " +
                "'allow untrusted_app magisk unix_stream_socket connectto'";
            Process pol = Runtime.getRuntime().exec(new String[]{"su", "-c", policyCmd});
            int polRc = pol.waitFor();
            if (polRc != 0) {
                Log.w(TAG, "magiskpolicy returned " + polRc + " — continuing anyway");
            } else {
                Log.i(TAG, "SELinux policy patched for socket access");
            }

            // Redirect daemon stderr to a temp file so we can read it on failure.
            String cmd = DAEMON_PATH + " " + SOCKET_PATH + " " + BPF_OBJ_PATH
                       + " " + android.os.Process.myUid()
                       + " 2>" + DAEMON_STDERR_PATH;
            daemonProcess = Runtime.getRuntime()
                    .exec(new String[]{"su", "-c", cmd});
            Log.i(TAG, "Root daemon launched: " + cmd);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Daemon start failed: " + e.getMessage());
            return false;
        }
    }

    /** Read the daemon's stderr log (useful when socket never appears). */
    public String readDaemonStderr() {
        try {
            Process p = Runtime.getRuntime()
                    .exec(new String[]{"su", "-c", "cat " + DAEMON_STDERR_PATH});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.length() > 0 ? sb.toString() : "(empty)";
        } catch (Exception e) {
            return "(could not read: " + e.getMessage() + ")";
        }
    }

    /** Terminate the root daemon. */
    public void stopDaemon() {
        if (daemonProcess != null) {
            try {
                daemonProcess.destroy();
                daemonProcess = null;
                Runtime.getRuntime()
                       .exec(new String[]{"su", "-c", "pkill -TERM -f shield_modea_daemon"});
                Log.i(TAG, "Root daemon stopped");
            } catch (Exception e) {
                Log.w(TAG, "Error stopping daemon: " + e.getMessage());
            }
        }
        new File(SOCKET_PATH).delete();
    }

    /** True if the daemon socket file exists (daemon is listening). */
    public boolean isDaemonRunning() {
        return new File(SOCKET_PATH).exists();
    }
}
