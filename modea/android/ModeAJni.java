package com.dearmoon.shield.modea;

/**
 * ModeAJni — thin Java wrapper around the native modea_jni.so library.
 *
 * The native library is built by CMake (see modea/build/CMakeLists.txt)
 * and pushed to the device alongside the daemon binary during setup.
 *
 * All native methods are non-blocking or connect/disconnect helpers.
 * Event reading is done on a dedicated background thread inside
 * ModeAService — never on the main thread.
 */
public class ModeAJni {

    static {
        System.loadLibrary("modea_jni");
    }

    /**
     * Open a connection to the root daemon Unix domain socket.
     *
     * @param socketPath  absolute path of the Unix socket
     *                    (e.g. /data/local/tmp/shield_modea.sock)
     * @return true if the connection was established successfully
     */
    public native boolean nativeConnect(String socketPath);

    /**
     * Close the open connection to the daemon.
     * Safe to call even if not connected.
     */
    public native void nativeDisconnect();

    /**
     * Send a KILL command for the given PID to the daemon.
     * The daemon will call kill(pid, SIGKILL).
     *
     * @param pid  process ID to kill
     * @return true if the command was sent successfully
     */
    public native boolean nativeSendKill(int pid);

    /**
     * Non-blocking read of the next available shield_event.
     * Fills {@code eventOut} in-place.
     *
     * @param eventOut  caller-allocated ShieldEventData to fill
     * @return true if an event was read; false if no event is available
     *         or the connection has been lost
     */
    public native boolean nativeReadEvent(ShieldEventData eventOut);
}
