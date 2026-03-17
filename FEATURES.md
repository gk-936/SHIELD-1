# SHIELD — Complete Feature Reference

**SHIELD** is an Android ransomware detection and mitigation system that operates across two complementary modes: **Mode A (Root/eBPF)** and **Mode B (User-space/Accessibility)**. This document catalogues every feature in the application, including core protection mechanisms and recently added security enhancements.

---

## 1. Data Collection & Hybrid Telemetry

### 1.1 Mode A: Kernel-Level Telemetry (Root)
Uses a custom eBPF-based daemon to monitor kernel system calls.
- **Direct I/O Monitoring**: Captures `READ`, `WRITE`, and `FSYNC` calls directly from the kernel.
- **Process Attribution**: Identifies the exact PID and UID of every file modification, even for background processes.

### 1.2 Mode B: User-Space Monitoring
Uses Android's `FileObserver` for non-rooted devices.
- **Recursive Directory Watching**: Monitors standard user directories (up to 8 levels deep).
- **Explicit Path Monitoring**: Supports targeted monitoring of specific app sandboxes (e.g., RanSim) even when parent directories (like `Android/`) are globally skipped.
- **Honeyfile Traps (NEW)**: Deploys unpredictable decoy files across the filesystem with device-fingerprint-derived names. Immediate "Absolute Kill" trigger on any access, bypassing heuristic scoring.
- **Heuristic Attribution**: Attributes modifications to the foreground application when direct UID info is unavailable.

### 1.3 Hybrid Event Engine (NEW)
- **Multi-Source Deduplication**: The `EventMerger` aggregates Mode A and Mode B events into a single high-fidelity stream.
- **Self-Monitoring Exclusion**: Automatically filters out SHIELD's own database writes and log rotations to prevent infinite feedback loops.

---

## 2. Detection Engine (SPRT & Entropy)

### 2.1 SPRT (Sequential Probability Ratio Test)
- **CRI-Weighted SPRT (NEW)**: Uses the Wald SPRT algorithm with Composite Ransomware Indicator (CRI) weights (Cohen's d calibrated) to detect anomalous modification rates with high mathematical confidence.
- **Optimized Min-Sample Guard**: Reduced to 5 samples (from 10) for faster detection without increasing false positives.

### 2.2 Entropy-Based Detection
- **Shannon Entropy Analysis**: Calculates the randomness of modified file chunks to detect encryption.
- **Extension Allowlist**: Ignores known high-entropy formats (images, videos, zips) to reduce false positives.

### 2.3 Behavior Correlation
- **KL-Divergence Scoring**: Measures the distance between current file modification patterns and a known "Normal" baseline.
- **Risk Aggregation**: Combines entropy, CRI-weighted SPRT state, and network behavior into a unified Risk Score. **Kill Threshold optimized to 43** (calibrated).

---

## 3. Network Protection (NetworkGuard)

### 3.1 C2 Communication Blocking
- **DNS Interception**: Monitors UDP port 53 and blocks resolution for known ransomware C2 domains (e.g., Pastebin, Telegram API, Discord, Paste.ee, Hastebin, Transfer.sh, Webhook.site).
- **Quad9 Integration**: Configures secure DNS (9.9.9.9) for hardware-level threat filtering.

### 3.2 Post-Detection Isolation
- **Emergency Kill Switch**: Automatically blocks 100% of IPv4 and IPv6 traffic once a ransomware threat is high-confidence.
- **IP Range Blacklisting**: Blocks known Tor exit nodes and suspicious bulletproof hosting ranges.

---

## 4. Snapshot, Recovery & Integrity

### 4.1 Tamper-Evident Snapshot Ledger
- **SHA-256 Hash Chain**: Every snapshot entry is cryptographically linked to the previous one. Any manual modification to the database breaks the chain.
- **Metadata Pinning**: Stores file size, modification time, and SHA-256 hashes to verify file integrity before restoration.

### 4.2 AES-256-GCM Backup Encryption
- **Key Rotation**: Every backup file uses a unique, freshly generated AES-256 key.
- **Hardware-Backed Keystore**: Master keys are stored in the Android Keystore (StrongBox supported) and never touch the filesystem.
- **Column Encryption**: Encrypts sensitive database columns like `backup_path` to prevent information leaks.

### 4.3 Automated Post-Kill Restoration
- **Await Death Protocol**: Polls for process termination and triggers `SnapshotManager.performAutomatedRestore()` only when the threat is confirmed dead.

---

## 5. OWASP Compliance & Anti-Tampering

### 5.1 RASP (Runtime Application Self-Protection)
- **Advanced Root Detection**: Detects Magisk, KernelSU, su binaries in PATH, and test-keys custom ROMs.
- **Locker Detection (Enhanced)**: Monitors `typeWindowsChanged` and `typeWindowStateChanged` to detect full-screen overlays and screen-lock attempts.
- **Tamper Detection**: Detects attached debuggers, hook frameworks (Xposed), and emulator environments.
- **Signature Verification**: Validates the APK's own SHA-256 signature against a hard-coded release baseline.

### 5.2 Supply Chain Security (NEW - M2)
- **Certificate Pinning**: Persists the installation certificate hash and alerts on any change (APK re-signing).
- **Native Library Pinning**: Baselines and monitors SHA-256 hashes of all `.so` library files to detect payload injection.

### 5.3 Security Misconfiguration Audit (M8)
- **Runtime Manifest Audit**: Checks for `debuggable=true`, `allowBackup=true`, and unsafe exported components.
- **OS Health Check**: Detects if ADB/USB debugging is active or if clear-text traffic is permitted.

### 5.4 Privacy Controls & Minimization (M6)
- **Consent Gating**: Requires explicit user acceptance before enabling telemetry.
- **Automated Data Purge**: Provides a one-tap mechanism to wipe all collected telemetry, with mandatory audit logging of the purge action.

---

## 6. Incident Analysis (Ransomware DNA)

### 6.1 AttackFamily Classification
Categorizes detected threats into five families (Crypto, Locker, Hybrid, Reconnaissance, Unknown) for better forensic analysis.

### 6.2 Forensic DNA Profiles
Generates comprehensive incident reports linking suspect PIDs, affected files, C2 attempts, and recovery status into a single "DNA Profile" in the database.
