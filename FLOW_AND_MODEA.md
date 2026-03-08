# SHIELD — Full Working Flow & Mode A Research Guide

---

## Part 1: Complete Working Flow (Data Collection → Final Step)

---

### Overview

SHIELD's core loop is: **Collect → Detect → Mitigate → Restore**

It operates as a foreground Android service protecting documents, downloads, pictures, and camera folders in real time. Everything from file events to network packets to screen-lock behaviors feeds a composite scoring engine that drives automated isolation and recovery.

---

### Stage 1 — Data Collection (Four Parallel Channels)

All four collectors run simultaneously once the user taps "Start Protection".

#### 1.1 File System Events — `FileSystemCollector`

- Uses Android's `FileObserver` API, watching `CREATE`, `MODIFY`, `CLOSE_WRITE`, `MOVED_TO`, and `DELETE` events recursively up to depth 8.
- Maintains an LRU debounce cache (500 ms window) to suppress duplicate events for the same file.
- Only MODIFY, CLOSE_WRITE, and DELETE are forwarded for detection — CREATE alone is skipped unless it is an archive.
- Logs each qualifying event as a `FileSystemEvent` into `shield_events.db` (`file_system_events` table).
- Also notifies `SnapshotManager` to back up the pre-modification copy of any changed file into `secure_backups/`.

#### 1.2 Honeyfile Events — `HoneyfileCollector`

- On service start, decoy files are planted in every monitored directory.
- Any process that touches these files has no legitimate reason to do so — the access is immediately logged as a `HoneyfileEvent`.
- Honeyfile events are excluded from entropy analysis (the decoy files themselves would score high and cause false positives).

#### 1.3 Network Packet Metadata — `NetworkGuardService`

- Creates a local VPN tunnel via Android's `VpnService` API — no root required.
- Intercepts all outgoing packets, parsing both IPv4 and IPv6 headers to extract: destination IP, port, protocol (TCP/UDP), and packet size.
- Uses flow-based caching to suppress repeated logs for the same endpoint.
- Stores each unique connection as a `NetworkEvent` in `shield_events.db`.

#### 1.4 Screen-Locker Behavior — `LockerShieldService`

- Runs as an Accessibility Service, monitoring window state changes, overlay drawing attempts, and apps repeatedly forcing themselves to the foreground.
- Scores each suspicious UI event via `RiskEvaluator`.
- Stores results as `LockerShieldEvent` in the `locker_shield_events` table.
- Feeds into the behavior correlation path alongside file and network events.

---

### Stage 2 — Real-Time Backup (Concurrent with Collection)

Before analysis even starts, `SnapshotManager` is already protecting data:

1. **Baseline snapshot** — On service start, every file in the monitored directories is SHA-256 hashed and recorded in `shield_snapshots.db`.
2. **Pre-modification backup** — Every time `FileSystemCollector` fires a MODIFY event, the original file is copied to `secure_backups/` **before** the detection pipeline runs.
3. **Encryption** — Each backup file is encrypted with a fresh AES-256-GCM key. That per-file key is wrapped by a master key stored in Android Keystore (hardware-backed TEE). The wrapped key blob is stored in the `encrypted_key` column of the snapshot database.
4. **Hash chain integrity** — Each new backup entry is linked to the previous via `chain_hash = SHA-256(previous_chain_hash | metadata_hash)`. Deleting, reordering, or injecting a fake record breaks the chain, which is detected on next startup.
5. **Attack tracking** — When detection confidence reaches ≥ 40, `startAttackTracking()` opens an attack window. Every file modified from that point is tagged with the `attack_id` so selective restoration is possible later.
6. **Retention policy** — After each backup, the oldest entries are pruned if the total exceeds 100 files or 200 MB, preventing unbounded storage growth.

---

### Stage 3 — Detection Pipeline (Inside `UnifiedDetectionEngine`)

When `FileSystemCollector` forwards a MODIFY event, it lands on a background `HandlerThread` inside `UnifiedDetectionEngine`. The following happen in strict order:

#### Step 3.1 — Process Attribution (`resolveAttributionUid`)

The engine attempts to identify *which app* caused the file write using three strategies:

