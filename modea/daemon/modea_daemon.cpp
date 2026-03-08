/*
 * modea_daemon.cpp — SHIELD Mode-A root daemon
 *
 * Launched by ModeAService as:
 *   su -c /data/local/tmp/shield_modea_daemon <socket_path> <bpf_obj_path>
 *
 * Responsibilities
 * ----------------
 *   1. Load BPF programs and attach tracepoints via BpfLoader.
 *   2. Listen on a Unix domain socket for:
 *        a) the Android service to connect and receive events
 *        b) kill-PID commands from the service
 *   3. Poll the ring buffer continuously.
 *   4. Serialize each shield_event and send it to the service socket.
 *   5. React to kill commands by calling kill(pid, SIGKILL).
 *   6. Perform graceful shutdown on SIGTERM / SIGINT.
 *
 * Protocol
 * --------
 * All messages on the socket are prefixed with a 4-byte little-endian
 * length field followed by a flat payload:
 *
 *   Daemon → Service:  [uint32_t len][struct shield_event]
 *   Service → Daemon:  [uint32_t len]["KILL\0" + uint32_t pid]
 *
 * The fixed-size shield_event struct (292 bytes) is sent verbatim.
 * sizeof() is cross-checked at build time in shield_event.h.
 */

#include "modea_daemon.h"
#include "bpf_loader.h"
#include "../include/shield_event.h"
#include "../include/pid_activity.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_set>
#include <vector>

#include <fcntl.h>
#include <dirent.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include <android/log.h>

#define TAG "SHIELD_MODE_A"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* -------------------------------------------------------------------------
 * Globals for signal handling
 * ------------------------------------------------------------------------- */
static std::atomic<bool> g_running{true};
static int g_server_fd  = -1;
static int g_client_fd  = -1;

static void signal_handler(int /*sig*/)
{
    g_running.store(false, std::memory_order_relaxed);
    /* Wake up accept/poll by closing the server socket */
    if (g_server_fd >= 0) {
        close(g_server_fd);
        g_server_fd = -1;
    }
}

/* -------------------------------------------------------------------------
 * Socket helpers
 * ------------------------------------------------------------------------- */

static int create_unix_server(const std::string &socket_path)
{
    unlink(socket_path.c_str());   /* remove stale socket */

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        LOGE("socket(): %s", strerror(errno));
        return -1;
    }

    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, socket_path.c_str(), sizeof(addr.sun_path) - 1);

    if (bind(fd, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr)) < 0) {
        LOGE("bind(%s): %s", socket_path.c_str(), strerror(errno));
        close(fd);
        return -1;
    }
    chmod(socket_path.c_str(), 0666);   /* world-accessible so app UID can connect */

    /* Relabel to shell_data_file so SELinux allows untrusted_app to connectto.
     * The socket is created under magisk context; without this label, even
     * 0666 DAC permissions are overridden by SELinux MAC policy. */
    {
        const char *chcon_argv[] = {
            "/system/bin/chcon",
            "u:object_r:shell_data_file:s0",
            socket_path.c_str(),
            nullptr
        };
        pid_t pid = fork();
        if (pid == 0) {
            execv(chcon_argv[0], const_cast<char *const *>(chcon_argv));
            _exit(1);
        } else if (pid > 0) {
            waitpid(pid, nullptr, 0);
        }
    }

    if (listen(fd, 1) < 0) {
        LOGE("listen(): %s", strerror(errno));
        close(fd);
        return -1;
    }
    LOGI("Unix socket listening at %s", socket_path.c_str());
    return fd;
}

/*
 * send_event — write a length-prefixed shield_event to the client socket.
 * Returns false if the send failed (client disconnected).
 */
static bool send_event(int client_fd, const struct shield_event *ev)
{
    uint32_t len = static_cast<uint32_t>(sizeof(*ev));

    /* Send 4-byte length header */
    if (send(client_fd, &len, sizeof(len), MSG_NOSIGNAL) != sizeof(len))
        return false;

    /* Send event payload */
    ssize_t sent = send(client_fd, ev, len, MSG_NOSIGNAL);
    return sent == static_cast<ssize_t>(len);
}

/* -------------------------------------------------------------------------
 * Kill command processing
 * Protocol: "KILL" (4 bytes) + uint32_t pid (4 bytes) = 8 bytes payload
 * ------------------------------------------------------------------------- */

