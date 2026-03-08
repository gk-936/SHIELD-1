package com.dearmoon.shield.modea;

/**
 * ShieldEventData — plain data class holding one deserialized shield_event.
 *
 * Field names MUST match the JNI field lookups in modea_jni.cpp exactly.
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
