/*
 * modea_daemon.h — public interface for ModeADaemon
 */
#pragma once

#include "bpf_loader.h"
#include <string>

class ModeADaemon {
public:
    /* expected_uid: UID of the SHIELD app that is allowed to connect.
     * Pass 0 to fall back to the loose uid >= 10000 check (only for
     * backward-compatibility; always pass the real UID in production). */
    ModeADaemon(std::string socket_path, std::string bpf_obj_path,
                uint32_t expected_uid = 0);
    ~ModeADaemon();

    /* Start the daemon main loop.  Blocks until SIGTERM / SIGINT. */
    bool start();

    /* Request graceful shutdown from another thread. */
    void stop();

private:
    std::string  socket_path_;
    std::string  bpf_obj_path_;
    uint32_t     expected_uid_;  /* 0 = accept any app UID (>= 10000) */
    BpfLoader    loader_;
};
