/* SPDX-License-Identifier: GPL-2.0 */
/*
 * bpf_helpers.h — minimal BPF helper function declarations for SHIELD
 *
 * Replaces the libbpf-sourced bpf/bpf_helpers.h.
 * Covers only the helpers used by shield_bpf.c.
 * Compatible with kernel 4.9+ (helpers numbered per kernel/bpf/syscall.c).
 *
 * Helper numbering per linux/bpf.h enum bpf_func_id.
 */
#ifndef __BPF_HELPERS_H
#define __BPF_HELPERS_H

#include <linux/bpf.h>

/*
 * struct bpf_map_def — legacy BPF map definition for SEC("maps").
 *
 * Placed in the "maps" ELF section; each instance defines one BPF map.
 * This struct is loader-side only (not a kernel UAPI type) and is
 * intentionally compatible with the layout that libbpf 0.x expects.
 */
struct bpf_map_def {
    unsigned int type;
    unsigned int key_size;
    unsigned int value_size;
    unsigned int max_entries;
    unsigned int map_flags;
};

/*
 * SEC — place a global in a named ELF section.
 * The loader identifies programs and maps by their section names.
 */
#ifndef SEC
#define SEC(name) __attribute__((section(name), used))
#endif

#ifndef __always_inline
#define __always_inline inline __attribute__((always_inline))
#endif

/*
 * BPF helper declarations.
 * Each is a pointer-to-function with the address equal to the numeric
 * helper ID (cast to a pointer).  The BPF JIT replaces these calls with
 * the real kernel helper implementations.
 */

/* Map operations */
static void *(*bpf_map_lookup_elem)(void *map, const void *key) =
    (void *) BPF_FUNC_map_lookup_elem;

static int (*bpf_map_update_elem)(void *map, const void *key,
                                   const void *value, __u64 flags) =
    (void *) BPF_FUNC_map_update_elem;

static int (*bpf_map_delete_elem)(void *map, const void *key) =
    (void *) BPF_FUNC_map_delete_elem;

/* Time */
static __u64 (*bpf_ktime_get_ns)(void) =
    (void *) BPF_FUNC_ktime_get_ns;

/* Process metadata */
static __u64 (*bpf_get_current_pid_tgid)(void) =
    (void *) BPF_FUNC_get_current_pid_tgid;

static __u64 (*bpf_get_current_uid_gid)(void) =
    (void *) BPF_FUNC_get_current_uid_gid;

/*
 * bpf_probe_read_str — safely copy a NUL-terminated string from kernel
 * or user space.  Added in kernel 4.11 (helper #45).
 *
 * Returns the number of bytes written (including the NUL), or a negative
 * error code on fault.
 */
static int (*bpf_probe_read_str)(void *dst, int size, const void *unsafe_ptr) =
    (void *) BPF_FUNC_probe_read_str;

#endif /* __BPF_HELPERS_H */