| Strategy | Method | Accuracy |
|---|---|---|
| 1. App-private storage path | Parse `/data/data/<pkg>/` or `/data/user/N/<pkg>/` via regex, resolve to UID via `PackageManager` | Deterministic — 100% accurate |
| 2. Scoped shared-storage path | Parse `Android/data/<pkg>/` or `Android/obb/<pkg>/` in `/sdcard/` paths | Deterministic — covers most ransomware paths |
| 3. Foreground-process heuristic | Query `ActivityManager.getRunningAppProcesses()`, return UID of first non-SHIELD foreground process | Time-correlated estimate — wrong for background writers |

If none match, returns `-1` (unattributed). This value is passed to `BehaviorCorrelationEngine`.

**Critical limitation:** `FileObserver` does not expose the writing PID/UID at the kernel level. This is the fundamental architectural gap that Mode A (eBPF) is designed to solve.

#### Step 3.2 — Whitelist Check

If the attributed package name is in the user's app whitelist (`WhitelistManager`), the event is dropped immediately and scoring is skipped.

#### Step 3.3 — File Size Gate

Files under 100 bytes are skipped — too small to measure entropy meaningfully.

#### Step 3.4 — Entropy Extension Allowlist

Files with naturally high-entropy extensions (`.jpg`, `.mp4`, `.zip`, `.apk`, `.png`, `.docx`, `.pdf`, etc. — 40+ types) are skipped entirely. Without this, any WhatsApp photo receipt would score 70 points and trigger emergency network isolation.

#### Step 3.5 — Shannon Entropy Analysis (`EntropyAnalyzer`)

Reads bytes from **three regions** of the file (head, middle, tail) and computes Shannon entropy:

$$H = -\sum_{i=0}^{255} p_i \log_2 p_i$$

Takes the **maximum** of the three samples — this specifically catches partial-encryption attacks where ransomware only encrypts the first few KB. Contributes up to **40 points**:

| Entropy | Points |
|---|---|
| > 7.8 | +40 |
| > 7.5 | +30 |
| > 7.0 | +20 |
| > 6.0 | +10 |

#### Step 3.6 — KL-Divergence Analysis (`KLDivergenceCalculator`)

Measures how uniform the byte distribution is compared to a perfectly random distribution. Encrypted data approaches uniformity (KL → 0). Complements entropy — both must be high to classify a file as encrypted. Contributes up to **30 points**:

| KL Divergence | Points |
|---|---|
| < 0.05 | +30 |
| < 0.10 | +20 |
| < 0.20 | +10 |

#### Step 3.7 — SPRT Statistical Detector (`SPRTDetector`)

Implements the **Wald Sequential Probability Ratio Test** using Poisson arrival rate math:

- **H₀:** Normal modification rate — λ₀ = 0.1 files/second
- **H₁:** Ransomware modification rate — λ₁ = 5.0 files/second
- **α = β = 0.05** (5% false positive and false negative rates)

The log-likelihood ratio is updated on every event. A minimum of 3 events is required before `ACCEPT_H1` can fire (to prevent single-event false triggers, since the per-event LLR increment of `log(50) ≈ 3.91` exceeds the Wald boundary of `log(19) ≈ 2.94`). Contributes up to **30 points**:

| SPRT State | Points |
|---|---|
| ACCEPT_H1 | +30 |
| CONTINUE | +10 |
| ACCEPT_H0 | +0 |

#### Step 3.8 — Behavior Correlation (`BehaviorCorrelationEngine`)

Queries the last 5 seconds of events for the attributed UID across all three event tables (file, network, locker). Raises the behavior score if:
- The same UID has both file events and network events (concurrent C2 + encryption).
- The same UID has locker events alongside file events (hybrid ransomware).

Adds up to **30 additional points** on top of the file-level score (total cap: 130).

#### Step 3.9 — Composite Score and Risk Classification

$$\text{total} = \min(\text{fileScore} + \text{behaviorScore},\ 130)$$

| Score | Classification | Action |
|---|---|---|
| ≥ 70 | HIGH RISK | Emergency response triggered |
| 40–69 | SUSPICIOUS | Attack tracking opened |
| < 40 | Low / Normal | Logged, no action |

---

### Stage 4 — Automated Mitigation (Score ≥ 70)

Three actions fire in sequence when `HIGH_RISK_ALERT` is broadcast:

#### 4.1 Process Termination (`killMaliciousProcess`)

Calls `ActivityManager.killBackgroundProcesses(packageName)`. **Known limitation:** this only kills background processes. Any ransomware running as a foreground service with an active notification is immune to this call. A `SEND_USAGE_ACCESS` or `DevicePolicyManager` kill would be needed for full effectiveness, but both require elevated permissions.

