# SHIELD — Comprehensive Security & Architecture Audit

**Audit Date:** March 3, 2026  
**Audited By:** GitHub Copilot (Claude Sonnet 4.6)  
**Scope:** Full codebase review — detection logic, cryptographic subsystems, test evaluation, resilience mechanisms, and MVP readiness

---

## Understood Intent

SHIELD is a **rootless Android ransomware detection and mitigation system**. Its core loop is: monitor → detect → isolate → restore. It targets both file-encrypting ransomware (via entropy + SPRT + honeyfiles) and screen-locker ransomware (via Accessibility Service), with hardware-backed backup integrity as the recovery guarantee. The goal is an MVP that works on stock Android 7+ with no root, no cloud dependency, and no server infrastructure.

---

## STRENGTHS

**1. Multi-signal detection pipeline is architecturally sound.**
Combining Shannon entropy, KL-divergence, SPRT, honeyfile triggers, and behavior correlation is the right design. No single signal fires alone - you need at least two to hit the 70-point threshold. That's a real strength.

**2. Multi-region sampling for entropy is a smart bypass prevention.**
Sampling head/middle/tail and taking the maximum is a direct countermeasure to ransomware that only encrypts the first 4KB of files to save time. Most research-grade detectors miss this.

**3. Three-tier network blocking model is excellent.**
OFF → ON → EMERGENCY is exactly the right UX progression. Automatically escalating to block all traffic on confidence ≥ 70 without user interaction is the correct decision under time pressure.

**4. Cryptographic snapshot architecture is professional-grade.**
AES-256-GCM per-file encryption + per-file key wrapping via Android Keystore + hash chain integrity = genuinely publication-quality work. The `BackupEncryptionManager` design is one of the strongest parts of the project.

**5. TEE-anchored APK integrity gate is novel for an academic APK.**
Using HMAC-SHA256 with a hardware-backed key to detect APK tampering before the service initializes is a real contribution. Most student projects skip this entirely.

**6. LockerGuard covers a distinct attack vector most detectors ignore.**
File-encrypting ransomware gets all the research attention. Having a parallel Accessibility-Service-based locker detector for screen-ransom families is a meaningful differentiator.

**7. SPRT statistical testing uses correct Poisson math.**
The Wald sequential test with λ₀ = 0.1 and λ₁ = 5.0 event rates is mathematically correct. Most competing academic implementations use a simpler sliding-window counter.

---

## BLINDSPOTS AND WEAKNESSES

### Critical (Will break in real use or invalidate claims)

**1. Process attribution is fundamentally broken.**
In `UnifiedDetectionEngine.java`, the call to `correlationEngine.correlateFileEvent(filePath, event.getTimestamp(), android.os.Process.myUid())` passes **SHIELD's own UID** as the attacker UID. `Process.myUid()` returns the calling process's UID — which is SHIELD, not the app that modified the file. `BehaviorCorrelationEngine` then queries the database for network/file/locker events attributed to that UID and builds a profile of SHIELD's own behavior. The entire "Pseudo-Kernel" behavior attribution column is correlating against the wrong actor. This is the single most critical flaw in the detection pipeline. FileObserver does not expose which process caused a file event — there is no clean fix without root or a kernel eBPF probe.

**2. `killMaliciousProcess()` almost certainly does nothing.**
The code iterates `getRunningAppProcesses()`, finds the PID of the ransomware package, logs it, and then calls `am.killBackgroundProcesses(packageName)`. Look at the comment in the loop: *"We attempt to kill the process"* — but there is no actual `Process.killProcess(info.pid)` call inside the loop. The only active call is `killBackgroundProcesses()`, which only works on background processes. Any ransomware actively encrypting files is a foreground process (`START_STICKY` service with a notification), so this call silently does nothing. The detection fires, the "kill" call runs, ransomware continues encrypting, and your automated restore races against ongoing encryption.