static void process_kill_command(int client_fd,
                                  BpfLoader &loader,
                                  std::unordered_set<uint32_t> &killed_pids)
{
    /* Read 4-byte length header */
    uint32_t msg_len = 0;
    ssize_t  n = recv(client_fd, &msg_len, sizeof(msg_len), MSG_DONTWAIT);
    if (n <= 0 || msg_len < 8 || msg_len > 64)
        return;

    char buf[64]{};
    n = recv(client_fd, buf, msg_len, MSG_DONTWAIT);
    if (n < 8) return;

    if (strncmp(buf, "KILL", 4) != 0) return;

    uint32_t pid = 0;
    memcpy(&pid, buf + 4, sizeof(pid));

    if (pid == 0 || pid == 1) {
        LOGW("Rejecting kill request for reserved PID %u", pid);
        return;
    }
    if (killed_pids.count(pid)) {
        LOGI("PID %u already killed, skipping", pid);
        return;
    }

    LOGI("KILL PID %u — SIGKILL", pid);
    if (kill(static_cast<pid_t>(pid), SIGKILL) == 0) {
        killed_pids.insert(pid);
        loader.mark_suspect_pid(pid);
        LOGI("SIGKILL delivered to PID %u", pid);
    } else {
        LOGE("kill(%u, SIGKILL): %s", pid, strerror(errno));
    }
}

/* -------------------------------------------------------------------------
 * Activity-map polling — replaces ring buffer callback
 *
 * Iterates the shield_pid_activity hash map.  Whenever burst_flags != 0,
 * synthesises a shield_event and sends it to the connected service, then
 * clears burst_flags so the same burst is not reported repeatedly.
 * ------------------------------------------------------------------------- */

static void check_activity_map(BpfLoader &loader, int client_fd, bool &connected)
{
    /* Best-effort: scan /proc/<pid>/fd/ symlinks to find a recently-open
     * regular file path for the bursting PID.  Falls back to empty string
     * if the process no longer exists or has no suitable fds. */
    auto get_pid_last_file = [](uint32_t pid) -> std::string {
        char dir[64];
        snprintf(dir, sizeof(dir), "/proc/%u/fd", pid);
        DIR *d = opendir(dir);
        if (!d) return "";
        std::string result;
        struct dirent *e;
        time_t newest = 0;
        while ((e = readdir(d)) != nullptr) {
            if (e->d_name[0] == '.') continue;
            char link[128], path[512];
            snprintf(link, sizeof(link), "%s/%s", dir, e->d_name);
            ssize_t n = readlink(link, path, sizeof(path) - 1);
            if (n <= 0) continue;
            path[n] = '\0';
            if (path[0] != '/') continue;           /* skip sockets, pipes */
            if (strncmp(path, "/dev/",  5) == 0) continue;
            if (strncmp(path, "/proc/", 6) == 0) continue;
            if (strncmp(path, "/sys/",  5) == 0) continue;
            struct stat st;
            if (stat(link, &st) == 0 && st.st_mtime >= newest) {
                newest = st.st_mtime;
                result = path;
            }
        }
        closedir(d);
        return result;
    };
    int map_fd = loader.activity_fd();
    if (map_fd < 0) return;

    uint32_t key = 0, next_key = 0;
    bool first = true;

    for (;;) {
        int r = BpfLoader::map_get_next_key(map_fd,
                                            first ? nullptr : &key,
                                            &next_key);
        if (r < 0) break;   /* ENOENT = end of map */
        first = false;
        key   = next_key;

        struct pid_activity pa{};
        if (BpfLoader::map_lookup_elem(map_fd, &key, &pa) < 0) continue;
        if (pa.burst_flags == 0) continue;

        /* Build a synthetic event from the accumulated activity */
        struct shield_event ev{};
        ev.pid       = key;
        ev.uid       = pa.uid;
        ev.timestamp = pa.last_timestamp;
        ev.bytes     = pa.write_count;
        /* operation field encodes which burst types fired */
        snprintf(ev.operation, sizeof(ev.operation), "BURST");
        /* Best-effort filename from /proc/<pid>/fd/ */
        std::string fname = get_pid_last_file(key);
        if (!fname.empty())
            strncpy(ev.filename, fname.c_str(), sizeof(ev.filename) - 1);

        LOGI("BURST pid=%u flags=0x%x writes=%u reads=%u fsyncs=%u",
             key, pa.burst_flags,
             pa.write_count, pa.read_count, pa.fsync_count);

        if (connected && client_fd >= 0) {
            if (!send_event(client_fd, &ev)) {
                LOGW("Send failed — client disconnected");
                connected = false;
            }
        }

        /* Clear burst_flags so we don't re-report the same burst */
        pa.burst_flags = 0;
        BpfLoader::map_update_elem(map_fd, &key, &pa, 0 /*BPF_ANY*/);
    }
}