#### 4.2 Network Isolation (`NetworkGuardService` — EMERGENCY mode)

The `NetworkBlockReceiver` receives `HIGH_RISK_ALERT` and immediately commands `NetworkGuardService` to switch to **EMERGENCY mode**, which drops all outgoing packets regardless of destination. This cuts off any ransomware C2 channel within under one second of detection, even if the specific server IP is not on any blocklist.

Prior to emergency mode, two lower tiers exist:
- **OFF (default):** Monitor only, no dropping.
- **ON (user-enabled):** Block known malicious ports (4444, 5555, 6666, 7777) and Tor exit nodes.

#### 4.3 Automated File Restore (`finalizeMitigationAndRestore`)

After a 1-second delay (to allow process termination to take effect), `RestoreEngine` is called with the active `attack_id`. For every file tagged during that attack window:
1. Decrypt the `backup_path` column from the snapshot database.
2. Load the encrypted backup file from `secure_backups/`.
3. Unwrap the per-file AES key from the Keystore.
4. Decrypt the backup using AES-256-GCM (GCM tag verification is inherent — any tampered backup throws `AEADBadTagException` and fails cleanly).
5. Compare SHA-256 of current on-disk file to stored hash — skip if file hash matches (already clean).
6. Write the original file back to its original path.
7. Report counts of restored / skipped / failed files to `RecoveryActivity`.

---

### Stage 5 — Anti-Tampering and Resilience

Running in parallel throughout all stages:

- **TEE Integrity Gate** (`ShieldIntegrityManager`): On every service start, recomputes the APK's SHA-256 hash, re-signs it with a hardware-backed HMAC-SHA256 key in Android Keystore, and compares against the stored baseline in `SharedPreferences`. If tampered, the service halts and alerts the user before any detection logic runs.
- **Snapshot Directory Monitor** (`SnapshotDirectoryObserver`): A dedicated `FileObserver` on `secure_backups/` watching for DELETE, MOVED_FROM, and ATTRIB events. Any tampering with backup files broadcasts `BACKUP_DIR_TAMPER`.
- **Auto-restart on boot** (`BootReceiver`): `BOOT_COMPLETED` broadcast relaunches `ShieldProtectionService`.
- **Auto-restart on crash** (`ServiceRestartReceiver`): `onDestroy()` broadcasts a restart intent, creating a self-healing loop.
- **RASP checks** (`SecurityUtils`): Detects debugger, emulator, root indicators, Xposed hooks, and signature mismatch on every app launch.

---

### Stage 6 — Incident Reporting

After an attack, the `IncidentActivity` (Kotlin) renders two tabs:

1. **Attack Timeline** — chronological event log for the attack window pulled from all five database tables.
2. **DNA Report** — `RansomwareDnaProfiler` classifies the family (`CRYPTO_RANSOMWARE`, `LOCKER_RANSOMWARE`, `HYBRID`, etc.), summarizes signals that fired, and exports a PDF via `ShieldPdfReportGenerator`.

---

### Full Flow Diagram

```
Device File I/O
      │
      ▼
FileSystemCollector (FileObserver)
      │                  │
      │                  ▼
      │           SnapshotManager ──► AES-256-GCM encrypt ──► secure_backups/
      │                                     │
      │                                 Hash chain
      ▼
UnifiedDetectionEngine (background HandlerThread)
      │
      ├── resolveAttributionUid() ──► PackageManager / ActivityManager
      │
      ├── WhitelistManager.isWhitelisted() ──► SKIP if true
      │
      ├── EntropyAnalyzer (multi-region, extension allowlist) ──► 0–40 pts
      │
      ├── KLDivergenceCalculator ──► 0–30 pts
      │
      ├── SPRTDetector (Poisson LLR, min 3 events) ──► 0–30 pts
      │
      └── BehaviorCorrelationEngine (UID-keyed, 5-sec window) ──► 0–30 pts
                 │
                 └── queryRecentNetworkEvents()  ← NetworkGuardService (VPN)
                 └── queryRecentLockerEvents()   ← LockerShieldService (Accessibility)

compositeScore = min(fileScore + behaviorScore, 130)

      ├── score ≥ 40  ──► startAttackTracking()
      │
      └── score ≥ 70  ──► HIGH_RISK_ALERT broadcast
                              │
                  ┌───────────┼───────────────┐
                  ▼           ▼               ▼
          killBackground  NetworkGuard    RestoreEngine
          Processes()     EMERGENCY MODE  (AES-GCM decrypt
                          (drop all pkts)  + write back)
```