**3. The test evaluation has zero visibility into false positives.**
Both test scripts (`test_shield_with_andmal2020.py`, `test_shield_with_cicmaldroid.py`) load **only ransomware samples**. The confusion matrix never has a benign column. A detector that marks every file event as ransomware would score 100% recall on your tests. You need benign samples — normal Android app file I/O, WhatsApp downloads, Spotify cache writes, Google Photos sync — to validate that the confidence score doesn't fire constantly during normal phone use. Right now this is your biggest evaluation gap.

**4. High-entropy legitimate file types will generate constant false positives.**
The entropy analyzer has no file extension allowlist. `.zip`, `.jpg`, `.mp4`, `.png`, `.pdf`, `.apk`, `.aac`, `.opus` — all of these have naturally high Shannon entropy (>7.5) because they are already compressed or encoded. When any of these files are modified (e.g., WhatsApp receiving a photo, a download completing), SHIELD will score 40 entropy points + 30 KL points = 70 total, instantly crossing the high-risk threshold and triggering emergency network isolation. This is a showstopper for daily usability.

**5. `EXPECTED_SIGNATURE_HASH = null` in production.**
In `SecurityUtils.java`, the expected APK signature hash is hardcoded to `null`. The `verifySignature()` method will always either return `false` (failing all security checks) or silently skip verification depending on its null-check logic. This was a placeholder that was never filled in. The RASP protection is neutered until this is set.

**6. Snapshot retention policy deletes the exactly wrong files during an active attack.**
The retention policy kicks in after every backup operation. When ransomware encrypts 150 files in a burst, the retention limit (100 files) forces deletion of the **oldest backups** — which are the original clean copies of the files encrypted earliest in the attack. You are systematically deleting the files you need most for recovery. The retention policy needs to be suspended during an active attack window, or at minimum should not purge files that are marked with an active `attack_id`.

---

### Significant (Limits MVP quality or real-world validity)

**7. SPRT rate parameters will false-positive on normal Android behavior.**
λ₁ = 5.0 files/second as the ransomware threshold means any app that writes 5+ files per second triggers H1. Google Photos backing up a burst of photos, a downloader completing a batch, a music app caching, or a game saving state would all exceed this rate. The 5.0 files/sec threshold comes from academic literature on PC ransomware — it has not been calibrated against real Android baseline behavior. You need to run the detection on a benign device for 24 hours and plot the actual file modification rate distribution before setting this parameter.

**8. Automated restore races against active encryption.**
`finalizeMitigationAndRestore()` waits 1 second after detection, then restores files. But if `killMaliciousProcess()` is ineffective (see point 2), the ransomware is still running during that 1 second, encrypting more files. The restore and the encryption are operating concurrently on the same file paths. Files restored will be immediately re-encrypted. The correct order is: kill or isolate the process first, verify it is gone, then restore.

**9. Test scripts test a Python reimplementation, not the Android code.**
The test scripts synthesize "file bytes" from API call count features in CICMalDroid by setting bytes randomly at a probability proportional to `crypto_activity`. This has no faithful correspondence to what real ransomware files look like on disk. You are testing whether a specific random seed produces high-entropy bytes, then detecting those synthetic bytes with your Python replica of the entropy algorithm. This tells you nothing about whether the Android `EntropyAnalyzer.java` behaves correctly on real ransomware-encrypted files.

**10. VPN blocklist is static and immediately outdated.**
The hardcoded Tor exit node ranges and ports (4444, 5555, 6666, 7777) are classic Android RAT ports from circa 2014–2018. Modern Android ransomware uses HTTPS over port 443 to standard cloud hosts (Pastebin, Telegram bots, GitHub Gists) as C2 channels. Your blocklist would never intercept these. The emergency all-traffic-block mode is the only effective network mitigation — the specific-port blocking gives a false sense of granular control.