/* -------------------------------------------------------------------------
 * ModeADaemon public API
 * ------------------------------------------------------------------------- */

ModeADaemon::ModeADaemon(std::string socket_path, std::string bpf_obj_path)
    : socket_path_(std::move(socket_path))
    , bpf_obj_path_(std::move(bpf_obj_path))
{}

ModeADaemon::~ModeADaemon()
{
    stop();
}

bool ModeADaemon::start()
{
    /* Install signal handlers for clean shutdown */
    struct sigaction sa{};
    sa.sa_handler = signal_handler;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, nullptr);
    sigaction(SIGINT,  &sa, nullptr);
    signal(SIGPIPE, SIG_IGN);

    /* Create Unix server socket FIRST — so ModeAService can connect
     * and receive a status message even if BPF load fails. */
    g_server_fd = create_unix_server(socket_path_);
    if (g_server_fd < 0) {
        LOGE("Failed to create Unix socket at %s", socket_path_.c_str());
        return false;
    }

    /* Load BPF programs */
    LOGI("Loading BPF object: %s", bpf_obj_path_.c_str());
    if (!loader_.load(bpf_obj_path_)) {
        LOGE("BPF load failed — check logcat for details");
        LOGE("Hint: adb shell ls /sys/kernel/tracing/events/android_fs/");
        close(g_server_fd);
        g_server_fd = -1;
        return false;
    }

    LOGI("Mode-A daemon running — waiting for Android service connection");

    /* -----------------------------------------------------------------
     * Main event loop
     * -----------------------------------------------------------------
     * Strategy:
     *   1. Block on accept() until the Android service connects.
     *   2. Once connected, poll the client socket with a 500 ms timeout:
     *        - client socket readable → process kill commands
     *        - timeout              → poll activity hash map, emit events
     *        - client disconnected  → wait for reconnect
     * ----------------------------------------------------------------- */
    bool connected = false;
    std::unordered_set<uint32_t> killed_pids;

    while (g_running.load(std::memory_order_relaxed)) {
        LOGI("Waiting for Android service to connect …");
        int client = accept(g_server_fd, nullptr, nullptr);
        if (client < 0) {
            if (!g_running.load()) break;
            if (errno == EINTR || errno == EAGAIN) continue;
            LOGE("accept(): %s", strerror(errno));
            break;
        }
        g_client_fd = client;
        connected   = true;
        LOGI("Android service connected (fd=%d)", client);

        while (g_running.load() && connected) {
            struct pollfd pfd;
            pfd.fd      = client;
            pfd.events  = POLLIN | POLLHUP;
            pfd.revents = 0;

            int ret = poll(&pfd, 1, 500 /* ms */);
            if (ret < 0) {
                if (errno == EINTR) continue;
                LOGE("poll(): %s", strerror(errno));
                break;
            }

            /* Process incoming kill command from Android service */
            if (pfd.revents & POLLIN) {
                process_kill_command(client, loader_, killed_pids);
            }

            /* Client hung up */
            if (pfd.revents & POLLHUP) {
                LOGW("Android service disconnected");
                connected = false;
                break;
            }

            /* Poll hash map for burst events (every 500 ms) */
            check_activity_map(loader_, client, connected);
        }

        close(client);
        g_client_fd = -1;
        connected   = false;
        LOGI("Client connection closed — will wait for reconnect");
    }

    LOGI("Mode-A daemon shutting down");
    return true;
}

void ModeADaemon::stop()
{
    g_running.store(false, std::memory_order_relaxed);

    if (g_client_fd >= 0) {
        close(g_client_fd);
        g_client_fd = -1;
    }
    if (g_server_fd >= 0) {
        close(g_server_fd);
        g_server_fd = -1;
    }
    loader_.unload();
    unlink(socket_path_.c_str());
}
