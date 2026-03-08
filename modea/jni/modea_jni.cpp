/*
 * modea_jni.cpp — JNI bridge between ModeAService (Kotlin/Java) and
 *                  the native Unix-socket / event-parsing layer.
 *
 * Exported JNI methods (registered to class com.dearmoon.shield.modea.ModeAJni):
 *
 *   nativeConnect(socketPath: String): Boolean
 *       Opens a persistent connection to the root daemon Unix socket.
 *       Returns true on success.
 *
 *   nativeDisconnect()
 *       Closes the socket connection.
 *
 *   nativeSendKill(pid: Int): Boolean
 *       Sends a KILL command for the given PID to the daemon.
 *
 *   nativeReadEvent(eventOut: ShieldEventData): Boolean
 *       Non-blocking read of the next shield_event from the socket.
 *       Fills the caller-supplied ShieldEventData object.
 *       Returns false if no event is available or socket is disconnected.
 *
 * Design note
 * -----------
 * Android's LocalSocket / LocalServerSocket (in Java) can also connect
 * to UNIX domain sockets and is sufficient for the primary receive path.
 * This JNI layer is provided for two reasons:
 *   1. To allow the service to send binary KILL commands without Java
 *      DataOutputStream alignment concerns.
 *   2. To allow future optimisation (zero-copy ring-buffer access) if
 *      the Java socket approach proves too slow.
 *
 * The Kotlin service may choose to use either this JNI bridge or the
 * pure-Java LocalSocket — both approaches are valid.
 */

#include <jni.h>
#include <string>
#include <cstring>
#include <cerrno>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/log.h>

#include "../include/shield_event.h"

#define TAG "SHIELD_MODE_A_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Global socket fd (one connection at a time) */
static int g_sock_fd = -1;

/* CLASS_NAME kept for future FindClass() use */
static const char *CLASS_NAME __attribute__((unused)) = "com/dearmoon/shield/modea/ModeAJni";

/* -------------------------------------------------------------------------
 * Helper: fully receive `n` bytes from `fd`, handling partial reads.
 * Returns true if all bytes were received.
 * ------------------------------------------------------------------------- */
static bool recv_all(int fd, void *buf, size_t n)
{
    auto *p = static_cast<uint8_t *>(buf);
    size_t remaining = n;
    while (remaining > 0) {
        ssize_t r = recv(fd, p, remaining, 0);
        if (r <= 0) return false;
        p         += r;
        remaining -= (size_t)r;
    }
    return true;
}

/* -------------------------------------------------------------------------
 * Helper: send `n` bytes to `fd`, all-or-nothing.
 * ------------------------------------------------------------------------- */
static bool send_all(int fd, const void *buf, size_t n)
{
    const auto *p = static_cast<const uint8_t *>(buf);
    size_t remaining = n;
    while (remaining > 0) {
        ssize_t s = send(fd, p, remaining, MSG_NOSIGNAL);
        if (s <= 0) return false;
        p         += s;
        remaining -= (size_t)s;
    }
    return true;
}