---

---

## Part 2: Mode A — Rooted eBPF Implementation (Research Guide)

---

### What is Mode A?

Mode A is the **planned kernel-level protection tier** of SHIELD, designed for rooted Android devices. It is listed as "Coming in v2.0" in the current app (`RootModeInfoActivity`). It solves the fundamental limitation of Mode B: `FileObserver` fires **after** a file has been closed, tells you **what** changed but not **who** changed it, and has no way to intercept the syscall before it happens.

Mode A replaces `FileObserver` with **eBPF probes attached to Linux kernel syscalls**, giving SHIELD exact PID/UID attribution on every file write, the ability to block a syscall in real time before any byte is written, and access to the calling process's memory for key extraction.

---

### Why Root is Required

Android's security model is a user-space sandbox. The boundaries that require root to cross for Mode A are:

1. **Loading eBPF programs into the kernel** — requires `CAP_BPF` (Linux 5.8+) or `CAP_SYS_ADMIN`. On stock Android, no app has these capabilities. On rooted devices, a root daemon (Magisk, KernelSU) grants them.
2. **Reading `/proc/<pid>/maps`** — requires either the same UID as the target process or `CAP_SYS_PTRACE`. Cross-process memory inspection for key extraction requires `ptrace` access, which is blocked by SELinux policy on stock Android.
3. **Calling `ptrace(PTRACE_ATTACH, pid)`** — blocked by Android's SELinux rules for all user-space apps. Root unlocks the permissive policy context needed to attach.
4. **Sending `SIGKILL` to processes in other UID namespaces** — standard kill requires matching UID or `CAP_KILL`. Ransomware runs under its own UID; SHIELD cannot kill it without CAP_KILL, which root grants.

---

### eBPF — Concepts You Need to Research

#### What is eBPF?

**Extended Berkeley Packet Filter (eBPF)** is a Linux kernel subsystem that lets you load verified, sandboxed programs into the kernel and attach them to hooks — without modifying kernel source code or loading a kernel module. eBPF programs are written in a restricted subset of C, compiled to eBPF bytecode, verified by the kernel's verifier (memory safety, no unbounded loops, no null dereferences), and then JIT-compiled to native machine code.

Key properties:
- Runs **in kernel context** — zero user-kernel copy overhead for events.
- **Safe** — the verifier statically proves all programs terminate and access only valid memory.
- **Programmable** — you choose which hooks to attach to.
- **Maps** — shared data structures between eBPF programs and user space (ring buffers, hash maps, arrays).

#### eBPF Hook Types Relevant to Mode A

| Hook Type | What it attaches to | Use in Mode A |
|---|---|---|
| `kprobe` | Entry of any kernel function | Hook `vfs_write()`, `vfs_rename()`, `vfs_unlink()` — fire before the operation completes |
| `kretprobe` | Return of any kernel function | Capture the return value (success/failure) of file operations |
| `tracepoint` | Static kernel trace points | `syscalls:sys_enter_write`, `syscalls:sys_enter_openat` — cleaner than kprobes for syscall entry |
| `LSM (BPF-LSM)` | Linux Security Module hooks | **Most powerful for Mode A** — attach to `security_file_open`, `security_inode_rename`, `security_inode_unlink` and **return a denial code** to block the syscall at the LSM layer |
| `uprobes` | User-space function entry | Hook Java crypto functions inside the ransomware process (APK DEX bytecode is JIT-compiled — harder but possible via `/proc/<pid>/maps` + uprobe on the JIT output) |

#### BPF-LSM (The One You Need Most)

BPF-LSM (Linux 5.7+, Android with CONFIG_BPF_LSM=y) lets you write an eBPF program that acts as a Linux Security Module hook. Unlike kprobes (which are observational), BPF-LSM hooks can **return a non-zero error code to deny the operation**. This is what enables "kill before first write":

```c
// Pseudocode — BPF-LSM hook on inode_permission
SEC("lsm/inode_permission")
int BPF_PROG(inode_permission_check, struct inode *inode, int mask) {
    u32 pid = bpf_get_current_pid_tgid() >> 32;
    u32 uid = bpf_get_current_uid_gid() & 0xFFFFFFFF;

    // Look up whether this PID is in our suspect map
    if (bpf_map_lookup_elem(&suspect_pids, &pid)) {
        // Deny the write before it touches the filesystem
        return -EPERM;
    }
    return 0;
}
```

