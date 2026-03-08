/* SPDX-License-Identifier: (LGPL-2.1 OR BSD-2-Clause) */
/*
 * bpf.h — minimal BPF syscall wrapper stub for SHIELD prototype build.
 */
#pragma once

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Map update/lookup/delete flags */
#define BPF_ANY     0
#define BPF_NOEXIST 1
#define BPF_EXIST   2

int bpf_map_update_elem(int fd, const void *key, const void *value,
                         uint64_t flags);
int bpf_map_lookup_elem(int fd, const void *key, void *value);
int bpf_map_delete_elem(int fd, const void *key);
int bpf_obj_pin(int fd, const char *pathname);
int bpf_obj_get(const char *pathname);

#ifdef __cplusplus
}
#endif

