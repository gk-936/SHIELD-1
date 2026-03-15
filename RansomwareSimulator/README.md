# RansomwareSimulator (Android)

RansomwareSimulator is an Android application designed **exclusively for controlled cybersecurity research and testing anti-ransomware systems**.  
It simulates both **crypto-ransomware** (file encryption) and **locker-ransomware** (screen locking) behavior in a reversible, test-friendly way.

## Project Setup

- **Language**: Kotlin  
- **Min SDK**: 26  
- **Target / Compile SDK**: 34  
- **Architecture**: Single-activity entry point (`MainActivity`) with a dedicated locker overlay activity (`LockerActivity`).

Open the `RansomwareSimulator` directory in Android Studio and let Gradle sync.

## Safety Constraints

- Operates **only inside** `/sdcard/ransom_test`.  
- Requires explicit user confirmation dialogs before simulations.  
- Uses a simple XOR-based encryption scheme with a fixed key so that **all changes are reversible**.  
- Includes a visible **UNLOCK** button on the locker screen and a 60-second auto-dismiss safety fallback.

## Test Data & Usage

Create test files (from a host shell):

```bash
adb shell mkdir -p /sdcard/ransom_test
adb shell "echo test1 > /sdcard/ransom_test/file1.txt"
adb shell "echo test2 > /sdcard/ransom_test/file2.txt"
```

Monitor simulator behavior:

```bash
adb logcat | grep RANSOM_SIM
```

## Main Features

- **Crypto Simulation**:  
  - Scans `/sdcard/ransom_test`.  
  - Encrypts files using XOR with a fixed key.  
  - Writes `filename.ext.locked` and deletes the original.  
  - Logs every major action with tag `RANSOM_SIM`.  
  - Optional **High Speed Mode**: 4 worker threads process files in parallel to simulate modern ransomware bursts.

- **Locker Simulation**:  
  - Fullscreen overlay activity (`LockerActivity`) that keeps the screen awake and visually blocks navigation.  
  - Shows a clear warning: `"Your files are locked. This is a simulation."`  
  - UNLOCK button and 60-second auto-dismiss.

- **Restore Files**:  
  - Locates all `.locked` files in `/sdcard/ransom_test`.  
  - Reverses the XOR operation and restores original filenames (removes `.locked`).  

## Extensibility

The code is intentionally structured and commented so you can:

- Swap XOR for AES with a fixed test key.
- Change directory scopes for different experiments (keeping them test-only).
- Integrate with external telemetry / detection engines.

**Never ship or sideload this app on production or personal devices. It is for lab / research environments only.**

