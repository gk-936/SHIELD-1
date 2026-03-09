package com.dearmoon.shield.modea;

/**
 * ShieldEventData — plain data class holding one deserialized shield_event.
 *
 * Field names MUST match the JNI field lookups in modea_jni.cpp exactly.
 * Used by ModeAJni.nativeReadEvent() to return event data to Java without
 * creating a JNI object on every call (the caller allocates one instance
 * and reuses it across the read loop).
 */
public class ShieldEventData {
    public int    pid;
    public int    uid;
    public long   timestamp;     /* ktime_get_ns() — nanoseconds since boot */
    public int    bytes;
    public String operation;     /* "READ", "WRITE", "FSYNC", "EXEC"        */
    public String filename;      /* absolute path (best-effort)              */

    public ShieldEventData() {
        operation = "";
        filename  = "";
    }

    @Override
    public String toString() {
        return operation + " pid=" + pid + " uid=" + uid
                + " file=" + filename + " bytes=" + bytes;
    }
}