**11. MediaStoreCollector disabled = blind spot for MSC-API ransomware.**
The README acknowledges it was disabled to prevent duplicate telemetry. But some Android ransomware families exclusively use `ContentResolver` to enumerate and modify media files, bypassing `FileObserver` entirely. Re-enabling it with proper deduplication (e.g., a timestamp-keyed LRU cache to deduplicate events with the same path within 500ms) would close this detection gap.

**12. `SharedPreferences` for the HMAC integrity baseline is manipulable on rooted devices.**
`ShieldIntegrityManager` stores the APK SHA-256 hash and HMAC in a private `SharedPreferences` XML file. On rooted devices (which are a realistic attack scenario since you explicitly check for root), an attacker can `adb shell` into `/data/data/com.dearmoon.shield/shared_prefs/shield_integrity.xml` and rewrite the stored hash to match a tampered APK. The HMAC protects against this only if the Keystore key is intact — but an attacker with root can also delete the Keystore entry, which triggers `KEY_INVALIDATED` / `TEE_KEY_MISSING`, which your code handles by simply re-initializing the baseline with the tampered APK. The TEE system provides real protection on non-rooted devices only.

**13. Score threshold of 70 is an unvalidated arbitrary value.**
There is no ROC curve, no precision-recall curve, no validation study showing that 70/130 is the optimal operating point. Moving it to 60 or 80 can dramatically change your precision-recall tradeoff. Before calling this an MVP, plot the distribution of detection scores across benign samples and ransomware samples and pick the threshold at their intersection.

---

### Minor (Polish / completeness gaps)

**14. `ServiceRestartReceiver` may violate Play Store policies.**
Auto-resurrection after force-stop is explicitly restricted on Android 8+ via the stopped-process state bit. While it may still work via `BOOT_COMPLETED`, the explicit self-restart-on-destroy loop could flag your app during Play Store review. Document this explicitly in your research context.

**15. No Doze Mode / App Standby accommodation.**
`FileObserver` event delivery can be delayed when the device enters Doze mode. The SPRT's inter-event timing (`deltaSeconds = (currentTime - lastEventTimestamp) / 1000.0`) will produce a large spike the next time the device wakes up, potentially triggering false H1 decisions. The 5-second cap on `deltaSeconds` is a partial mitigation, but not a full solution.

**16. The `RecoveryActivity` UI has no feedback during restore.**
Restore operations run on a background thread, but there is no progress indicator, spinner, or cancel mechanism in the UI. For MVP user experience, this is a visible gap.

**17. No explanation to the user of WHY a detection fired.**
The high-risk alert shows the file name and score but doesn't tell the user which signal fired (entropy? SPRT? honeyfile?). For any security tool, explainability is a core UX requirement.

---

## WHERE TO FOCUS FOR MVP

Priority order, highest impact first:

| Priority | What to fix | Why |
|---|---|---|
| 1 | Add a **file extension allowlist** to `EntropyAnalyzer` — skip `.zip`, `.jpg`, `.mp4`, `.png`, `.apk` etc. | Eliminates the dominant source of false positives before the app is usable |
| 2 | Collect **benign test data** and validate your false positive rate | Without this, all detection accuracy claims are half-statements |
| 3 | Fix **snapshot retention during active attack** — freeze deletions while `activeAttackId > 0` | Without this, automated recovery is unreliable by design |
| 4 | Make `killMaliciousProcess` honest — either **actually implement process stopping** (call `stopService` via intent, use `DevicePolicyManager` if available) or document clearly it is `killBackgroundProcesses` only and your recovery assumes the process continues | This is a correctness claim in your documentation that is not true |
| 5 | Set `EXPECTED_SIGNATURE_HASH` to the actual release signing hash or remove the claim from documentation | A null production value is a false security claim |
| 6 | Validate and tune the **SPRT rate parameters** (λ₀, λ₁) against real Android device baseline data | The current parameters will fire on any file-intensive Android app |
| 7 | Add a **benign/ransomware confusion matrix** with real samples to the test scripts | Completes the evaluation story |
| 8 | Replace the **process attribution** comment "PSEUDO-KERNEL" with an honest documentation note about the limitation, and remove the misleading UID parameter | This is a documentation honesty issue for academic submission |

