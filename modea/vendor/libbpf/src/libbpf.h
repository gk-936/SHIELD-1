/* SPDX-License-Identifier: (LGPL-2.1 OR BSD-2-Clause) */
/*
 * libbpf.h — minimal stub for SHIELD Mode-A Android prototype build.
 *
 * This is NOT the real libbpf. It provides just enough declarations
 * so the daemon compiles against the NDK on a Windows/macOS dev machine.
 * On the actual Android device, the daemon must link against a real
 * libbpf built for arm64-v8a.
 */
#pragma once

#include <stdint.h>
#include <stddef.h>
#include <stdarg.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Opaque types ---- */
struct bpf_object;
struct bpf_program;
struct bpf_map;
struct bpf_link;
struct ring_buffer;

/* ---- bpf_object lifecycle ---- */
struct bpf_object *bpf_object__open(const char *path);
struct bpf_object *bpf_object__open_file(const char *path, const void *opts);
int  bpf_object__load(struct bpf_object *obj);
void bpf_object__close(struct bpf_object *obj);
const char *bpf_object__name(const struct bpf_object *obj);

/* ---- bpf_program ---- */
struct bpf_program *bpf_object__find_program_by_name(const struct bpf_object *obj,
                                                      const char *name);
struct bpf_link *bpf_program__attach_tracepoint(const struct bpf_program *prog,
                                                 const char *tp_category,
                                                 const char *tp_name);
int bpf_link__destroy(struct bpf_link *link);

/* ---- bpf_map ---- */
struct bpf_map *bpf_object__find_map_by_name(const struct bpf_object *obj,
                                              const char *name);
int bpf_map__fd(const struct bpf_map *map);
const char *bpf_map__name(const struct bpf_map *map);
int bpf_map__set_pin_path(struct bpf_map *map, const char *path);
int bpf_map__pin(struct bpf_map *map, const char *path);

/* ---- ring_buffer ---- */
typedef int (*ring_buffer_sample_fn)(void *ctx, void *data, size_t size);
struct ring_buffer *ring_buffer__new(int map_fd,
                                     ring_buffer_sample_fn sample_cb,
                                     void *ctx,
                                     const void *opts);
int  ring_buffer__poll(struct ring_buffer *rb, int timeout_ms);
int  ring_buffer__epoll_fd(const struct ring_buffer *rb);
void ring_buffer__free(struct ring_buffer *rb);

/* ---- libbpf logging ---- */
/* enum must be defined BEFORE it is used in a typedef */
enum libbpf_print_level {
    LIBBPF_WARN  = 0,
    LIBBPF_INFO  = 1,
    LIBBPF_DEBUG = 2,
};

typedef int (*libbpf_print_fn_t)(enum libbpf_print_level level,
                                 const char *format, va_list args);

libbpf_print_fn_t libbpf_set_print(libbpf_print_fn_t fn);

/* ---- misc helpers ---- */
int libbpf_get_error(const void *ptr);

#ifdef __cplusplus
}
#endif
