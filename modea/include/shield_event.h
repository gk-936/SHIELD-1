/* SPDX-License-Identifier: GPL-2.0 */
/*
 * shield_event.h — shared event structure for SHIELD Mode-A
 *
 * Used by:
 *   - eBPF kernel program  (shield_bpf.c)
 *   - Root daemon          (modea_daemon.cpp / bpf_loader.cpp)
 *   - JNI bridge           (modea_jni.cpp)
 *   - Android service      (ModeAFileCollector.kt — via JNI)
 *
 * All fields use fixed-width types so the struct is layout-identical
 * in both 32-bit ARM and 64-bit ARM64 userspace processes.
 */
#pragma once

#include <linux/types.h>

/* -----------------------------------------------------------------------
 * Event type tags — stored in shield_event.operation
 * ----------------------------------------------------------------------- */
#define SHIELD_OP_READ   "READ"
#define SHIELD_OP_WRITE  "WRITE"
#define SHIELD_OP_FSYNC  "FSYNC"
#define SHIELD_OP_EXEC   "EXEC"

/* -----------------------------------------------------------------------
 * Maximum path length stored in the event.
 * Keeping it at 256 limits per-event stack usage in the eBPF verifier.
 * ----------------------------------------------------------------------- */
#define SHIELD_FILENAME_LEN  256
#define SHIELD_OP_LEN         16

/* -----------------------------------------------------------------------
 * Primary telemetry event — emitted by eBPF and consumed by the daemon.
 *
 * Size: 4 + 4 + 8 + 16 + 256 + 4 = 292 bytes (plus potential padding).
 * Packed to avoid ABI surprises across kernel/user boundary.
 * ----------------------------------------------------------------------- */
struct shield_event {
    __u32  pid;                          /* Thread-group leader PID                */
    __u32  uid;                          /* Effective UID of the process            */
    __u64  timestamp;                    /* ktime_get_ns() nanoseconds since boot   */
    char   operation[SHIELD_OP_LEN];     /* "READ", "WRITE", "FSYNC", "EXEC"       */
    char   filename[SHIELD_FILENAME_LEN];/* Absolute path (best-effort from kernel) */
    __u32  bytes;                        /* Bytes transferred (0 for EXEC/FSYNC)   */
} __attribute__((packed));

/* -----------------------------------------------------------------------
 * Compile-time size assertion — caught by both gcc/clang.
 * Expected: 4+4+8+16+256+4 = 292 bytes.
 * ----------------------------------------------------------------------- */
#ifndef __BPF__
_Static_assert(sizeof(struct shield_event) == 292,
               "shield_event size mismatch — update daemon serialisation");
#endif
