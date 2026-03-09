// SPDX-License-Identifier: GPL-2.0
/*
 * shield_bpf.c — SHIELD Mode-A eBPF telemetry program
 *
 * Kernel compatibility: 4.9+ (tested on 4.14/Android 12)
 *
 * Attaches to four Android-kernel tracepoints:
 *   tracepoint/android_fs/android_fs_datawrite_start
 *   tracepoint/android_fs/android_fs_dataread_start
 *   tracepoint/android_fs/android_fs_fsync_start
 *   tracepoint/sched/sched_process_exec
 *
 * Design:
 * - Uses legacy struct bpf_map_def SEC("maps") format (no BTF/CO-RE needed).
 * - No ring buffer (requires kernel 5.8+); instead the daemon polls the
 *   shield_pid_activity hash map for burst_flags every ~500 ms.
 * - Uses bpf_probe_read_str() (kernel 4.11+) instead of bpf_probe_read_kernel_str().
 *
 * Build:
 *   clang -target bpf -O2 -g \
 *         -I../include \
 *         -D__BPF__ \
 *         -c shield_bpf.c -o shield_bpf.o
 */

/* Tell headers we are in BPF context (build system may pass -D__BPF__ too) */
#ifndef __BPF__
#define __BPF__
#endif

#include <linux/bpf.h>
#include <linux/ptrace.h>
#include <linux/types.h>
#include <bpf/bpf_helpers.h>

#include "../include/shield_event.h"
#include "../include/pid_activity.h"

/* =========================================================================
 * BPF MAP DEFINITIONS — legacy struct format, kernel 4.9+ compatible
 *
 * SEC("maps") places these in the "maps" ELF section.  The loader reads
 * struct bpf_map_def directly (no BTF required).
 * ========================================================================= */

/*
 * 1. PID activity map — tracks reads/writes/fsyncs per PID.
 *    The daemon polls this map every ~500 ms to detect burst patterns.
 *    Key:   __u32 pid
 *    Value: struct pid_activity
 *    Pinned at /sys/fs/bpf/shield_pid_activity by the loader.
 */
struct bpf_map_def SEC("maps") shield_pid_activity = {
    .type        = BPF_MAP_TYPE_HASH,
    .key_size    = sizeof(__u32),
    .value_size  = sizeof(struct pid_activity),
    .max_entries = 4096,
};

/*
 * 2. Suspect PID map — PIDs flagged by the daemon's detection engine.
 *    Key:   __u32 pid
 *    Value: __u32 flags (reserved for future use)
 *    Pinned at /sys/fs/bpf/shield_suspect_pids by the loader.
 */
struct bpf_map_def SEC("maps") shield_suspect_pids = {
    .type        = BPF_MAP_TYPE_HASH,
    .key_size    = sizeof(__u32),
    .value_size  = sizeof(__u32),
    .max_entries = 256,
};

/* =========================================================================
 * TRACEPOINT CONTEXTS
 *
 * Android FS tracepoints expose the following fields (from trace_events.h):
 *   android_fs_datawrite_start / android_fs_dataread_start:
 *     struct inode *inode
 *     loff_t        offset
 *     int           bytes
 *     pid_t         i_uid    (effective UID of the filesystem inode owner)
 *     char         *pathname  (pointer into dentry cache — use probe_read_str)
 *     pid_t         pid
 *     char         *cmdline
 *
 *   android_fs_fsync_start:
 *     struct inode *inode
 *     pid_t         pid
 *     char         *pathname
 * Note: use bpf_probe_read_str() — available since kernel 4.11.
 *       bpf_probe_read_kernel_str() was added in 5.5 and is NOT available
 *       on Android 12 GKI 4.14 devices.
 *
 * sched_process_exec:
 *     char         *filename   (new binary path)
 *     pid_t         pid
 *     pid_t         old_pid
 *
 * We declare minimal structs matching the ABI rather than including
 * kernel headers that may not be available in the NDK sysroot.
 * ========================================================================= */