#### eBPF Maps (Communication with User Space)

eBPF programs cannot call arbitrary kernel functions or make decisions based on context maintained in user space — they communicate via **BPF maps**:

| Map Type | Structure | Use in Mode A |
|---|---|---|
| `BPF_MAP_TYPE_HASH` | Key → value hash table | `suspect_pids` map: user space adds PID, eBPF checks on every write |
| `BPF_MAP_TYPE_RINGBUF` | Lock-free ring buffer | Stream file events (pid, uid, path, timestamp) to user space without polling |
| `BPF_MAP_TYPE_PERF_EVENT_ARRAY` | Per-CPU perf buffers | Alternative to ring buffer for event streaming |
| `BPF_MAP_TYPE_ARRAY` | Fixed-size array | Config flags (is blocking enabled? entropy threshold?) |

The main service (user space) writes PIDs to the `suspect_pids` map. The eBPF program reads from that map on every syscall and blocks writes from listed PIDs. This is how you implement "zero files encrypted" — block the very first suspicious write before it reaches the VFS layer.

---

### The Four Mode A Capabilities (from `activity_root_mode_info.xml`)

#### Capability 1 — Kernel Syscall Interception

**Goal:** Hook `open()`, `write()`, `rename()`, `unlink()` at the kernel level.

**Mechanism:**
- Attach `kprobe` or `tracepoint` programs to `vfs_write`, `vfs_rename`, `do_unlinkat`.
- Each probe fires synchronously in the kernel context of the writing thread — you get the PID, UID, file path, and number of bytes being written **before any data reaches the storage layer**.
- Use a `BPF_MAP_TYPE_RINGBUF` to push events to SHIELD's user-space daemon with sub-microsecond latency.

**Android-specific challenges:**
- The VFS path names are in kernel memory (`struct qstr` or `struct dentry` → `d_name`). Reading them requires `bpf_probe_read_kernel_str()` — requires verifier-approved pointer arithmetic.
- Android uses `ext4`, `f2fs`, and `sdcardfs`/`fuse` depending on the storage layer. Ransomware targeting `/sdcard/` actually writes through the FUSE layer (`/proc/pid/fd` → FUSE passthrough). You may need to hook FUSE operations (`fuse_file_write_iter`) separately from VFS operations.

**What to research:**
- Linux `kprobe` and `kretprobe` API documentation
- `bpf_probe_read_kernel_str()` for reading kernel memory safely
- FUSE operations in Android 11+ (Red Pill / FUSE passthrough)
- `vfs_write` call graph in `fs/read_write.c` (Linux kernel source)

---

#### Capability 2 — Deep Process Inspection

**Goal:** Inspect process memory maps and loaded libraries to identify injected ransomware modules.

**Mechanism:**
- Read `/proc/<pid>/maps` from the root daemon to get the virtual memory layout of the suspect process.
- Scan mapped anonymous executable regions (`rwxp` segments without a file backing) — these are JIT-compiled code or injected shellcode.
- Use `process_vm_readv()` (requires `CAP_SYS_PTRACE` or same UID) to read bytes from those regions.
- Compute entropy of the read bytes — encrypted or compressed shellcode has entropy > 7.5.
- Hash known ransomware module patterns and compare.

**What to research:**
- `process_vm_readv()` / `process_vm_writev()` Linux syscalls
- `/proc/<pid>/maps` format and parsing
- Android's ART JIT compiler — how DEX bytecode becomes native code in `/proc/<pid>/maps` anonymous mappings
- ELF injection techniques used by Android malware (GOT overwrite, `dlopen` abuse)

---

#### Capability 3 — Cryptographic Key Extraction

**Goal:** Extract ransomware encryption keys from process memory before files are locked.

**Mechanism:**
This is the most technically advanced Mode A feature. The approach:

1. **Identify the crypto library** — most Android ransomware uses `javax.crypto` (via ART), `Conscrypt`, or native `libcrypto.so`. From `/proc/<pid>/maps`, find which crypto library is loaded and at what base address.
2. **Hook the key generation point** — attach a `uprobe` (user-space probe) to the `AES_set_encrypt_key()` function in `libcrypto.so`. When the ransomware calls this function to schedule its AES key, the uprobe fires.
3. **Read the key argument** — the first argument to `AES_set_encrypt_key(const unsigned char *key, int bits, ...)` is a pointer to the raw key bytes. Use `bpf_probe_read_user()` to read those bytes before the call completes.
4. **Store the key** — write the extracted key bytes into a BPF ring buffer. SHIELD's daemon reads it and stores it in the Keystore.
5. **Decrypt after kill** — when the ransomware is killed, SHIELD has the key. Files encrypted before the kill can be decrypted directly, bypassing the backup-and-restore path.

