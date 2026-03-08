# MODE-A IMPLEMENTATION

> **Status:** Prototype — Integration-ready  
> **Last updated:** 2026-03-07  
> **Author:** SHIELD engineering

---

## Table of Contents

1. [Architecture](#1-architecture)
2. [Kernel Probes Used](#2-kernel-probes-used)
3. [BPF Maps Used](#3-bpf-maps-used)
4. [Kernel Filtering Strategy](#4-kernel-filtering-strategy)
5. [Event Format](#5-event-format)
6. [JNI / Socket Design](#6-jni--socket-design)
7. [Build Instructions](#7-build-instructions)
8. [Testing Instructions](#8-testing-instructions)
9. [Debugging Tips](#9-debugging-tips)
10. [Known Limitations](#10-known-limitations)
11. [Future Improvements](#11-future-improvements)

---

## 1  Architecture

### 1.1  High-level overview

```
  Android Kernel
  ┌────────────────────────────────────────────────────────┐
  │  tracepoint/android_fs/android_fs_datawrite_start      │
  │  tracepoint/android_fs/android_fs_dataread_start       │
  │  tracepoint/android_fs/android_fs_fsync_start          │
  │  tracepoint/sched/sched_process_exec                   │
  │            │                                           │
  │            ▼  (eBPF program: shield_bpf.c)             │
  │  ┌──────────────────────────────────────┐              │
  │  │  UID filter → path filter → burst    │              │
  │  │  tracking → ring-buffer emit         │              │
  │  └──────────┬───────────────────────────┘              │
  │             │  BPF_MAP_TYPE_RINGBUF                     │
  └─────────────┼──────────────────────────────────────────┘
                │  (ring buffer, pinned at /sys/fs/bpf/shield_events)
                ▼
  Root daemon process  (shield_modea_daemon, C++)
  ┌──────────────────────────────────────────────────┐
  │  BpfLoader:  load / pin / attach                 │
  │  ModeADaemon: ring_buffer__poll() loop           │
  │    → serialise shield_event (292 bytes)          │
  │    → send over Unix domain socket                │
  │    → receive KILL commands → kill(pid, SIGKILL)  │
  └──────────────────────┬───────────────────────────┘
                         │  Unix domain socket
                         │  /data/local/tmp/shield_modea.sock
                         ▼
  ModeAService  (Android ForegroundService, Java)
  ┌──────────────────────────────────────────────────┐
  │  ModeAJni (JNI): nativeReadEvent()               │
  │  ModeAFileCollector: ShieldEventData             │
  │                      → FileSystemEvent           │
  │                      (operation="MODIFY", uid=N) │
  └──────────────────────┬───────────────────────────┘
                         │  processFileEvent()
                         ▼
  UnifiedDetectionEngine  (existing SHIELD pipeline)
  ┌──────────────────────────────────────────────────┐
  │  Entropy → KL-divergence → SPRT                  │
  │  BehaviorCorrelationEngine                       │
  │  SnapshotManager → backup + restore              │
  │  killMaliciousProcess() / triggerNetworkBlock()  │
  └──────────────────────────────────────────────────┘
```

### 1.2  Module structure

```
modea/
├─ docs/
│   └─ MODE_A_IMPLEMENTATION.md     ← this file
│
├─ ebpf/
│   └─ shield_bpf.c                 ← eBPF kernel program
│
├─ daemon/
│   ├─ daemon_main.cpp              ← entry point, argument parsing
│   ├─ modea_daemon.h / .cpp        ← socket server, ring-buffer poll loop
│   └─ bpf_loader.h / .cpp         ← libbpf wrapper: load / attach / pin
│
├─ jni/
│   └─ modea_jni.cpp                ← native methods for ModeAJni.java
│
├─ android/
│   ├─ ModeAService.java            ← Android ForegroundService
│   ├─ ModeAController.java         ← root check, binary deploy, daemon lifecycle
│   ├─ ModeAFileCollector.java      ← kernel event → FileSystemEvent translation
│   ├─ ModeAJni.java                ← Java wrapper for the JNI library
│   └─ ShieldEventData.java         ← plain data class matching shield_event struct
│
├─ include/
│   ├─ shield_event.h               ← shared event struct (kernel + userspace)
│   └─ pid_activity.h               ← per-PID burst counters struct
│
├─ build/
│   ├─ CMakeLists.txt               ← builds daemon + JNI .so via NDK
│   └─ build_bpf.sh                 ← compiles shield_bpf.c with clang -target bpf
│
└─ tests/
    └─ telemetry_test.md            ← 10-step integration test plan
```

### 1.3  Integration with existing SHIELD pipeline

Mode-A feeds events into `UnifiedDetectionEngine.processFileEvent()` — the
same entry point used by Mode-B's `FileSystemCollector`.  The detection
logic, scoring, snapshot backup, and automated restore are completely
unchanged.

The key improvement over Mode-B is **accurate kernel-level UID attribution**:
Mode-B uses heuristics (`resolveAttributionUid` in `UnifiedDetectionEngine`);
Mode-A reads the real UID directly from the kernel via `bpf_get_current_uid_gid()`.

---

## 2  Kernel Probes Used

All probes are **stable Android tracepoints** that do not rely on internal
kernel function addresses.  They survive kernel upgrades and do not require
kallsyms lookup at runtime.

| Tracepoint | Purpose | Burst gate |
|---|---|---|
| `tracepoint/android_fs/android_fs_datawrite_start` | Fired on every filesystem write-back. Detects encryption loops — the core ransomware signal. | bytes ≥ 4096; always emitted |
| `tracepoint/android_fs/android_fs_dataread_start` | Fired on every read request. Detects directory enumeration before encryption begins. | Only emitted after `BURST_FLAG_READ` set (≥ 100 reads in 5 s) |
| `tracepoint/android_fs/android_fs_fsync_start` | Fired on each `fsync(2)` call. Ransomware calls fsync after every encrypted file to ensure victim data is unrecoverable. | Always emitted (fsyncs are inherently low-frequency for normal apps) |
| `tracepoint/sched/sched_process_exec` | Fired immediately after `execve()` succeeds. Captures new process launches — earliest possible detection point. | Always emitted (once per process launch) |

### Why these tracepoints (not vfs_write/vfs_unlink)

`vfs_write`, `vfs_rename`, `vfs_unlink` are **internal kernel functions**
whose addresses and argument layouts change between kernel versions.
kprobes on these functions require `CONFIG_KPROBES` (not guaranteed on
Android GKI) and frequently break when the kernel is patched.

The `android_fs` tracepoints are a stable API defined by the Android GKI
kernel interface (`GKABI`).  They are guaranteed stable across Android 12+
kernel versions.

---

## 3  BPF Maps Used

### shield_events  (event ring buffer)

```c
BPF_MAP_TYPE_RINGBUF
max_entries = 256 KB
pinned at:  /sys/fs/bpf/shield_events
```

The primary channel from kernel to daemon.  Each record is one
`struct shield_event` (292 bytes).  The ring buffer is lock-free and
supports concurrent producers (the four tracepoint handlers) with a single
consumer (the daemon read loop).

`BPF_MAP_TYPE_RINGBUF` is preferred over `BPF_MAP_TYPE_PERF_EVENT_ARRAY`
because it guarantees ordering and does not require a per-CPU buffer.
Fallback to `PERF_EVENT_ARRAY` is documented in §10 (Known Limitations).

### shield_pid_activity  (per-PID counters)

```c
BPF_MAP_TYPE_HASH
key:   __u32  pid
value: struct pid_activity  (40 bytes)
max_entries = 4096
pinned at:  /sys/fs/bpf/shield_pid_activity
```

Tracks read/write/fsync counts per PID within a 5-second sliding window.
Used to implement burst detection inside the eBPF program.  The daemon
also reads this map to forward burst stats to the `BehaviorCorrelationEngine`.

### shield_suspect_pids  (flagged PIDs)

```c
BPF_MAP_TYPE_HASH
key:   __u32  pid
value: __u32  flags  (reserved)
max_entries = 256
pinned at:  /sys/fs/bpf/shield_suspect_pids
```

Populated by the daemon when the detection engine flags a PID.  Future
eBPF program enhancements can use this map for fast-path decisions (e.g.
emit every write regardless of burst gate for a suspect PID).

### Map pinning rationale

All maps are pinned to `/sys/fs/bpf/` so that:
- State survives daemon restarts (activity counters are preserved).
- `bpftool map dump` can inspect live data without a running daemon.
- Multiple processes (daemon + diagnostic tools) can share the same map.

---

## 4  Kernel Filtering Strategy

All filtering happens inside the eBPF program to minimise the number of
events crossing the kernel/user boundary.

### 4.1  UID filter

```c
if (uid < 10000)
    return 0;   // drop system processes
```

Android app UIDs start at 10000.  System daemons (init, zygote, installd)
have UIDs below 10000 and generate high-frequency legitimate I/O that would
flood the ring buffer and trigger false positives.

### 4.2  Path filter

```c
// Accept: /sdcard/...  or  /storage/...
if (!path_is_user_storage(path))
    return 0;
```

Only user-accessible storage paths are monitored.  System partitions
(`/system`, `/vendor`, `/apex`, `/data/data`) are excluded because:
- Ransomware targets user files (photos, documents).
- System writes are legitimate and frequent.

The inline `path_is_user_storage()` function avoids loops and uses
direct byte comparisons (verifier-safe).

### 4.3  Write size filter

```c
if (nbytes < 4096)
    return 0;
```

Crypto libraries typically write in blocks of 4096 bytes or more.  Small
writes are associated with metadata updates, log files, and config patches —
not encryption.  This filter eliminates ~60% of write events on a typical
device without sacrificing detection coverage.

### 4.4  Burst detection

The `shield_pid_activity` map accumulates counts per PID inside a 5-second
window.  When a threshold is exceeded, a burst flag is set in the map value.

| Counter | Threshold | Flag |
|---|---|---|
| `read_count` | ≥ 100 reads / 5 s | `BURST_FLAG_READ` |
| `write_count` | ≥ 50 writes / 5 s | `BURST_FLAG_WRITE` |
| `fsync_count` | ≥ 20 fsyncs / 5 s | `BURST_FLAG_FSYNC` |

READ events are only emitted once the burst flag is set.  WRITE and FSYNC
events are always emitted after passing the size and UID filters.

Heavy behavioral analysis (cross-event correlation, SPRT, entropy) stays
in **userspace** inside `UnifiedDetectionEngine` and `BehaviorCorrelationEngine`.

---

## 5  Event Format

Defined in `modea/include/shield_event.h`:

```c
struct shield_event {
    __u32  pid;                    // Thread-group leader PID
    __u32  uid;                    // Effective UID
    __u64  timestamp;              // ktime_get_ns() — nanoseconds since boot
    char   operation[16];          // "READ", "WRITE", "FSYNC", "EXEC"
    char   filename[256];          // Absolute path (best-effort)
    __u32  bytes;                  // Bytes transferred (0 for EXEC/FSYNC)
} __attribute__((packed));
// Total: 292 bytes
```

The struct is **packed** and uses fixed-width types to ensure identical
layout in kernel and userspace regardless of ABI (32-bit vs 64-bit).

A compile-time `_Static_assert` in `shield_event.h` verifies the size
is exactly 292 bytes.

### Transport

The daemon sends events over the Unix socket as:

```
[uint32_t length = 292]  [struct shield_event (292 bytes)]
```

Kill commands flow in the reverse direction:

```
[uint32_t length = 8]  ["KILL" (4 bytes)]  [uint32_t pid (4 bytes)]
```

### FileSystemEvent mapping

`ModeAFileCollector` converts each `shield_event` to a `FileSystemEvent`:

| shield_event | FileSystemEvent |
|---|---|
| `operation = "WRITE"` | `operation = "MODIFY"` (triggers entropy analysis) |
| `operation = "READ"` | `operation = "READ"` |
| `operation = "FSYNC"` | `operation = "FSYNC"` |
| `operation = "EXEC"` | `operation = "EXEC"` |
| `uid` | `event.setUid(uid)` — accurate kernel attribution |
| `bytes` | `sizeBefore = sizeAfter = bytes` |
| `filename` | `filePath = filename` |
| — | `mode = "MODE_A"` (set via reflection) |

---

## 6  JNI / Socket Design

### Unix domain socket

The daemon and the Android service communicate over a UNIX domain socket:

```
/data/local/tmp/shield_modea.sock
```

Reasons for this choice:
- **Simplicity:** standard `send()`/`recv()` API, no serialisation framework needed.
- **Stability:** works across all Android versions (no binder restrictions).
- **Debuggability:** `socat` and `strace` can inspect traffic.
- **Low overhead:** no kernel-to-user buffer copying beyond what is strictly necessary.

### Connection lifecycle

```
Daemon starts → binds socket → listen(1) → accept()
                                              │
ModeAService starts → connect() ────────────▶│
                                              │
poll loop: events → send()     ─────────────▶│──▶ nativeReadEvent()
           recv() ←─ KILL cmd  ◀─────────────│
```

If the service disconnects (crash / stop), the daemon loops back to
`accept()` and waits for a reconnect.  Event emission from the ring buffer
continues regardless of client connectivity.

### JNI layer

`ModeAJni.java` loads `libmodea_jni.so` and exposes four native methods:

| Method | Thread-safety |
|---|---|
| `nativeConnect(String)` | Call once from init thread |
| `nativeDisconnect()` | Call from any thread |
| `nativeSendKill(int)` | Call from event handler thread |
| `nativeReadEvent(ShieldEventData)` | Call from `ModeAEventLoop` thread only |

`nativeReadEvent` is non-blocking (uses `MSG_DONTWAIT` internally).
The Java poll loop calls it every 20 ms to drain all pending events.

---

## 7  Build Instructions

### Prerequisites

```
clang 12+
Android NDK r25c or newer  (export ANDROID_NDK=/path/to/ndk)
libbpf submodule:
  git submodule add https://github.com/libbpf/libbpf modea/vendor/libbpf
Linux UAPI headers:
  apt install linux-libc-dev   (Ubuntu / Debian)
```

### Step 1 — Compile the BPF object

```bash
cd modea/build
./build_bpf.sh
# Output: bpf_out/shield_bpf.o  (arm64)
#         bpf_out/shield_bpf.x86.o  (x86_64)
```

### Step 2 — Build the daemon and JNI library

```bash
cd modea/build
mkdir cmake_out && cd cmake_out
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-30 \
  -DANDROID_STL=c++_static \
  -DLIBBPF_DIR=../../vendor/libbpf
cmake --build . --target shield_modea_daemon modea_jni -j4
```

### Step 3 — Deploy to device

```bash
adb push bpf_out/shield_bpf.o                    /data/local/tmp/shield_bpf.o
adb push cmake_out/shield_modea_daemon            /data/local/tmp/shield_modea_daemon
adb push cmake_out/libmodea_jni.so                /data/local/tmp/

# Set permissions
adb shell chmod 755 /data/local/tmp/shield_modea_daemon

# Copy JNI library into the app's lib directory (for development)
adb shell "run-as com.dearmoon.shield mkdir -p /data/data/com.dearmoon.shield/lib"
adb push cmake_out/libmodea_jni.so \
         /data/data/com.dearmoon.shield/lib/libmodea_jni.so
```

### Step 4 — Package for APK

1. Copy `shield_bpf.o` and `shield_modea_daemon` to `app/src/main/assets/`.
2. Copy `libmodea_jni.so` to `app/src/main/jniLibs/arm64-v8a/`.
3. Build the APK as normal with Gradle.

`ModeAController.deployBinaries()` extracts the assets at runtime.

---

## 8  Testing Instructions

See `modea/tests/telemetry_test.md` for the full 10-case test plan.

### Quick smoke test

```bash
# 1. Start daemon manually
adb shell su -c "/data/local/tmp/shield_modea_daemon \
  /data/local/tmp/shield_modea.sock \
  /data/local/tmp/shield_bpf.o" &

sleep 2

# 2. Tail Mode-A logs
adb logcat -s SHIELD_MODE_A &

# 3. Trigger a write event
adb shell "dd if=/dev/urandom of=/sdcard/modea_test.bin bs=8192 count=1"

# 4. Expected log line:
# SHIELD_MODE_A: WRITE pid=XXXX uid=XXXX file=/sdcard/modea_test.bin bytes=8192
```

---

## 9  Debugging Tips

### Verify tracepoint availability

```bash
adb shell su -c "ls /sys/kernel/tracing/events/android_fs/"
# Expected: android_fs_dataread_start  android_fs_datawrite_start  android_fs_fsync_start
```

If the directory is absent, the kernel was built without `CONFIG_ANDROID_FS_TRACING`.
Fall back to genirq tracepoints or vfs kprobes (see §11).

### Check pinned maps

```bash
adb shell su -c "bpftool map show"
adb shell su -c "bpftool map dump pinned /sys/fs/bpf/shield_pid_activity"
```

### Check loaded programs

```bash
adb shell su -c "bpftool prog show"
# Should list 4 programs: tp_fs_write, tp_fs_read, tp_fs_fsync, tp_sched_exec
```

### Raw kernel trace (no daemon needed)

```bash
adb shell su -c "echo 1 > /sys/kernel/tracing/events/android_fs/android_fs_datawrite_start/enable"
adb shell su -c "cat /sys/kernel/tracing/trace_pipe"
```

### libbpf verbose output

The `BpfLoader` routes libbpf diagnostics to logcat under the `SHIELD_BPF_LOADER` tag:

```bash
adb logcat -s SHIELD_BPF_LOADER
```

### Daemon not starting

```bash
adb shell su -c "/data/local/tmp/shield_modea_daemon \
  /data/local/tmp/shield_modea.sock \
  /data/local/tmp/shield_bpf.o"
# Run interactively to see stderr
```

Common causes:
- BPF object compiled for wrong arch → recompile with correct `--arch`.
- Missing `CONFIG_BPF_JIT` → events work but are slow; JIT is non-fatal.
- `bpffs` not mounted → `mount -t bpf none /sys/fs/bpf` (done automatically by daemon).

---

## 10  Known Limitations

| # | Limitation | Impact |
|---|---|---|
| L-01 | `android_fs_datawrite_start` is not defined in all GKI kernel builds prior to Android 12. | Mode-A unavailable on Android 11 devices with older GKI. |
| L-02 | `BPF_MAP_TYPE_RINGBUF` requires kernel ≥ 5.8. Kernels 4.19–5.7 (some Android 10/11 GKI builds) need `BPF_MAP_TYPE_PERF_EVENT_ARRAY` fallback. | Fallback not yet implemented in this prototype. |
| L-03 | `bpf_probe_read_kernel_str()` copies the dentry-cache path, which may be truncated at 256 bytes for deeply nested paths. | Long paths are silently truncated; detection still works but filenames in logs may be cut. |
| L-04 | The daemon must run as UID 0 (root). Devices without Magisk or equivalent root solution cannot use Mode-A. | Automatic fallback to Mode-B for non-rooted devices. |
| L-05 | BPF map entry limit is 4096 PIDs for `shield_pid_activity`. On devices with aggressive process spawning, older entries are evicted by the kernel hash-map replacement policy. | Burst state may be lost for very short-lived processes; unlikely to affect ransomware detection. |
| L-06 | The JNI layer uses a single global socket fd — only one concurrent connection supported. | Not an issue in practice; ModeAService is a singleton. |
| L-07 | `path_is_user_storage()` only checks `/sdcard` and `/storage` prefixes. Paths under `/mnt/` (secondary storage on some OEM ROMs) are not covered. | Events on secondary SD cards may be missed; extend the prefix check if needed. |
| L-08 | `ModeAController.deployBinaries()` performs a simple file-copy without hash verification of the daemon binary. | Mitigate in production by signing the binary and verifying before execution. |

---

## 11  Future Improvements

| Priority | Improvement | Effort |
|---|---|---|
| High | Add `PERF_EVENT_ARRAY` fallback for kernels < 5.8 | Medium |
| High | Sign daemon binary with APK signing key; verify hash before `su -c` execution | Medium |
| High | Extend `path_is_user_storage()` to cover `/mnt/` and OEM-specific paths | Low |
| Medium | Read `shield_pid_activity` map periodically and forward burst stats to `BehaviorCorrelationEngine` for richer correlation scoring | Medium |
| Medium | Add `tracepoint/syscalls/sys_enter_unlinkat` probe to detect mass file deletion (a ransomware cleanup step) | Low |
| Medium | Implement CO-RE (Compile Once – Run Everywhere) using BTF so the same BPF object works across multiple kernel versions | High |
| Medium | Replace polling with `ring_buffer__epoll_fd()` in the JNI layer to eliminate the 20 ms poll latency | Medium |
| Low | Introduce a `shield_dns_cache` BPF map to correlate domain lookups (via `getaddrinfo` tracepoints) with encryption bursts | High |
| Low | Move `pid_activity` window-rotation logic into the BPF program itself (requires bounded loops — kernel ≥ 5.17 with `bpf_loop()` helper) | High |
| Low | Generate BTF-annotated skeleton headers (`bpftool gen skeleton`) to replace hand-written tracepoint context structs | Low |
