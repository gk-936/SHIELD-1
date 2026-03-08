/* SPDX-License-Identifier: (LGPL-2.1 OR BSD-2-Clause) */
/* bpf.c — stub BPF syscall wrappers */
#include "bpf.h"

int bpf_map_update_elem(int fd, const void *key, const void *value, uint64_t flags) {
    (void)fd; (void)key; (void)value; (void)flags; return -1;
}
int bpf_map_lookup_elem(int fd, const void *key, void *value) {
    (void)fd; (void)key; (void)value; return -1;
}
int bpf_map_delete_elem(int fd, const void *key) {
    (void)fd; (void)key; return -1;
}
int bpf_obj_pin(int fd, const char *pathname) {
    (void)fd; (void)pathname; return -1;
}
int bpf_obj_get(const char *pathname) {
    (void)pathname; return -1;
}