**What to research:**
- Linux `uprobe` documentation — attaching to user-space functions within a specific PID
- `bpf_probe_read_user()` for reading user-space memory from a kernel probe
- OpenSSL/libcrypto AES key scheduling API (`AES_set_encrypt_key`, `EVP_EncryptInit_ex`)
- Android's Conscrypt JNI bridge — how `javax.crypto.SecretKey` maps to native `EVP_AEAD_CTX`
- ART interpreter vs. JIT — at which point does a `javax.crypto.Cipher.init()` call hit native code

---

#### Capability 4 — Process Kill Before First Write

**Goal:** Terminate a ransomware process the instant its first suspicious syscall is detected — zero files encrypted.

**Mechanism:**
This is the combination of Capabilities 1 and the privilege to send `SIGKILL`:

1. The BPF-LSM hook on `security_inode_permission` or `security_file_permission` fires before any write.
2. The hook checks the calling PID against an entropy pre-screen: one option is to let the first write through while recording it, compute entropy in user space (in the SHIELD daemon), and if entropy is suspicious, add the PID to the `suspect_pids` BPF map.
3. On the **second** suspicious write from that PID, the LSM hook returns `-EPERM`, blocking the syscall.
4. Simultaneously, the SHIELD daemon sends `SIGKILL` to the PID (root privilege required) or calls `bpf_send_signal(SIGKILL)` directly from the BPF program (available since Linux 5.3 via `bpf_send_signal()`).

**`bpf_send_signal()`** is the cleanest implementation — it sends a signal to the current task from within the eBPF program itself, eliminating the round-trip to user space:

```c
SEC("kprobe/vfs_write")
int kprobe_vfs_write(struct pt_regs *ctx) {
    u32 pid = bpf_get_current_pid_tgid() >> 32;
    if (bpf_map_lookup_elem(&kill_list, &pid)) {
        bpf_send_signal(SIGKILL); // kills the writing thread immediately
    }
    return 0;
}
```

**What to research:**
- `bpf_send_signal()` helper — Linux 5.3+, Android kernel requirements
- BPF-LSM with `CONFIG_BPF_LSM=y` — which Android kernels ship this
- `bpftime` — user-space eBPF runtime that can run some probes without kernel support (research alternative for devices without BPF-LSM)
- Android kernel versions by device: Pixel 6+ ships Linux 5.10 (GKI), which has BPF-LSM support

---

### Android-Specific eBPF Infrastructure

Android has had a **restricted in-kernel eBPF runtime since Android 9** (for network stats, traffic accounting via `netd`). However, the full BPF-LSM and `kprobe` APIs are only available on:

| Android Version | Kernel | eBPF Support |
|---|---|---|
| Android 9 | 4.9 | BPF_PROG_TYPE_SOCKET_FILTER, BPF_PROG_TYPE_CGROUP_SKB only |
| Android 10 | 4.14 | + BPF_PROG_TYPE_SCHED_CLS, BPF_PROG_TYPE_CGROUP_SOCK |
| Android 12 (GKI) | 5.10 | + kprobe, tracepoint, BPF-LSM (on supported devices) |
| Android 13+ | 5.15 | Full BPF feature set including ring buffers |

**Key constraint:** `CONFIG_BPF_LSM=y` must be set in the kernel config. Pixel 6 (GKI 5.10) has it. Most OEM kernels (Samsung, Xiaomi) do not. Rooted devices running a custom `KernelSU` or `Magisk` kernel (e.g., `NetHunter`) often have it enabled.

---

### Toolchain for Mode A Development

#### Writing and Compiling eBPF Programs

