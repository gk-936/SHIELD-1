/* SPDX-License-Identifier: GPL-2.0 */
/*
 * pid_activity.h — per-PID burst-tracking structure for SHIELD Mode-A
 *
 * Stored as values in the BPF_MAP_TYPE_HASH map keyed by PID (__u32).
 * The daemon also maintains a userspace shadow of this map between
 * ring-buffer polls to calculate sliding-window burst thresholds.
 *
 * Design intent
 * -------------
 * Ransomware exhibits burst patterns characteristic of a mass-file
 * encryption campaign:
 *   - high write_count over a short window  → encryption loop running
 *   - high read_count  preceding writes     → directory enumeration
 *   - high fsync_count after writes         → forcing disk commits
 *
 * Thresholds are evaluated in userspace (daemon) to keep verifier
 * complexity inside the BPF program at zero.
 */
#pragma once

#include <linux/types.h>

/* -----------------------------------------------------------------------
 * Burst detection thresholds (evaluated in userspace daemon)
 * ----------------------------------------------------------------------- */
#define BURST_WRITE_THRESHOLD    50  /* mass-write burst threshold (encryption loop) */
#define BURST_READ_THRESHOLD     50  /* mass-read burst threshold (dir enumeration)  */
#define BURST_FSYNC_THRESHOLD    20  /* mass-fsync threshold (forced disk commits)   */
#define BURST_WINDOW_SEC         5   /* sliding window length in seconds */

/* -----------------------------------------------------------------------
 * Per-PID activity record — value type in shield_pid_activity BPF map
 * ----------------------------------------------------------------------- */
struct pid_activity {
    __u32  read_count;       /* Total read events observed for this PID   */
    __u32  write_count;      /* Total write events observed for this PID  */
    __u32  fsync_count;      /* Total fsync events observed for this PID  */
    __u32  exec_count;       /* Number of exec calls (usually 1 per PID)  */
    __u64  last_timestamp;   /* ktime_get_ns() of most recent event       */
    __u64  window_start_ns;  /* ktime_get_ns() when current window opened */
    __u32  burst_flags;      /* Bitmask: bit0=read_burst, bit1=write_burst,
                              *          bit2=fsync_burst                  */
    __u32  uid;              /* UID of the process (from bpf_get_current_uid_gid) */
} __attribute__((packed));

/* burst_flags bit positions */
#define BURST_FLAG_READ   (1u << 0)
#define BURST_FLAG_WRITE  (1u << 1)
#define BURST_FLAG_FSYNC  (1u << 2)

/* -----------------------------------------------------------------------
 * Compile-time size check (userspace only — not available in BPF)
 * Expected: 4+4+4+4+8+8+4+4 = 40 bytes
 * ----------------------------------------------------------------------- */
#ifndef __BPF__
_Static_assert(sizeof(struct pid_activity) == 40,
               "pid_activity size mismatch");
#endif
