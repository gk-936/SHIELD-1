/*
 * modea_daemon.h — public interface for ModeADaemon
 */
#pragma once

#include "bpf_loader.h"
#include <string>

class ModeADaemon {
public:
    ModeADaemon(std::string socket_path, std::string bpf_obj_path);
    ~ModeADaemon();

    /* Start the daemon main loop.  Blocks until SIGTERM / SIGINT. */
    bool start();

    /* Request graceful shutdown from another thread. */
    void stop();

private:
    std::string  socket_path_;
    std::string  bpf_obj_path_;
    BpfLoader    loader_;
};
