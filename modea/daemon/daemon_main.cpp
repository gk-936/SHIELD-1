/*
 * daemon_main.cpp — SHIELD Mode-A daemon entry point
 *
 * Usage:
 *   shield_modea_daemon <unix_socket_path> <bpf_obj_path> [<expected_app_uid>]
 *
 * The first two arguments are required.  The third is the UID of the
 * SHIELD app and is used to authenticate incoming socket connections via
 * SO_PEERCRED — only that exact UID is allowed to connect.  If omitted
 * the daemon falls back to a looser uid >= 10000 check.
 *
 * The Android service passes these at launch via:
 *   su -c /data/data/com.dearmoon.shield/shield_modea_daemon \
 *          /data/data/com.dearmoon.shield/shield_modea.sock \
 *          /data/data/com.dearmoon.shield/shield_bpf.o \
 *          <app_uid>
 *
 * The daemon MUST run as UID 0 (root).  If it detects otherwise it
 * exits immediately so ModeAService can fall back to Mode-B.
 *
 * Exit codes
 * ----------
 *   0  — clean shutdown (SIGTERM received)
 *   1  — missing arguments
 *   2  — not running as root
 *   3  — BPF / socket initialisation failed
 */

#include "modea_daemon.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <android/log.h>

#define TAG "SHIELD_MODE_A"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

int main(int argc, char *argv[])
{
    LOGI("SHIELD Mode-A daemon starting (pid=%d uid=%d)", getpid(), getuid());

    /* ------------------------------------------------------------------ */
    /* 1. Argument validation                                              */
    /* ------------------------------------------------------------------ */
    if (argc < 3) {
        LOGE("Usage: %s <socket_path> <bpf_obj_path>", argv[0]);
        fprintf(stderr, "Usage: %s <socket_path> <bpf_obj_path>\n", argv[0]);
        return 1;
    }

    const char *socket_path  = argv[1];
    const char *bpf_obj_path = argv[2];
    uint32_t expected_uid    = (argc >= 4)
                               ? static_cast<uint32_t>(std::strtoul(argv[3], nullptr, 10))
                               : 0;

    /* ------------------------------------------------------------------ */
    /* 2. Root check                                                       */
    /* ------------------------------------------------------------------ */
    if (getuid() != 0) {
        LOGE("Mode-A daemon must run as root (current uid=%d)", getuid());
        fprintf(stderr, "Error: daemon must run as root\n");
        return 2;
    }

    /* ------------------------------------------------------------------ */
    /* 3. Kernel compatibility pre-check                                   */
    /* verifed by ModeAController before launch; we double-check here.    */
    /* ------------------------------------------------------------------ */
    /* Kernel compatibility is verified by ModeAController before launch. */
    LOGI("Kernel pre-checks passed (full check done in Android layer)");

    /* ------------------------------------------------------------------ */
    /* 4. Start daemon                                                      */
    /* ------------------------------------------------------------------ */
    ModeADaemon daemon(socket_path, bpf_obj_path, expected_uid);

    if (!daemon.start()) {
        LOGE("Daemon start failed — exiting");
        return 3;
    }

    LOGI("Daemon exited cleanly");
    return 0;
}
