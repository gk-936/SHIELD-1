# Mode-A Telemetry Test Plan

## 1  Environment Requirements

| Item | Requirement |
|---|---|
| Device | Physical Android device with kernel ≥ 5.10, root (Magisk) |
| Android version | Android 12 (API 31) or newer |
| Kernel config | `CONFIG_BPF=y`, `CONFIG_BPF_SYSCALL=y`, `CONFIG_TRACEPOINTS=y`, `CONFIG_BPF_JIT=y` |
| Storage | `/sdcard` accessible |
| ADB | USB debug enabled |
| Build host | Linux / macOS with NDK r25+, clang, libbpf |

---

## 2  Build Steps

```bash
# 1. Compile BPF object
cd modea/build
./build_bpf.sh

# 2. Build daemon + JNI library
mkdir cmake_out && cd cmake_out
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-30 \
  -DANDROID_STL=c++_static
cmake --build . --target shield_modea_daemon modea_jni

# 3. Push binaries to device
adb push bpf_out/shield_bpf.o            /data/local/tmp/shield_bpf.o
adb push cmake_out/shield_modea_daemon   /data/local/tmp/shield_modea_daemon
adb shell chmod 755 /data/local/tmp/shield_modea_daemon
```

---

## 3  Test Cases

---

### TC-01  Root and BPF sanity check

**Goal:** Confirm the device supports Mode-A prerequisites.

```bash
adb shell su -c "id"
# Expected: uid=0(root)

adb shell su -c "cat /proc/config.gz | zcat | grep -E 'CONFIG_BPF|CONFIG_TRACEPOINTS'"
# Expected lines include: CONFIG_BPF=y, CONFIG_BPF_SYSCALL=y, CONFIG_TRACEPOINTS=y
```

**Pass:** Both commands succeed with expected output.

---

### TC-02  Daemon starts and attaches tracepoints

**Goal:** Verify the daemon loads BPF programs successfully.

```bash
adb shell su -c "/data/local/tmp/shield_modea_daemon \
  /data/local/tmp/shield_modea.sock \
  /data/local/tmp/shield_bpf.o &"

sleep 2

adb shell su -c "bpftool prog show"
# Expected: four programs named tp_fs_write, tp_fs_read, tp_fs_fsync, tp_sched_exec

adb shell su -c "bpftool map show"
# Expected: shield_events (ringbuf), shield_pid_activity (hash), shield_suspect_pids (hash)
```

**Pass:** All four programs listed, all three maps listed.

---

### TC-03  EXEC event — app launch

**Goal:** Confirm sched_process_exec fires for a new process.

```bash
adb shell su -c "cat /sys/kernel/tracing/trace_pipe &"
adb shell am start -n com.android.calculator2/.Calculator
```

**Expected log output (adb logcat -s SHIELD_MODE_A):**
```
EXEC pid=<N> uid=<N> file=/system/bin/app_process64 bytes=0
```

**Pass:** EXEC event appears within 2 seconds.

---

### TC-04  WRITE event — file creation on /sdcard

**Goal:** Confirm android_fs_datawrite_start fires on file write.

```bash
adb shell "echo 'hello world test payload larger than 4096 bytes' > /sdcard/shield_test.txt"
# Note: use dd to write more than 4096 bytes to cross the write-size filter
adb shell "dd if=/dev/urandom of=/sdcard/shield_test.bin bs=8192 count=1"
```

**Expected log (logcat -s SHIELD_MODE_A):**
```
WRITE pid=<N> uid=<N> file=/sdcard/shield_test.bin bytes=8192
```

**Pass:** WRITE event with `bytes >= 4096` appears in logcat.

---

### TC-05  READ burst detection

**Goal:** Confirm that a burst of reads triggers READ events.

```bash
# Script that reads 110 files rapidly (above BURST_READ_THRESHOLD=100)
adb shell "for i in \$(seq 1 110); do cat /sdcard/DCIM/Camera/*.jpg > /dev/null 2>&1; done"
```

**Expected log:**
```
READ pid=<N> uid=<N> file=/sdcard/DCIM/Camera/IMG_xxx.jpg bytes=<N>
```

**Pass:** READ events appear after the 100th read for that PID.

---

