/* SPDX-License-Identifier: (LGPL-2.1 OR BSD-2-Clause) */
/* libbpf.c — stub implementation for SHIELD Android prototype build */
#include "libbpf.h"
#include <stdlib.h>

struct bpf_object  { char name[64]; };
struct bpf_program { char name[64]; };
struct bpf_map     { char name[64]; int fd; char pin_path[256]; };
struct bpf_link    { int dummy; };
struct ring_buffer { int dummy; };

struct bpf_object *bpf_object__open(const char *path)        { (void)path; return NULL; }
struct bpf_object *bpf_object__open_file(const char *path, const void *opts) { (void)path; (void)opts; return NULL; }
int  bpf_object__load(struct bpf_object *obj)                { (void)obj; return -1; }
void bpf_object__close(struct bpf_object *obj)               { (void)obj; }
const char *bpf_object__name(const struct bpf_object *obj)   { (void)obj; return ""; }

struct bpf_program *bpf_object__find_program_by_name(const struct bpf_object *obj, const char *name) { (void)obj; (void)name; return NULL; }
struct bpf_link *bpf_program__attach_tracepoint(const struct bpf_program *prog, const char *cat, const char *name) { (void)prog; (void)cat; (void)name; return NULL; }
int bpf_link__destroy(struct bpf_link *link) { (void)link; return -1; }

struct bpf_map *bpf_object__find_map_by_name(const struct bpf_object *obj, const char *name) { (void)obj; (void)name; return NULL; }
int bpf_map__fd(const struct bpf_map *map) { (void)map; return -1; }
const char *bpf_map__name(const struct bpf_map *map) { (void)map; return ""; }
int bpf_map__set_pin_path(struct bpf_map *map, const char *path) { (void)map; (void)path; return -1; }
int bpf_map__pin(struct bpf_map *map, const char *path) { (void)map; (void)path; return -1; }

struct ring_buffer *ring_buffer__new(int map_fd, ring_buffer_sample_fn cb, void *ctx, const void *opts) { (void)map_fd; (void)cb; (void)ctx; (void)opts; return NULL; }
int  ring_buffer__poll(struct ring_buffer *rb, int timeout_ms) { (void)rb; (void)timeout_ms; return -1; }
int  ring_buffer__epoll_fd(const struct ring_buffer *rb) { (void)rb; return -1; }
void ring_buffer__free(struct ring_buffer *rb) { (void)rb; }

libbpf_print_fn_t libbpf_set_print(libbpf_print_fn_t fn) { (void)fn; return NULL; }
int libbpf_get_error(const void *ptr) { return ptr ? 0 : -1; }
