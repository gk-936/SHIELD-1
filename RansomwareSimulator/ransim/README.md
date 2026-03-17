# SHIELD RanSim (Ransomware Behaviour Simulator)

**SHIELD RanSim** is a robust, multi-scenario ransomware simulator designed exclusively for security research and detection engine testing. It provides a safe, controlled environment to mimic the behavioral patterns of real-world Android ransomware families (like SOVA, Cerber, Koler, and Svpeng) without posing a risk to user data.

---

## 🛡️ Safety & Research Constraints

To ensure safety during security research, the following constraints are hardcoded:
- **Sandboxed Operations**: All file operations are strictly confined to:
  `/sdcard/Android/data/com.dearmoon.shield.ransim/shield_ransim_sandbox/`
- **Reversible XOR "Encryption"**: Uses a simple XOR cipher (Key: `0x5A`) rather than real cryptographic algorithms.
- **Transparent Locker**: The locker overlay always displays the test password (**1234**) on the screen.
- **Emergency Stop**: A "STOP ALL & RESTORE" button is always available to immediately terminate all threads and restore original files.
- **Local Network Only**: C2 (Command & Control) simulation targets `127.0.0.1` only.

---

## 🚀 Simulation Scenarios

### 1. Crypto Ransomware
Mimics standard file-encrypting ransomware (e.g., **Cerber**, **SOVA v5**).
- **Behaviour**: Sequentially scans the sandbox and "encrypts" files at a rate of ~5 files per second.
- **Artifacts**: Appends `.enc` to filenames and drops a `RANSOM_NOTE.txt`.
- **Detection Target**: File writes, high-entropy transitions, and sequential I/O patterns.

### 2. Locker Ransomware
Mimics lock-screen ransomware (e.g., **Android/Koler**, **Svpeng**).
- **Behaviour**: Utilizes a `SYSTEM_ALERT_WINDOW` overlay and a Foreground Service to block user interaction.
- **Safety**: Includes a 60-second automatic timer and a visible exit password.
- **Detection Target**: Permission abuse (`SYSTEM_ALERT_WINDOW`) and persistent foreground service activity.

### 3. Hybrid Attack
The most aggressive scenario, mimicking a full attack chain (e.g., **SOVA** full deployment).
- **Behaviour**: Runs simultaneous file encryption, screen locking, and periodic C2 heartbeat attempts.
- **Detection Target**: Multi-layer behavioral correlation (Network + Disk + UI).

### 4. Recon → Encrypt
Mimics sophisticated ransomware that performs reconnaissance before attacking.
- **Behaviour**: Performs 30 seconds of "slow" file reading/scanning to evade threshold-based detection, followed by rapid encryption.
- **Detection Target**: SPRT (Sequential Probability Ratio Test) threshold transitions and reconnaissance phase discovery.

---

## 🛠️ Technical Details

- **Language**: Java
- **Target SDK**: 35 (Android 15+)
- **Architecture**:
    - `MainActivity`: UI for scenario selection and permission management.
    - `OverlayService`: Foreground service handling system overlays.
    - `RansomwareSimulator`: Core logic for file I/O and network simulation.
- **Required Permissions**:
    - `MANAGE_EXTERNAL_STORAGE`: For sandbox file operations.
    - `SYSTEM_ALERT_WINDOW`: For locker simulation.
    - `POST_NOTIFICATIONS`: For safety alerts.

---

## 📈 Research & Logging

Monitor simulation activity in real-time using adb:
```bash
# Filter for simulator specific events
adb logcat -s SHIELD_RANSIM

# Watch for SHIELD detection alerts
adb logcat | grep com.dearmoon.shield.HIGH_RISK_ALERT
```

---
**Disclaimer**: This tool is for authorized security research and educational purposes only. Do not use on devices containing sensitive data outside of a research sandbox.
