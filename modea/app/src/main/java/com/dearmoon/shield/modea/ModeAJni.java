package com.dearmoon.shield.modea;

/**
 * ModeAJni — Java wrapper around the native modea_jni.so library.
 *
 * The shared library is built by CMake (modea/build/CMakeLists.txt)
 * and packaged into the APK via app/src/main/jniLibs/.
 */
public class ModeAJni {

    static {
        System.loadLibrary("modea_jni");
    }

    /**
     * Open a connection to the root daemon Unix domain socket.
     * @return true if connection established
     */
    public native boolean nativeConnect(String socketPath);

    /** Close the open connection. Safe to call when not connected. */
    public native void nativeDisconnect();

    /**
     * Send a KILL command for the given PID to the daemon.
     * @return true if command was sent successfully
     */
    public native boolean nativeSendKill(int pid);

    /**
     * Non-blocking read of the next shield_event from the socket.
     * Fills {@code eventOut} in-place.
     * @return true if an event was read; false if no event available
     */
    public native boolean nativeReadEvent(ShieldEventData eventOut);
}