struct android_fs_rw_args {
    /* common fields (8 bytes) */
    __u64  pad;
    /* trace-specific fields */
    void  *inode;
    __s64  offset;
    __s32  bytes;
    __u32  i_uid;
    __u32  pathname_loc;  /* __data_loc: bits[15:0]=offset from ctx, bits[31:16]=len */
    __u32  pid;
    __u32  cmdline_loc_;  /* __data_loc for cmdline */
};

struct android_fs_fsync_args {
    __u64  pad;
    void  *inode;
    __u32  pid;
    __u32  pathname_loc;  /* __data_loc */
};

struct sched_exec_args {
    __u64  pad;
    char  *filename;      /* pointer into kernel memory */
    __u32  pid;
    __u32  old_pid;
};

/* =========================================================================
 * HELPERS — inline, verifier-friendly
 * ========================================================================= */

/*
 * uid_is_app — returns non-zero if uid belongs to a regular Android app.
 * System UIDs are < 10000; ignore them to reduce noise.
 */
static __always_inline int uid_is_app(__u32 uid)
{
    return uid >= 10000;
}

/*
 * path_is_user_storage — coarse prefix check via byte comparison.
 *
 * We check the first 8 characters of the path string against the two
 * monitored prefixes:
 *   /sdcard      → bytes: '/', 's', 'd', 'c', 'a', 'r', 'd'
 *   /storage/    → bytes: '/', 's', 't', 'o', 'r', 'a', 'g', 'e'
 *
 * This avoids loops and keeps stack usage well below 512 bytes.
 * False negatives (e.g. /mnt/sdcard) are acceptable — the daemon can
 * apply a richer filter in userspace.
 *
 * Returns 1 if the path is likely under user storage, 0 otherwise.
 */
static __always_inline int path_is_user_storage(const char path[SHIELD_FILENAME_LEN])
{
    /* /sdcard — 7 chars */
    if (path[0] == '/' && path[1] == 's' && path[2] == 'd' &&
        path[3] == 'c' && path[4] == 'a' && path[5] == 'r' &&
        path[6] == 'd')
        return 1;

    /* /storage/ — 9 chars with trailing slash in typical mount */
    if (path[0] == '/' && path[1] == 's' && path[2] == 't' &&
        path[3] == 'o' && path[4] == 'r' && path[5] == 'a' &&
        path[6] == 'g' && path[7] == 'e')
        return 1;

    return 0;
}

/*
 * update_pid_activity — increment counters and reset the sliding window
 * if BURST_WINDOW_SEC seconds have elapsed.  Called from every handler.
 */
static __always_inline void update_pid_activity(__u32 pid, __u32 uid, __u64 now,
                                                 int is_read, int is_write,
                                                 int is_fsync, int is_exec)
{
    struct pid_activity *act;
    struct pid_activity  zero = {};

    /* Look up or create entry */
    act = bpf_map_lookup_elem(&shield_pid_activity, &pid);
    if (!act) {
        bpf_map_update_elem(&shield_pid_activity, &pid, &zero, BPF_ANY);
        act = bpf_map_lookup_elem(&shield_pid_activity, &pid);
        if (!act)
            return; /* should never happen, but verifier requires the check */
    }

    /* Rotate window if expired */
    __u64 window_age_ns = now - act->window_start_ns;
    if (window_age_ns > (__u64)BURST_WINDOW_SEC * 1000000000ULL) {
        act->read_count   = 0;
        act->write_count  = 0;
        act->fsync_count  = 0;
        act->burst_flags  = 0;
        act->window_start_ns = now;
    }

    /* Increment counters */
    if (is_read)   act->read_count++;
    if (is_write)  act->write_count++;
    if (is_fsync)  act->fsync_count++;
    if (is_exec)   act->exec_count++;
    act->last_timestamp = now;
    act->uid = uid;

    /* Set burst flags — the daemon polls this map and reports when set */
    if (act->read_count  >= BURST_READ_THRESHOLD)  act->burst_flags |= BURST_FLAG_READ;
    if (act->write_count >= BURST_WRITE_THRESHOLD)  act->burst_flags |= BURST_FLAG_WRITE;
    if (act->fsync_count >= BURST_FSYNC_THRESHOLD)  act->burst_flags |= BURST_FLAG_FSYNC;
}

