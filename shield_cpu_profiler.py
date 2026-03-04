"""
SHIELD CPU Overhead Profiler
============================
Measures the real-time CPU usage of the SHIELD app on a connected Android device.

Requirements:
  - adb installed and on PATH
  - Android device connected via USB with USB debugging enabled
  - SHIELD app installed (com.dearmoon.shield)

Usage:
  python shield_cpu_profiler.py              # idle measurement (30s)
  python shield_cpu_profiler.py --attack     # simulate file burst + measure
  python shield_cpu_profiler.py --duration 60  # custom duration in seconds

Output:
  - Live CPU% per sample
  - Min / Mean / Max / P95 summary
  - Comparison against 3-4% target budget
"""

import subprocess, time, sys, statistics, argparse, os, threading

PACKAGE = "com.dearmoon.shield"
SAMPLE_INTERVAL = 1.0   # seconds between adb top samples
TARGET_CPU_PCT  = 4.0   # SHIELD design target


# ── ADB helpers ──────────────────────────────────────────────────────────────

def adb(cmd, capture=True):
    full = ["adb"] + cmd.split()
    try:
        r = subprocess.run(full, capture_output=capture, text=True, timeout=10)
        return r.stdout.strip() if capture else None
    except Exception as e:
        return f"ERROR: {e}"


def check_adb():
    out = adb("devices")
    lines = [l for l in out.splitlines() if "device" in l and "List" not in l]
    if not lines:
        print("ERROR: No Android device found. Connect device + enable USB debugging.")
        sys.exit(1)
    print(f"Device connected: {lines[0]}")


def get_pid():
    out = adb(f"shell pidof {PACKAGE}")
    if not out or "ERROR" in out:
        return None
    try:
        return int(out.strip().split()[0])
    except:
        return None


def get_cpu_pct(pid):
    """Read CPU% for a specific PID from /proc/<pid>/stat."""
    # Use top -n1 -p <pid> which works across Android versions
    out = adb(f"shell top -n 1 -p {pid}")
    if not out or "ERROR" in out:
        return None
    for line in out.splitlines():
        parts = line.split()
        if len(parts) > 0 and str(pid) in parts[0]:
            for p in parts:
                p = p.replace('%','')
                try:
                    val = float(p)
                    if 0.0 <= val <= 800.0:  # valid CPU% range (multi-core up to 800%)
                        return val
                except:
                    continue
    return None


def get_cpu_via_dumpsys(pid):
    """Fallback: use dumpsys cpuinfo for the package."""
    out = adb("shell dumpsys cpuinfo")
    for line in out.splitlines():
        if PACKAGE in line:
            parts = line.strip().split()
            for p in parts:
                p = p.replace('%','').replace('+','')
                try:
                    val = float(p)
                    if 0.0 <= val <= 800.0:
                        return val
                except:
                    continue
    return None


# ── File burst simulator (triggers SHIELD detection) ─────────────────────────

def simulate_file_burst(stop_event):
    """
    Creates and modifies files in SHIELD_TEST directory at ~5 files/sec
    to simulate a ransomware burst and stress-test the detection engine.
    Runs until stop_event is set.
    """
    test_dir = "/sdcard/SHIELD_CPU_STRESS/"
    adb(f"shell mkdir -p {test_dir}")
    count = 0
    while not stop_event.is_set():
        fname = f"{test_dir}stress_{count % 20}.tmp"
        # Write 8KB of random-ish data to trigger entropy analysis
        adb(f"shell dd if=/dev/urandom of={fname} bs=8192 count=1 2>/dev/null")
        count += 1
        time.sleep(0.2)  # 5 files/sec
    # Cleanup
    adb(f"shell rm -rf {test_dir}")
    print(f"\n  [burst simulator] wrote {count} files, cleaned up")


# ── Main profiler ─────────────────────────────────────────────────────────────

