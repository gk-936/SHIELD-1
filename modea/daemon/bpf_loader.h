/*
 * bpf_loader.h — self-contained BPF ELF loader interface
 *
 * No libbpf or external library dependency.
 * Supports legacy BPF ELF format (SEC("maps") with struct bpf_map_def).
 * Kernel requirement: 4.9+ (tested on 4.14 / Android 12).
 */
#pragma once

#include <cstdint>
#include <string>
#include <vector>

class BpfLoader {
public:
    BpfLoader();
    ~BpfLoader();

    /* Load the BPF ELF, create maps, load programs, attach tracepoints.
     * Returns true on success.  Must be called from a root context. */
    bool load(const std::string &bpf_obj_path);

    /* Detach all tracepoints, close map / program FDs. */
    void unload();

    bool is_loaded() const { return loaded_; }

    /* fd of the per-PID activity hash map — polled by the daemon */
    int activity_fd()     const { return pid_activity_fd_; }

    /* fd of the suspect-PIDs hash map */
    int suspect_pids_fd() const { return suspect_pids_fd_; }

    /* Write a PID into the suspect-PID map (fast-path flag for eBPF) */
    void mark_suspect_pid(uint32_t pid);
    void clear_suspect_pid(uint32_t pid);

    /* BPF map operation wrappers used by the daemon poll loop */
    static int map_get_next_key(int fd, const void *key, void *next_key);
    static int map_lookup_elem (int fd, const void *key, void *value);
    static int map_update_elem (int fd, const void *key, const void *value, uint64_t flags);
    static int map_delete_elem (int fd, const void *key);

private:
    int  pid_activity_fd_  = -1;
    int  suspect_pids_fd_  = -1;
    bool loaded_           = false;

    std::vector<int> prog_fds_;   /* BPF program FDs (keep for lifetime) */
    std::vector<int> perf_fds_;   /* perf event FDs for tracepoint attach */
};