/* =========================================================================
 * TRACEPOINT HANDLERS
 *
 * No ring buffer is used.  Each handler only updates shield_pid_activity.
 * The userspace daemon polls the map every ~500 ms and synthesises events
 * when burst_flags is non-zero (then clears the flags to avoid duplicates).
 * ========================================================================= */

/* -----------------------------------------------------------------------
 * android_fs_datawrite_start
 * ----------------------------------------------------------------------- */
SEC("tracepoint/android_fs/android_fs_datawrite_start")
int tp_fs_write(struct android_fs_rw_args *ctx)
{
    __u64 pid_tgid = bpf_get_current_pid_tgid();
    __u32 pid = (__u32)(pid_tgid >> 32);
    __u64 uid_gid = bpf_get_current_uid_gid();
    __u32 uid = (__u32)(uid_gid & 0xffffffff);
    __u64 ts  = bpf_ktime_get_ns();

    /* Filter: only track app-range UIDs (>= 10000) */
    if (!uid_is_app(uid)) return 0;
    /* Filter: ignore tiny writes — ransomware encrypts in large chunks */
    if (ctx->bytes < 4096) return 0;
    update_pid_activity(pid, uid, ts, 0, 1, 0, 0);
    return 0;
}

/* -----------------------------------------------------------------------
 * android_fs_dataread_start
 * ----------------------------------------------------------------------- */
SEC("tracepoint/android_fs/android_fs_dataread_start")
int tp_fs_read(struct android_fs_rw_args *ctx)
{
    __u64 pid_tgid = bpf_get_current_pid_tgid();
    __u32 pid = (__u32)(pid_tgid >> 32);
    __u64 uid_gid = bpf_get_current_uid_gid();
    __u32 uid = (__u32)(uid_gid & 0xffffffff);
    __u64 ts  = bpf_ktime_get_ns();

    /* Filter: only track app-range UIDs (>= 10000) */
    if (!uid_is_app(uid)) return 0;
    update_pid_activity(pid, uid, ts, 1, 0, 0, 0);
    return 0;
}

/* -----------------------------------------------------------------------
 * android_fs_fsync_start
 * ----------------------------------------------------------------------- */
SEC("tracepoint/android_fs/android_fs_fsync_start")
int tp_fs_fsync(struct android_fs_fsync_args *ctx)
{
    __u64 pid_tgid = bpf_get_current_pid_tgid();
    __u32 pid = (__u32)(pid_tgid >> 32);
    __u64 uid_gid = bpf_get_current_uid_gid();
    __u32 uid = (__u32)(uid_gid & 0xffffffff);
    __u64 ts  = bpf_ktime_get_ns();

    /* Filter: only track app-range UIDs (>= 10000) */
    if (!uid_is_app(uid)) return 0;
    update_pid_activity(pid, uid, ts, 0, 0, 1, 0);
    return 0;
}

/* -----------------------------------------------------------------------
 * sched_process_exec
 * ----------------------------------------------------------------------- */
SEC("tracepoint/sched/sched_process_exec")
int tp_sched_exec(struct sched_exec_args *ctx)
{
    __u64 uid_gid = bpf_get_current_uid_gid();
    __u32 uid = (__u32)(uid_gid & 0xffffffff);
    __u64 ts  = bpf_ktime_get_ns();
    __u32 pid = ctx->pid;

    update_pid_activity(pid, uid, ts, 0, 0, 0, 1);
    return 0;
}

/* =========================================================================
 * LICENSE — required by the verifier for programs using GPL-only helpers
 * ========================================================================= */
char LICENSE[] SEC("license") = "GPL";