/* =========================================================================
 * JNI Implementations
 * ========================================================================= */

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_dearmoon_shield_modea_ModeAJni_nativeConnect(
        JNIEnv *env, jobject /*thiz*/, jstring socket_path_j)
{
    const char *socket_path = env->GetStringUTFChars(socket_path_j, nullptr);
    if (!socket_path) return JNI_FALSE;

    /* Close any old connection */
    if (g_sock_fd >= 0) {
        close(g_sock_fd);
        g_sock_fd = -1;
    }

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        LOGE("socket(): %s", strerror(errno));
        env->ReleaseStringUTFChars(socket_path_j, socket_path);
        return JNI_FALSE;
    }

    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);

    if (connect(fd, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr)) < 0) {
        LOGE("connect(%s): %s", socket_path, strerror(errno));
        close(fd);
        env->ReleaseStringUTFChars(socket_path_j, socket_path);
        return JNI_FALSE;
    }

    g_sock_fd = fd;
    LOGI("Connected to daemon at %s (fd=%d)", socket_path, fd);
    env->ReleaseStringUTFChars(socket_path_j, socket_path);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dearmoon_shield_modea_ModeAJni_nativeDisconnect(
        JNIEnv * /*env*/, jobject /*thiz*/)
{
    if (g_sock_fd >= 0) {
        close(g_sock_fd);
        g_sock_fd = -1;
        LOGI("Disconnected from daemon");
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_dearmoon_shield_modea_ModeAJni_nativeSendKill(
        JNIEnv * /*env*/, jobject /*thiz*/, jint pid)
{
    if (g_sock_fd < 0) {
        LOGW("nativeSendKill: not connected");
        return JNI_FALSE;
    }

    /*
     * Kill command payload: "KILL" (4 bytes) + uint32_t pid (4 bytes).
     * Prefixed with uint32_t length = 8.
     */
    struct {
        uint32_t len;
        char     tag[4];
        uint32_t pid;
    } __attribute__((packed)) cmd{};

    cmd.len = 8;
    memcpy(cmd.tag, "KILL", 4);
    cmd.pid = static_cast<uint32_t>(pid);

    if (!send_all(g_sock_fd, &cmd, sizeof(cmd))) {
        LOGE("nativeSendKill: send failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    LOGI("Kill command sent for PID %d", pid);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_dearmoon_shield_modea_ModeAJni_nativeReadEvent(
        JNIEnv *env, jobject /*thiz*/, jobject event_out)
{
    if (g_sock_fd < 0) return JNI_FALSE;

    /* Set non-blocking for the peek */
    int flags = fcntl(g_sock_fd, F_GETFL, 0);
    fcntl(g_sock_fd, F_SETFL, flags | O_NONBLOCK);

    /* Read 4-byte length header */
    uint32_t len = 0;
    ssize_t  n   = recv(g_sock_fd, &len, sizeof(len), MSG_DONTWAIT);

    /* Restore blocking mode */
    fcntl(g_sock_fd, F_SETFL, flags & ~O_NONBLOCK);

    if (n <= 0) return JNI_FALSE;   /* no event available */

    if (len != sizeof(struct shield_event)) {
        LOGW("Unexpected event length %u (expected %zu)", len,
             sizeof(struct shield_event));
        return JNI_FALSE;
    }

    /* Read fixed-size event payload */
    struct shield_event ev{};
    if (!recv_all(g_sock_fd, &ev, sizeof(ev))) {
        LOGE("Failed to read event payload");
        return JNI_FALSE;
    }

    /*
     * Fill the Java ShieldEventData object via reflection.
     * Field names must match those declared in ModeAFileCollector.kt.
     */
    jclass cls = env->GetObjectClass(event_out);

    auto set_int = [&](const char *name, jint val) {
        jfieldID fid = env->GetFieldID(cls, name, "I");
        if (fid) env->SetIntField(event_out, fid, val);
    };
    auto set_long = [&](const char *name, jlong val) {
        jfieldID fid = env->GetFieldID(cls, name, "J");
        if (fid) env->SetLongField(event_out, fid, val);
    };
    auto set_string = [&](const char *name, const char *val) {
        jfieldID fid = env->GetFieldID(cls, name, "Ljava/lang/String;");
        if (fid) {
            jstring jval = env->NewStringUTF(val ? val : "");
            env->SetObjectField(event_out, fid, jval);
            env->DeleteLocalRef(jval);
        }
    };

    set_int   ("pid",       static_cast<jint>(ev.pid));
    set_int   ("uid",       static_cast<jint>(ev.uid));
    set_long  ("timestamp", static_cast<jlong>(ev.timestamp));
    set_int   ("bytes",     static_cast<jint>(ev.bytes));
    set_string("operation", ev.operation);
    set_string("filename",  ev.filename);

    return JNI_TRUE;
}