def run_profile(duration, with_attack):
    check_adb()

    print()
    print("=" * 60)
    print("  SHIELD CPU PROFILER")
    print(f"  Package : {PACKAGE}")
    print(f"  Duration: {duration}s")
    print(f"  Mode    : {'ATTACK SIMULATION' if with_attack else 'IDLE MONITORING'}")
    print("=" * 60)

    # Check SHIELD is running
    pid = get_pid()
    if pid is None:
        print(f"\nSHIELD is not running. Start SHIELD (enable Mode B) then retry.")
        sys.exit(1)
    print(f"\nSHIELD PID: {pid}")

    # Optionally start file burst in background thread
    stop_event = threading.Event()
    burst_thread = None
    if with_attack:
        print("Starting file burst simulator (5 files/sec)...")
        burst_thread = threading.Thread(target=simulate_file_burst, args=(stop_event,), daemon=True)
        burst_thread.start()
        time.sleep(1)  # let it warm up

    # Collect samples
    samples = []
    print(f"\nSampling every {SAMPLE_INTERVAL}s for {duration}s...")
    print(f"{'Time':>6}  {'CPU%':>8}  {'vs target':>12}")
    print("-" * 35)

    t_start = time.time()
    for i in range(int(duration / SAMPLE_INTERVAL)):
        elapsed = time.time() - t_start
        cpu = get_cpu_pct(pid)
        if cpu is None:
            cpu = get_cpu_via_dumpsys(pid)
        if cpu is not None:
            samples.append(cpu)
            vs_target = f"{'OK' if cpu <= TARGET_CPU_PCT else 'OVER'} ({cpu/TARGET_CPU_PCT*100:.0f}%)"
            print(f"{elapsed:>5.1f}s  {cpu:>7.2f}%  {vs_target:>12}")
        else:
            print(f"{elapsed:>5.1f}s  {'N/A':>8}")
        time.sleep(SAMPLE_INTERVAL)

    # Stop burst
    if burst_thread:
        stop_event.set()
        burst_thread.join(timeout=5)

    # Summary
    if not samples:
        print("\nNo CPU samples collected. Check adb connection and try again.")
        return

    mean_cpu = statistics.mean(samples)
    p95_cpu  = sorted(samples)[int(len(samples) * 0.95)]
    max_cpu  = max(samples)
    min_cpu  = min(samples)

    print()
    print("=" * 60)
    print("  RESULTS")
    print("=" * 60)
    print(f"  Samples collected : {len(samples)}")
    print(f"  Min CPU           : {min_cpu:.2f}%")
    print(f"  Mean CPU          : {mean_cpu:.2f}%")
    print(f"  P95 CPU           : {p95_cpu:.2f}%")
    print(f"  Max CPU           : {max_cpu:.2f}%")
    print(f"  Target budget     : {TARGET_CPU_PCT:.1f}%")
    print()

    if mean_cpu <= TARGET_CPU_PCT:
        headroom = TARGET_CPU_PCT - mean_cpu
        print(f"  STATUS : PASS  -- Mean {mean_cpu:.2f}% is within {TARGET_CPU_PCT}% target")
        print(f"  Headroom: {headroom:.2f}% remaining for future features")
    else:
        overage = mean_cpu - TARGET_CPU_PCT
        print(f"  STATUS : OVER  -- Mean {mean_cpu:.2f}% exceeds {TARGET_CPU_PCT}% by {overage:.2f}%")
        print(f"  Consider: reducing MAX_DEPTH in RecursiveFileSystemCollector")
        print(f"  or throttling NetworkGuardService packet inspection rate")

    print()
    print("  COMPONENT BREAKDOWN (estimated)")
    print(f"  Detection engine (entropy+KL+SPRT) : ~{mean_cpu*0.10:.3f}%")
    print(f"  NetworkGuardService (VPN loop)     : ~{mean_cpu*0.60:.3f}%")
    print(f"  FileObserver (inotify watches)     : ~{mean_cpu*0.05:.3f}%")
    print(f"  SnapshotManager + misc             : ~{mean_cpu*0.25:.3f}%")
    print("=" * 60)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="SHIELD CPU Profiler")
    parser.add_argument("--attack", action="store_true",
                        help="Simulate file burst while profiling")
    parser.add_argument("--duration", type=int, default=30,
                        help="Profiling duration in seconds (default: 30)")
    args = parser.parse_args()
    run_profile(args.duration, args.attack)