---

## Summary

The features that are already at MVP quality and don't need more work: the cryptographic snapshot system, the TEE integrity gate, the three-tier network blocking, LockerGuard, and the auto-restart resilience mechanisms. Spend remaining time on the usability and evaluation correctness items listed above.

---

---

# SHIELD Mode-A — Standalone APK Audit

**Audit Date:** March 8, 2026  
**Audited By:** GitHub Copilot (Claude Sonnet 4.6)  
**Scope:** Full review of `modea/` — eBPF kernel program, self-contained BPF ELF loader, root daemon, JNI bridge, Android service layer, stub/merge architecture

---

## Architecture Summary

The Mode-A stack has five distinct layers:

```
android_fs / sched tracepoints (kernel)
        ↓  [eBPF: shield_bpf.c]
shield_pid_activity BPF hash map (kernel)
        ↓  [polled every 500 ms by root daemon]
shield_modea_daemon (C++, UID 0, aarch64)
        ↓  [Unix domain socket, length-framed]
modea_jni.so (JNI bridge, loaded in app process)
        ↓  [ShieldEventData, polled at 20 ms]
ModeAService → ModeAFileCollector → UnifiedDetectionEngine
```

This is the correct architecture for kernel-level process attribution. The eBPF path solves the fundamental flaw identified in the main SHIELD audit (finding #1) where `Process.myUid()` attributed events to SHIELD itself instead of the attacking process.

---

## STRENGTHS

**1. Self-contained BPF ELF loader removes the libbpf version matrix problem.**
`bpf_loader.cpp` parses ELF64 headers, applies SHT_RELA/SHT_REL relocations, patches `BPF_PSEUDO_MAP_FD` load instructions, and calls `BPF_MAP_CREATE` / `BPF_PROG_LOAD` / `perf_event_open` directly via syscall. No libbpf, no UAPI header version mismatch, no Android's broken `libbpf.so` availability questions. It works on kernel 4.9+ as claimed.

**2. Kernel-attributed UID via `bpf_get_current_uid_gid()` solves the root attribution problem.**
This is the primary contribution of Mode-A over Mode-B. The UID is read at interrupt time in the kernel, in the executing process's context — it is cryptographically correct attribution, not a heuristic. The value flows all the way through `pid_activity.uid` → `ev.uid` → `ShieldEventData.uid` → `event.setUid(data.uid)`, and is correctly logged per event.

**3. `__attribute__((packed))` + `_Static_assert` guards against silent ABI divergence.**
The size assertion `sizeof(struct shield_event) == 292` is checked at compile time. Since the event struct crosses three ABI boundaries (BPF → C daemon → JNI → Java), this catches any accidental padding insertion before it causes a silent protocol desync. This is production-grade defensive engineering.

**4. EEXIST case in `attach_tp_all_cpus()` is handled correctly.**
On kernel 4.14, attaching a second BPF program to a tracepoint via `PERF_EVENT_IOC_SET_BPF` can return `EEXIST` when the tracepoint slot is already occupied at the system level. The loader continues with `PERF_EVENT_IOC_ENABLE` in this case, which correctly arms the perf event without failing the attachment. Most BPF loaders get this wrong and fail noisily.

**5. SELinux solution is complete and correct.**
The three-part fix — `magiskpolicy --live` (MAC rule injection) + `chcon u:object_r:shell_data_file:s0` (label) + `chmod 0666` (DAC) — correctly layers MAC and DAC policies. Neither alone would work: `chmod 0666` without the `chcon` is blocked by SELinux; `chcon` without `chmod` is blocked by DAC. This is a non-trivial Android security problem handled precisely.

**6. Graceful degradation init sequence is well-engineered.**
The service performs root check → kernel compatibility check (reading `/proc/config.gz`) → binary deploy → daemon launch → 10-second socket poll before connecting. Each step has clear error propagation via `ACTION_UNAVAILABLE` broadcast with a human-readable reason. The daemon logs its own stderr to `/data/local/tmp/shield_modea_daemon.log` and `ModeAController.readDaemonStderr()` can retrieve it on failure. The fallback path to Mode-B is explicit.

**7. Socket reconnect-capable design.**
The daemon's main loop goes back to `accept()` after a client disconnects — it does not exit. If the Android service crashes and restarts, it reconnects to the same daemon without needing to reload the BPF programs or re-attach tracepoints. This is correct for a long-running kernel telemetry engine.

**8. Stub/merge architecture has zero ambiguity.**
Every stub class has explicit `MERGE NOTE` comments with exact import replacements. `ModeAFileCollector`, `ModeAService`, `ModeAJni` are all written to be drop-in additions to the main SHIELD project — no behavioral changes needed, only import swaps. The stub `UnifiedDetectionEngine` correctly exposes the same `processFileEvent()` signature as the real one.

**9. Kill protocol has basic sanity guards.**
`process_kill_command()` rejects PID 0 and PID 1, uses a `killed_pids` set to prevent repeated SIGKILL to the same process, and marks the PID in `shield_suspect_pids` BPF map for future cross-reference. The 4-byte length prefix + "KILL" tag check prevents accidental misinterpretation of garbage data as a kill command.

**10. Build system is correct and reproducible.**
`build_real.sh` compiles BPF bytecode with `clang -target bpf` (architecture-independent), cross-compiles the daemon with the NDK's `aarch64-linux-android30-clang++`, verifies the output is an ARM64 ELF, copies assets into the APK asset folder, and pushes to device — all in one script with `set -euo pipefail`. This is clean automation.

---

## BLINDSPOTS AND WEAKNESSES

### Critical (Will break in real use or invalidate research claims)

**1. All detection filters are disabled — the system is in permanent test mode.**
The three `android_fs` handlers have a `/* TEST: no filters */` comment and call `update_pid_activity()` unconditionally. `pid_activity.h` has `BURST_WRITE_THRESHOLD=1`, `BURST_READ_THRESHOLD=1`, `BURST_FSYNC_THRESHOLD=1`. The `uid_is_app()` check was removed from `tp_sched_exec`. `path_is_user_storage()` exists in the BPF source but is never called. This means every single read or write by every process on the device (kernel threads, `logd`, `zygote`, system services, `adbd`) fires a burst event. The map fills constantly, the daemon sends a continuous flood of events to the service, and the detection engine receives noise that bears no resemblance to ransomware behavior. The filters and thresholds that are the entire point of Mode-A exist in the code but are entirely bypassed. **Before any evaluation or demonstration, all three must be restored: `uid_is_app()` check in every handler, `path_is_user_storage()` check, and production thresholds (write≥50, read≥100, fsync≥20, window=5s).**

**2. The KILL socket is an unauthenticated privilege escalation primitive.**
The Unix socket at `/data/local/tmp/shield_modea.sock` is `chmod 0666` and labeled `shell_data_file`. The SELinux policy was explicitly patched to allow `untrusted_app` to write to `shell_data_file` sock_files. This means **any installed app** on the device can connect to the socket and send an 8-byte `KILL\x00\x00\x00\xNN` payload to issue a `kill(N, SIGKILL)` as root. The daemon accepts any connection from any peer with no authentication (no credential check, no SO_PEERCRED verification, no token). Any malicious app knowing the socket path can kill arbitrary PIDs including system processes and SHIELD itself. The socket must be authenticated — at minimum, read `SO_PEERCRED` on `accept()` and verify the connecting UID matches the expected app UID.

**3. `system("mount -t bpf none /sys/fs/bpf")` in `ensure_bpf_fs()` runs a shell as UID 0.**
`system()` spawns `/bin/sh -c "..."` as root. On Android, `/bin/sh` is `mksh` or the toybox shell, and the `PATH` used by the child process is inherited from the daemon's environment. If an attacker can place a binary named `mount` earlier in PATH than `/system/bin/mount`, or if the binary at `/bin/sh` has been replaced on a compromised device, this call executes arbitrary code as root. Replace with the direct `mount(2)` syscall: `mount(nullptr, "/sys/fs/bpf", "bpf", 0, nullptr)`, or use `execv("/system/bin/mount", ...)` with an absolute path and explicit argument vector.

---

### Significant (Limits correctness and reliability)

**4. TOCTOU race in `check_activity_map()` silently overwrites kernel-side counter increments.**
The burst-clear sequence is:
```
(a) map_lookup_elem(pid) → copy pa {write_count=N, burst_flags=SET}
(b) send event to service ...         ← BPF increments write_count to N+K here
(c) pa.burst_flags = 0; map_update_elem(pid, &pa)  ← writes back stale N, erasing K
```
The entire `pid_activity` struct is written back at step (c). BPF can increment counters between (a) and (c), and those increments are silently lost. The correct fix is to only update `burst_flags` in the map, not write back the whole struct: use a separate `map_update_elem` that writes only the flags field, or use `BPF_MAP_UPDATE_ELEM` with an atomic clear. Alternatively, clear `burst_flags` in a BPF-side program that runs on the next tracepoint hit for that PID.

**5. `/proc/<pid>/fd/` filename scan is almost never correct and often empty.**
`get_pid_last_file()` picks the file with the most recent `st_mtime` on its `/proc/<pid>/fd/<n>` symlink. Three problems:
- **Wrong file**: A process that has written 1000 files still has all those fds open. The "newest mtime" is not the file being written during the burst; it is whichever file the OS most recently updated the metadata on.
- **Empty on burst**: The 500ms polling latency means short-lived processes finish before the daemon polls. `opendir("/proc/<pid>/fd")` returns `EPERM` or fails, and the result is `""` → `<unknown>`.
- **Wrong process**: If PID N exited and PID N+1 reused the PID, the scan reads the new process's fds, not the ransomware's.
The correct approach for filename attribution requires capturing the pathname at tracepoint fire time in the BPF program (which has a verifier constraint on kernel 4.14 as discovered in this session), or maintaining a ring buffer of (pid, filename) pairs. Until then, the `<unknown>` result is the honest output and the `/proc/fd/` scan is misleading.

**6. `nativeReadEvent()` blocks indefinitely after reading the 4-byte length header.**
The JNI function sets the socket to `O_NONBLOCK`, reads 4 bytes with `MSG_DONTWAIT`, then **restores blocking mode** before calling `recv_all()` for the remaining 292 bytes. If the daemon stalls mid-send (e.g., blocked on a `send()` while its own client-fd write fails), the `recv_all()` loop in JNI blocks the `HandlerThread` forever. No timeout is set. The entire event poll loop is frozen: no more events reach `ModeAFileCollector`, detection stops, the UI stops updating. Use `MSG_DONTWAIT` or a `poll()` with timeout for all reads, or set `SO_RCVTIMEO` on the socket at connect time.

**7. The BPF hash map size of 4096 entries is completely inadequate at threshold=1.**
With all filters removed and burst threshold set to 1, every PID on the device — including hundreds of system processes — gets an entry on its first write. A typical rooted Android device has 300–800 active PIDs. When the map is full, `bpf_map_update_elem(&shield_pid_activity, &pid, &zero, BPF_ANY)` in `update_pid_activity()` silently returns an error inside the BPF program. The BPF program has no way to report this failure upward. New PIDs after map saturation are silently ignored — not a crash, but silent event loss with no visibility in userspace. Even with production filters re-enabled, 4096 is reasonable only if app-UID entries are evicted when the window expires. Add a daemon-side eviction pass: delete map entries where `burst_flags == 0` and `last_timestamp` is more than 2× `BURST_WINDOW_SEC` old.

**8. Daemon crash leaves a stale socket file, causing false-positive "daemon running" detection.**
`isDaemonRunning()` is `new File(SOCKET_PATH).exists()`. If the daemon crashes (SIGSEGV, OOM kill), the socket file is NOT cleaned up because `ModeADaemon::stop()` → `unlink(socket_path_)` only runs on graceful shutdown. The next call to `isDaemonRunning()` returns `true`, `nativeConnect()` fails with `ECONNREFUSED`, and the service reports "could not connect to daemon socket" and `stopSelf()`. The daemon is never restarted. Fix: if `isDaemonRunning()` returns true but `nativeConnect()` fails, delete the socket file and restart the daemon.

---

### Minor (Polish / correctness gaps)

**9. `exec_count` in `pid_activity` is tracked but generates no detection signal.**
`tp_sched_exec` increments `exec_count` and there is no `BURST_EXEC_THRESHOLD` and no `BURST_FLAG_EXEC` bit. The field is wasted space in every map entry. Either add an exec-burst detection path (useful for dropper-style ransomware that chains multiple executables) or remove the field.

**10. `path_is_user_storage()` is a dead function in BPF context — it always was.**
The function was defined but is now also never called. The BPF verifier does not warn about dead code. It consumes instruction budget from the BPF program if somehow called in future, and gives a misleading impression in code review that path filtering is operative.

**11. `ModeAController` leaks the daemon `Process` handle on double-start.**
If `startDaemon()` is called twice without an intervening `stopDaemon()`, the first `daemonProcess` reference is silently overwritten. The first `su` process becomes unreachable from Java (GC finalization may eventually call `destroy()`, but timing is undefined). More critically, `destroy()` sends SIGTERM to the `su` wrapper, not to the actual `shield_modea_daemon` child process — the daemon continues running after `stopDaemon()` returns unless the `pkill` command also succeeds.

**12. `process_kill_command()` silently loses kill commands on partial `MSG_DONTWAIT` reads.**
```cpp
n = recv(client_fd, buf, msg_len, MSG_DONTWAIT);
if (n < 8) return;
```
If `MSG_DONTWAIT` returns fewer bytes than `msg_len` (partial delivery), the function returns silently, leaving the remaining bytes in the socket buffer. On the next `poll()` tick, the daemon attempts to read a 4-byte length from the middle of the previous payload, gets a garbage value, and discards it. The kill command is lost with no log entry. Use `recv(MSG_WAITALL)` or a `recv_all()` loop for the payload read.

**13. `g_sock_fd` in `modea_jni.cpp` is a bare global with no thread-safety.**
`nativeConnect()` and `nativeDisconnect()` can be called from the service's `onDestroy()` on the main thread while `nativeReadEvent()` runs on `HandlerThread`. A concurrent `nativeDisconnect()` can close `g_sock_fd` while `nativeReadEvent()` has already read the fd value but hasn't called `recv()` yet. After `close()`, the fd number is available for reuse — the subsequent `recv()` on the stale fd hits a newly-opened file or socket. Guard all `g_sock_fd` accesses with a mutex, or use atomic CAS to swap it out on disconnect.

**14. `ensure_bpf_fs()` creates a permanent tracefs symlink without checking if the target already exists as a mountpoint.**
```cpp
symlink("/sys/kernel/tracing", "/sys/kernel/debug/tracing");
```
On devices where `debugfs` is mounted at `/sys/kernel/debug` and tracefs is a submount at `/sys/kernel/debug/tracing`, this `symlink()` call attempts to create a symlink over an existing directory, which fails with `EEXIST` — silently, since the return value is not checked. If it succeeds on a device where `/sys/kernel/debug/tracing` does not exist, the symlink points to the correct location. But the unchecked return means a failed symlink is indistinguishable from a successful one in the logs.

**15. `ModeAService.POLL_INTERVAL_MS = 20` fires 50 JNI round-trips per second during idle.**
The Java event loop calls `nativeReadEvent()` every 20ms. Each call makes two `fcntl()` syscalls (get/set flags) and one `recv(MSG_DONTWAIT)` that immediately returns `EAGAIN` when there are no events — the daemon only produces events at 500ms intervals. This is 150 unnecessary syscalls per second just to poll for incoming data. Use `poll()` with a 20ms timeout inside `nativeReadEvent()` itself; it will sleep until data arrives or timeout expires, collapsing the busy-poll to zero syscalls when idle.

**16. `struct android_fs_rw_args.pathname_loc` has a broken type comment that will mislead future developers.**
The field is declared as `__u32 pathname_loc; /* __data_loc: bits[15:0]=offset from ctx, bits[31:16]=len */` but is never read. Worse, the struct's prior `char *pathname` was replaced with `__u32 pathname_loc` + `__u32 cmdline_loc_`, totalling 8 bytes — which matches the 8 bytes of the original 64-bit `char *pathname` + `__u32 cmdline`. However on 64-bit kernel ABIs where `char *pathname` was 8 bytes and `char *cmdline` was 8 bytes, the current 4+4 layout is correct in size but the field semantics are wrong: the actual first field is a 32-bit `__data_loc` (not a pointer), and the cmdline is a separate `__data_loc` field. The comment refers to a decoding method that was never tested against a live tracepoint before it was removed. If anyone attempts to re-enable filename reading, the starting assumptions in this struct definition will need independent verification against the actual kernel `tracefs/events/android_fs/android_fs_datawrite_start/format` file on the target device.

---

## WHERE TO FOCUS BEFORE MERGE

Priority order, highest impact first:

| Priority | What to fix | Why |
|---|---|---|
| 1 | **Restore all production filters** — re-enable `uid_is_app()` in all three `android_fs` handlers, re-enable `path_is_user_storage()`, restore `BURST_WRITE_THRESHOLD=50`, `BURST_READ_THRESHOLD=100`, `BURST_FSYNC_THRESHOLD=20` | Without this, Mode-A cannot be evaluated — it produces only system noise |
| 2 | **Authenticate the KILL socket** — add `SO_PEERCRED` check on `accept()` and verify the connecting UID matches the expected app UID | This is a root-level privilege escalation on any device running Mode-A |
| 3 | **Replace `system("mount ...")` with a direct `mount(2)` syscall** | `system()` as root is a command injection risk |
| 4 | **Fix TOCTOU in `check_activity_map()`** — write back only `burst_flags=0`, not the entire `pa` struct | Current code silently drops kernel-side counter increments between read and write-back |
| 5 | **Fix `nativeReadEvent()` blocking** — set `SO_RCVTIMEO` at connect time or use `MSG_DONTWAIT` throughout | A stalled daemon currently freezes the Android event loop permanently |
| 6 | **Fix daemon crash → stale socket → no restart** — detect `ECONNREFUSED` on connect, clean the socket, restart the daemon | After a daemon OOM kill the service silently gives up with no recovery |
| 7 | **Add `SO_PEERCRED` / mutex to `g_sock_fd`** in JNI — for thread safety between `onDestroy()` and `HandlerThread` | Low-probability race but produces use-after-close on a reused fd number |
| 8 | **Add daemon-side map eviction** — delete `burst_flags==0` entries older than 2× window | Prevents the 4096-entry map from filling with idle PIDs under ANY filter configuration |

---

## Mode-A Summary

The kernel infrastructure (eBPF loader, daemon, JNI bridge, socket protocol) is architecturally sound and runs correctly on kernel 4.14 / Android 12 with Magisk root. The core innovation — accurate kernel-attributed UID and PID telemetry — works and is a genuine improvement over Mode-B's `FileObserver` approach. The three items that block MVP use are: (1) test-mode filters left in production code, (2) the unauthenticated root-kill socket, and (3) the `system()` shell invocation. Fix those three and the detection logic is evaluation-ready for merge into the main SHIELD project.