| Tool | Purpose |
|---|---|
| `libbpf` | The standard C library for loading, attaching, and managing eBPF programs. The foundation of all eBPF tooling. |
| `BPF CO-RE` (Compile Once – Run Everywhere) | Compiles eBPF programs with BTF (BPF Type Format) debug info so they run on different kernel versions without recompilation. Essential for Android device diversity. |
| `clang/LLVM` | Required compiler — `clang -target bpf` produces eBPF bytecode. GCC does not support the eBPF target. |
| `bpftool` | CLI utility to load, inspect, pin, and dump eBPF programs and maps. Your primary debugging tool. |
| `ply` / `bpftrace` | High-level scripting languages that compile to eBPF. Use for rapid prototyping of probes before writing production `libbpf` code. |

#### Android-Side Integration

| Component | Role |
|---|---|
| **Root daemon (C/C++)** | A native binary running as root that owns the `libbpf` lifecycle — loads eBPF programs, manages maps, reads ring buffers, communicates with the Java service via Unix domain socket or mapped shared memory. |
| **AIDL/Binder IPC** | The SHIELD Android service binds to the root daemon via a `Messenger` or AIDL interface to receive events and send commands (e.g., add PID to kill list). |
| **Android NDK** | Required to compile the root daemon for ARM64. Use `CMakeLists.txt` in the app module's `src/main/cpp/`. |
| **Magisk Module / KernelSU Module** | Package the root daemon as a Magisk/KernelSU module so it auto-starts on boot with root privileges. The module places the binary in `/data/adb/modules/shield_ebpf/` and uses a `service.d` script to launch it. |

---

### Minimum Android Kernel Requirements for Mode A

To research compatibility, look for these kernel `CONFIG` options:

```
CONFIG_BPF=y                    # Core BPF subsystem
CONFIG_BPF_SYSCALL=y            # Enables bpf() syscall
CONFIG_BPF_JIT=y                # JIT compilation (performance)
CONFIG_BPF_LSM=y                # BPF-LSM hooks (needed for blocking)
CONFIG_DEBUG_INFO_BTF=y         # BTF — needed for CO-RE
CONFIG_KPROBE_EVENTS=y          # kprobe/kretprobe tracing
CONFIG_TRACEPOINTS=y            # Tracepoint-based probes
CONFIG_UPROBES=y                # User-space probes (for key extraction)
CONFIG_PERF_EVENTS=y            # perf event array maps
```

Check any device's kernel config at `/proc/config.gz` (if enabled) or from its open-source kernel repository on GitHub.

---

### What Mode A Fixes in Mode B

| Mode B Limitation | Mode A Solution |
|---|---|
| `FileObserver` fires **after** file close — ransomware already wrote | eBPF kprobe on `vfs_write` fires **before** data reaches VFS |
| No PID/UID attribution from `FileObserver` | eBPF context provides `bpf_get_current_pid_tgid()` — exact PID/UID, zero ambiguity |
| `killBackgroundProcesses()` cannot kill foreground services | `bpf_send_signal(SIGKILL)` from kernel context bypasses all user-space restrictions |
| Process attribution broken for background writers | `kprobe` fires in the writing thread's context — attribution is always correct |
| Cannot extract encryption keys | `uprobe` on `AES_set_encrypt_key` in `libcrypto.so` captures the key before it's used |
| Retention policy deletes old backups under attack | Key extraction means restore does not need backups — decrypt in place |

---

### Research Starting Points

1. **eBPF fundamentals:** `ebpf.io` — the official eBPF documentation hub. Read "What is eBPF?" and the "Program Types" reference.
2. **libbpf tutorial:** `github.com/libbpf/libbpf-bootstrap` — minimal skeleton examples for kprobe, tracepoint, and BPF-LSM programs with CO-RE.
3. **BPF-LSM:** Brendan Gregg's blog post "BPF LSM" (2020) + the kernel commit `bpf: lsm: Add BPF LSM hooks` (Linux 5.7).
4. **Android eBPF internals:** Android source `system/bpf/` — the AOSP eBPF loader used by `netd` and `bpfloader`. Shows how Android loads BPF programs at boot.
5. **KernelSU for eBPF:** `kernelsu.org/guide` — how KernelSU grants `CAP_BPF` to a trusted daemon without full permissive SELinux.
6. **bpftrace for prototyping:** `bpftrace/bpftrace` on GitHub — write one-liner probes to verify which kernel functions are called during a file write before writing production C.
7. **uprobe for crypto hooking:** Research paper "eBPF for security monitoring" (USENIX ATC 2021) covers attaching uprobes to shared library functions.
8. **Android kernel source:** `android.googlesource.com/kernel/common` — look at `arch/arm64/configs/gki_defconfig` to verify which BPF options are enabled in GKI kernels.