### TC-06  FSYNC event

**Goal:** Confirm android_fs_fsync_start fires.

```bash
adb shell "python3 -c \"
import os
with open('/sdcard/fsync_test.txt','w') as f:
    f.write('x' * 8192)
    f.flush()
    os.fsync(f.fileno())
\""
```

**Expected log:**
```
FSYNC pid=<N> uid=<N> file=/sdcard/fsync_test.txt bytes=0
```

**Pass:** FSYNC event appears.

---

### TC-07  UID filter — system process ignored

**Goal:** Verify events from UID < 10000 are dropped.

```bash
adb shell su -c "dd if=/dev/urandom of=/sdcard/root_write.bin bs=8192 count=1"
```

Root has UID 0 — this write must not appear in Mode-A logs.

**Pass:** No WRITE event for `uid=0` in logcat.

---

### TC-08  Events reach UnifiedDetectionEngine

**Goal:** End-to-end test — kernel event appears in the SHIELD database.

1. Start ModeAService from the SHIELD app UI.
2. Write a large file to /sdcard:
   ```bash
   adb shell "dd if=/dev/urandom of=/sdcard/encrypt_sim.bin bs=65536 count=1"
   ```
3. Query the database:
   ```bash
   adb shell "run-as com.dearmoon.shield \
     sqlite3 databases/shield_events.db \
     'SELECT operation, filePath, mode FROM file_system_events ORDER BY id DESC LIMIT 5;'"
   ```

**Expected row:** `MODIFY | /sdcard/encrypt_sim.bin | MODE_A`

**Pass:** Row present with `mode = 'MODE_A'`.

---

### TC-09  Process kill via root daemon

**Goal:** Confirm kill command reaches daemon and terminates a PID.

```bash
# Start a background shell process and capture its PID
adb shell "sleep 100 &"
TEST_PID=$(adb shell "pgrep sleep | head -1")
echo "Test PID: $TEST_PID"

# Send kill from ModeAService broadcast
adb shell "am broadcast -a com.dearmoon.shield.MODEA_KILL_PID \
  --ei pid $TEST_PID com.dearmoon.shield"

# Verify process is gone
sleep 1
adb shell "ps -p $TEST_PID 2>&1"
# Expected: "error: no such process" or empty output
```

**Pass:** Process no longer listed in `ps` output.

---

### TC-10  Mode-A graceful fallback (no root)

**Goal:** Confirm ModeAService falls back gracefully when root is unavailable.

Disable root (or test on a non-rooted device).

**Expected logcat:**
```
SHIELD_MODE_A: Root not available — Mode-A disabled, falling back to Mode-B
```

**Expected broadcast:** `com.dearmoon.shield.MODEA_UNAVAILABLE` with `reason = "Mode A requires root access"`

**Pass:** No crash, Mode-B continues normally.

---

## 4  Debugging Commands

```bash
# Live kernel trace (verify tracepoint is firing at kernel level)
adb shell su -c "cat /sys/kernel/tracing/trace_pipe"

# Check pinned BPF maps
adb shell su -c "ls -la /sys/fs/bpf/"
adb shell su -c "bpftool map dump pinned /sys/fs/bpf/shield_pid_activity"

# Mode-A specific logcat
adb logcat -s SHIELD_MODE_A

# Full SHIELD logcat
adb logcat -s SHIELD_MODE_A:D UnifiedDetectionEngine:D BehaviorCorrelation:D

# Daemon process check
adb shell "ps -A | grep shield_modea"

# Check daemon socket
adb shell "ls -la /data/local/tmp/shield_modea.sock"
```

---

## 5  Expected Pass Criteria

| TC | Description | Must pass |
|---|---|---|
| TC-01 | Root + BPF sanity | Yes |
| TC-02 | Daemon + tracepoints load | Yes |
| TC-03 | EXEC event fires | Yes |
| TC-04 | WRITE event fires (> 4 KB) | Yes |
| TC-05 | READ burst detection | Yes |
| TC-06 | FSYNC event fires | Yes |
| TC-07 | System UID filtered | Yes |
| TC-08 | Events reach detection engine | Yes |
| TC-09 | Kill command works | Yes |
| TC-10 | Fallback on no-root | Yes |
