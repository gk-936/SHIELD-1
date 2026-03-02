# SHIELD — Complete Feature Reference

**SHIELD** is an Android ransomware detection and mitigation system that operates entirely without root access, making it deployable on 99%+ of Android devices (API 24+). This document catalogues every feature in the application, separated into features that existed in the original system and features added in the latest security upgrade.

---

## Table of Contents

1. [Existing Features](#existing-features)
   - [Data Collection](#1-data-collection)
   - [Detection Engine](#2-detection-engine)
   - [Network Protection](#3-network-protection)
   - [Snapshot and Recovery](#4-snapshot-and-recovery)
   - [Anti-Tampering and Resilience](#5-anti-tampering-and-resilience)
   - [LockerGuard — Anti-Locker Protection](#6-lockerguard--anti-locker-protection)
   - [User Interface](#7-user-interface)
   - [Data Storage](#8-data-storage)
2. [Newly Added Features](#newly-added-features)
   - [Snapshot Hash Chain — Tamper-Evident Ledger](#1-snapshot-hash-chain--tamper-evident-ledger)
   - [AES-256-GCM Backup File Encryption](#2-aes-256-gcm-backup-file-encryption)
   - [Encrypted Snapshot Database Columns](#3-encrypted-snapshot-database-columns)
   - [Snapshot Integrity Self-Check on Startup](#4-snapshot-integrity-self-check-on-startup)
   - [Snapshot Directory Monitoring](#5-snapshot-directory-monitoring)
   - [Per-File Key Rotation via Android Keystore](#6-per-file-key-rotation-via-android-keystore)
   - [Snapshot Expiry and Retention Policy](#7-snapshot-expiry-and-retention-policy)
3. [TEE-Anchored Integrity System](#tee-anchored-integrity-system)
   - [IntegrityResult — Verification Outcome Enum](#1-integrityresult--verification-outcome-enum)
   - [ShieldIntegrityManager — APK Hash + HMAC Baseline](#2-shieldintegritymanager--apk-hash--hmac-baseline)
   - [IntegrityLogger — Persistent Event Audit Trail](#3-integritylogger--persistent-event-audit-trail)
   - [EventDatabase v3 Migration](#4-eventdatabase-v3-migration)
   - [ShieldProtectionService — Integrity Gate](#5-shieldprotectionservice--integrity-gate)

---

## Existing Features

---

### 1. Data Collection

#### 1.1 File System Monitoring — `FileSystemCollector`

The `FileSystemCollector` uses Android's `FileObserver` API to watch multiple user directories (Documents, Downloads, Pictures, DCIM) for file system activity in real time. It monitors `CREATE`, `MODIFY`, `CLOSE_WRITE`, `MOVED_TO`, and `DELETE` events using multi-flag bitmask handling to reliably distinguish individual event types on all Android versions. When a qualifying event is detected, the file path is forwarded directly to the `UnifiedDetectionEngine` for analysis, and to `SnapshotManager` for real-time backup tracking.

#### 1.2 Honeyfile Collection — `HoneyfileCollector`

The `HoneyfileCollector` plants decoy files (honeyfiles) in each monitored directory when protection starts. These files have no legitimate use by any app, so any access, modification, or deletion of them is immediately treated as a strong indicator of malicious activity. Every honeyfile interaction is logged as a `HoneyfileEvent` in the SQLite database and raises the overall threat detection score. The system is careful to exclude honeyfiles from entropy analysis so they do not generate false positives.

#### 1.3 Network Packet Collection — `NetworkGuardService`

The `NetworkGuardService` uses Android's `VpnService` API to create a local VPN tunnel that intercepts all outgoing network packets without requiring root. It extracts metadata from both IPv4 and IPv6 packets — including destination IP, port, protocol, and packet size — and stores each unique connection as a `NetworkEvent`. A flow-based caching mechanism is used to suppress duplicate logging of repeated connections to the same endpoint, keeping database growth manageable.

#### 1.4 Accessibility Event Collection — `LockerShieldService`

The `LockerShieldService` runs as an Android Accessibility Service and monitors for UI events that are characteristic of screen-locker ransomware — such as apps requesting device administrator rights, drawing overlays on top of system UI, or repeatedly bringing themselves to the foreground. Each suspicious event is recorded as a `LockerShieldEvent` and scored by the `RiskEvaluator`. This collection channel specifically targets ransomware families that lock the device visually rather than encrypting files.

---

### 2. Detection Engine

#### 2.1 Shannon Entropy Analysis — `EntropyAnalyzer`

The `EntropyAnalyzer` computes the Shannon entropy of a modified file using multi-region sampling — reading bytes from the head, middle, and tail sections rather than the full file. This approach is specifically designed to detect partial encryption attacks where ransomware only encrypts the first few kilobytes of each file. A score above 7.5 bits per byte is considered suspicious, and a score above 7.8 is treated as high confidence of encryption. The result contributes up to 40 points toward the composite confidence score.

#### 2.2 KL-Divergence Analysis — `KLDivergenceCalculator`

The `KLDivergenceCalculator` measures the Kullback-Leibler divergence between the observed byte distribution of a file and a perfectly uniform distribution. Encrypted data has a near-uniform byte distribution, resulting in a very low KL-divergence value (below 0.1), while structured plaintext data such as documents or source code has a much higher divergence. This metric is complementary to entropy — a file must exhibit both high entropy and low divergence to be classified as encrypted. It contributes up to 30 points to the composite confidence score.

#### 2.3 SPRT Statistical Detector — `SPRTDetector`

The `SPRTDetector` implements the Sequential Probability Ratio Test using Poisson arrival rate mathematics to detect ransomware based on the rate of file modifications over time, rather than the content of any individual file. It tests two hypotheses: H0 (normal modification rate of 0.1 files/second) versus H1 (ransomware rate of 5.0 files/second), with a 5% error tolerance on each side. When H1 is accepted, the detector contributes 30 points to the confidence score. This approach catches ransomware even if entropy analysis alone is inconclusive.

#### 2.4 Unified Detection Engine — `UnifiedDetectionEngine`

The `UnifiedDetectionEngine` orchestrates all three detection algorithms on a background thread and combines their outputs into a single composite confidence score ranging from 0 to 100 (with a maximum overage of 130 when SPRT and file-level signals fire simultaneously). When the score reaches or exceeds 70, the engine broadcasts a `HIGH_RISK_ALERT` intent that triggers both a user-visible alert dialog and automatic network blocking via the `NetworkGuardService`. Scores between 40 and 69 are treated as medium risk and begin attack tracking via `SnapshotManager`.

---

### 3. Network Protection

#### 3.1 Three-Tier Network Blocking — `NetworkGuardService`

The `NetworkGuardService` implements a three-tier blocking model. In **OFF mode** (default), it monitors traffic without blocking anything. In **ON mode** (user-enabled), it blocks outbound connections to known malicious ports (4444, 5555, 6666, 7777), Tor exit node IP ranges, private network addresses, and localhost. In **EMERGENCY mode** (automatically triggered when confidence ≥ 70), it blocks all outgoing traffic entirely, cutting off any ransomware command-and-control communication even if the specific server is not on a blocklist. This progression ensures no false blocking during normal use while guaranteeing isolation during a confirmed attack.

#### 3.2 Automatic Emergency Blocking — `NetworkBlockReceiver`

The `NetworkBlockReceiver` listens for the `HIGH_RISK_ALERT` broadcast emitted by the detection engine and immediately commands the `NetworkGuardService` to switch into emergency mode. This means network isolation happens automatically in under one second of a ransomware detection, without requiring any user interaction. The receiver is declared in the Android manifest and runs even when the app UI is not in the foreground, ensuring no detection event is ever missed.

---

### 4. Snapshot and Recovery

#### 4.1 File Baseline Snapshot — `SnapshotManager`

The `SnapshotManager` creates a baseline snapshot of all monitored directories by scanning each file, computing its SHA-256 hash, and storing the metadata in the snapshot database. This baseline represents the known-good state of the file system before any attack occurs. When a file is subsequently modified, the original pre-modification copy is backed up to the app's private `secure_backups/` directory so it can be restored later. The snapshot creation runs on a dedicated single-thread executor to avoid blocking the main thread.

#### 4.2 Attack-Scoped File Tracking

When the detection engine raises a medium-confidence alert, `SnapshotManager.startAttackTracking()` is called, which opens an attack window in the database. Every file that is created, modified, or deleted while an attack window is open is marked with the attack's ID in the `modified_during_attack` column. This scoping mechanism means the restore engine knows exactly which files were affected during a specific incident and can selectively restore only those files, leaving unrelated changes intact.

#### 4.3 Selective File Restoration — `RestoreEngine`

The `RestoreEngine` takes an attack ID, queries the database for all files marked during that attack, and restores each one from its backup copy. Before restoring a file, it computes the current SHA-256 hash of the file on disk and compares it to the stored hash; if they match, the file is skipped to avoid unnecessary I/O. If the file was deleted, it is recreated from the backup. The result is reported back to the UI as a `RestoreResult` object with counts of restored, failed, and skipped files.

#### 4.4 Recovery UI — `RecoveryActivity`

The `RecoveryActivity` provides a dedicated screen where users can manually trigger a baseline snapshot and initiate a restore after an attack. It displays the timestamp of the last snapshot, the current threat status, and a restore button that becomes active only when a valid attack ID exists. Restore operations are performed on a background thread with the result displayed on the UI thread once complete. Users can also cancel and return to the main screen without performing a restore.

---

### 5. Anti-Tampering and Resilience

#### 5.1 RASP Runtime Protection — `SecurityUtils`

The `SecurityUtils` class performs Runtime Application Self-Protection checks every time the app starts. It detects the presence of a debugger (`Debug.isDebuggerConnected()`), emulator characteristics in `Build` fields, root indicators via common su binary paths, hook frameworks such as Xposed, and APK signature tampering by comparing the runtime signature hash against the expected production value. If any check fails, the anomaly is logged and the security status flag is set to false, allowing the app to limit its functionality when operating in a compromised environment.

#### 5.2 Auto-Restart on Boot — `BootReceiver`

The `BootReceiver` listens for the `BOOT_COMPLETED` system broadcast and automatically relaunches the `ShieldProtectionService` when the device restarts. This ensures continuous protection without requiring the user to manually open the app after a reboot. The receiver requires the `RECEIVE_BOOT_COMPLETED` permission and is declared in the manifest with appropriate intent filters. Without this, ransomware that survives a reboot could operate undetected until the user next opens SHIELD.

#### 5.3 Auto-Restart on Crash — `ServiceRestartReceiver`

The `ShieldProtectionService` broadcasts a restart intent in its `onDestroy()` callback, which is received by `ServiceRestartReceiver` and used to immediately re-start the service. This creates a self-healing loop where the protection service restarts itself within milliseconds of being killed — whether by the Android system's low-memory killer, by user force-stopping, or by a crash. Combined with the boot receiver, this makes the protection service highly resilient against both intentional and accidental termination.

---

### 6. LockerGuard — Anti-Locker Protection

#### 6.1 Locker Threat Detection — `LockerShieldService`

The `LockerShieldService` is an Android Accessibility Service that monitors window state changes, package visibility events, and system dialog appearances to detect screen-locker ransomware, which works by overlaying a full-screen ransom note rather than encrypting files. The service runs in the background continuously and feeds every suspicious accessibility event to the `RiskEvaluator` for scoring. Detected events are stored in a dedicated `locker_shield_events` table in the database for historical analysis.

#### 6.2 Locker Risk Scoring — `RiskEvaluator`

The `RiskEvaluator` assigns a numeric risk score to each accessibility event based on a set of behavioral heuristics — for example, apps attempting to draw system-level overlays score higher than apps simply requesting focus. Multiple events from the same package within a short time window are scored cumulatively, so sustained locker behavior is escalated to a high-risk alert more quickly than isolated events. The score is incorporated into the overall threat confidence level managed by the detection engine.

#### 6.3 Emergency Recovery UI — `EmergencyRecoveryActivity`

The `EmergencyRecoveryActivity` is designed to remain accessible even when a screen-locker ransomware app has drawn a full-screen overlay on top of the regular UI. It can be launched directly from an ongoing notification and provides a minimal interface with a single "Emergency Recover" button that attempts to kill the locker app's overlay and restore normal device access. This activity operates at the highest possible window layer to ensure it appears above any attacker-controlled UI.

---

### 7. User Interface

#### 7.1 Main Dashboard — `MainActivity`

The `MainActivity` is the central control panel of SHIELD, providing buttons to start and stop protection, request all required runtime permissions, launch the VPN network monitor, and toggle network blocking on or off. It displays a real-time status view showing whether protection is active, and listens for the `HIGH_RISK_ALERT` broadcast to immediately show an alert dialog with the detected file name, confidence score, and mitigation status. The dashboard also shows a count of monitored and infected files pulled from the snapshot database.

#### 7.2 Real-Time Log Viewer — `LogViewerActivity`

The `LogViewerActivity` provides a live scrolling feed of all events collected by SHIELD, displayed as color-coded cards in a `RecyclerView`. Events are categorized by severity (CRITICAL in red, HIGH in orange, MEDIUM in yellow, LOW in green) and can be filtered by type: ALL, FILE_SYSTEM, HONEYFILE_ACCESS, NETWORK, or DETECTION. Each card shows the event timestamp, source file or IP, and the computed scores where applicable. The viewer parses both the main telemetry database and the detection log file so all events appear in a unified stream.

#### 7.3 Splash Screen — `SplashActivity`

The `SplashActivity` serves as the entry point of the application and runs security checks via `SecurityUtils` before transferring control to `MainActivity`. During the pause, it initializes background resources and handles the Android 12+ splash screen API to ensure a smooth launch experience. If the device is detected to be rooted or running a debugger, a warning is shown to the user before they proceed. This prevents accidental use of SHIELD in an environment where its own integrity cannot be guaranteed.

#### 7.4 Settings — `SettingsActivity`

The `SettingsActivity` exposes configurable options for the user including notification preferences, the list of monitored directories, and toggle switches for individual collectors such as the accessibility monitor. Each setting is persisted in `SharedPreferences` and read by the relevant service on start. This allows power users to fine-tune SHIELD's behavior — for example, disabling the accessibility monitor on devices where it causes battery concerns — without modifying the source code.

---

### 8. Data Storage

#### 8.1 SQLite Telemetry Database — `TelemetryStorage`

The `TelemetryStorage` class manages a multi-table SQLite database (`shield_events.db`) that stores all telemetry events from every collector. It contains five tables: `file_system_events`, `network_events`, `honeyfile_events`, `detection_results`, and `correlation_results`. All write operations are synchronized and use `insertWithOnConflict` with `REPLACE` semantics to prevent duplicate rows. Timestamp-based indexes on each table enable fast time-range queries when the log viewer needs to load recent events.

#### 8.2 SQLite Snapshot Database — `SnapshotDatabase`

The `SnapshotDatabase` is a separate SQLite database (`shield_snapshots.db`) dedicated to storing file metadata and attack window records for the backup-and-restore subsystem. It maintains the `file_metadata` table with per-file SHA-256 hashes, backup paths, attack attribution, and (as of v2) encrypted key blobs and chain hashes. The `attack_windows` table records the start and end time of each detected attack, allowing the restore engine to precisely scope which files need recovering. All access is synchronized to ensure thread safety when the main detection thread and the background snapshot thread operate concurrently.

---

---

## Newly Added Features

> The following seven features were added as a security hardening layer on top of the existing snapshot subsystem. They elevate the backup system from a simple file copy mechanism into a tamper-evident, cryptographically protected, hardware-backed ledger.

---

### 1. Snapshot Hash Chain — Tamper-Evident Ledger

**Files:** `SnapshotIntegrityChecker.java`, `SnapshotManager.java`, `SnapshotDatabase.java`, `FileMetadata.java`

Every time a file is backed up, SHIELD now computes a chain hash using the formula `chain_hash = SHA-256(previous_chain_hash | metadata_hash)`, where `metadata_hash` is derived from the file's path, size, last-modified time, SHA-256 content hash, and snapshot ID. This links every snapshot entry to the one before it in an unbreakable chain, starting from a `GENESIS` sentinel value. An attacker who deletes a snapshot record, reorders entries, or injects a fake metadata row will break the chain — because the calculated chain hash at the tampered position will no longer match the stored value. On the next startup, the integrity checker recomputes the entire chain and immediately broadcasts a tamper alert if any link is broken.

---

### 2. AES-256-GCM Backup File Encryption

**Files:** `BackupEncryptionManager.java`, `SnapshotManager.java`, `RestoreEngine.java`

Backup files stored in the `secure_backups/` directory are now encrypted using AES-256 in GCM (Galois/Counter Mode) before being written to disk. The file encryption format is `[12-byte IV][ciphertext + 16-byte GCM authentication tag]`, which is the industry standard format for authenticated encryption. Even if an attacker with root access gains read access to the private storage directory, they cannot read the backup content or re-encrypt it with different ransomware keys because the decryption key is held exclusively by the Android Keystore — not in any file or database column. On restore, the GCM authentication tag is verified automatically; any byte-level tampering of the backup file causes `AEADBadTagException` and a failed restore rather than a silent data compromise.

---

### 3. Encrypted Snapshot Database Columns

**Files:** `BackupEncryptionManager.java`, `SnapshotManager.java`, `RestoreEngine.java`, `SnapshotDatabase.java`

The `backup_path` column in the snapshot database — which stores the filesystem path to each backup file — is now encrypted using AES-256-GCM before being inserted into the database. A dedicated Keystore key (`shield_db_column_v1`) is used exclusively for this purpose, separate from the key used to wrap per-file encryption keys. The encrypted value is stored as a Base64-encoded string containing `[12-byte IV | ciphertext]`. Without access to the Keystore (which requires passing Android's authentication), an attacker who extracts the database file cannot determine where backup files are stored and therefore cannot target them for deletion or re-encryption.

---

### 4. Snapshot Integrity Self-Check on Startup

**Files:** `SnapshotIntegrityChecker.java`, `SnapshotManager.java`

Every time `SnapshotManager` is constructed — which happens when the protection service starts, including on boot and crash-restart — it enqueues an asynchronous integrity audit via `SnapshotIntegrityChecker.check()`. The audit re-traverses every backed-up entry in the database in insertion order, decrypts each `backup_path` column value, verifies the physical backup file exists on disk, and re-derives the chain hash from scratch to confirm it matches the stored value. If any check fails, the checker broadcasts the `com.dearmoon.shield.SNAPSHOT_TAMPER_ALERT` intent with details of what was violated. The main activity and protection service can subscribe to this broadcast to raise the detection score, disable restore functionality, and alert the user — turning the snapshot storage into a self-auditing system.

---

### 5. Snapshot Directory Monitoring

**Files:** `SnapshotDirectoryObserver.java`, `SnapshotManager.java`

A dedicated `FileObserver` instance (`SnapshotDirectoryObserver`) now watches the `secure_backups/` directory for `DELETE`, `MOVED_FROM`, and `ATTRIB` events. These event types cover the three main ways ransomware could target recovery storage: directly deleting backup files, renaming or moving them away, or altering their permissions and timestamps to corrupt them without triggering an obvious delete event. When any of these events is detected on any file inside the backup directory, the observer logs a critical-level message and broadcasts `com.dearmoon.shield.BACKUP_DIR_TAMPER` with the event type, event code, and full path of the affected file. The observer is started in `SnapshotManager`'s constructor and stopped cleanly in `shutdown()` to avoid resource leaks.

---

### 6. Per-File Key Rotation via Android Keystore

**Files:** `BackupEncryptionManager.java`, `SnapshotManager.java`, `RestoreEngine.java`, `FileMetadata.java`, `SnapshotDatabase.java`

Each backup file is now encrypted with its own freshly generated AES-256 key rather than a single shared key. This key is generated in memory using `KeyGenerator`, then immediately wrapped (encrypted) by a master AES-256 key stored in the Android Keystore under the alias `shield_backup_master_v1`. The wrapped key bytes are stored as a `BLOB` in the `encrypted_key` column of the snapshot database, so the file can be decrypted later by unwrapping the stored blob with the Keystore. If one per-file key is somehow exposed through a side-channel or memory dump, all other backup files remain fully protected because each holds an independently wrapped key. The master key is hardware-backed on supported devices, meaning it can never be exported from the Keystore.

---

### 7. Snapshot Expiry and Retention Policy

**Files:** `SnapshotManager.java`, `SnapshotDatabase.java`

After every new backup operation, `SnapshotManager` enforces a two-part retention policy to prevent unbounded growth of the `secure_backups/` directory. First, if more than **100 backed-up entries** exist in the database, the oldest entries (by database row ID, which corresponds to insertion order) are pruned until the count is at or below the limit. Second, if the total size of all files in `secure_backups/` exceeds **200 MB**, the oldest backed-up entry is deleted one at a time — decrypting the stored path, deleting the encrypted backup file from disk, and removing the database row — until the directory size is within the cap. This policy is enforced synchronously on the snapshot executor thread to avoid any race conditions with the integrity checker.

---

## TEE-Anchored Integrity System

This module (`com.dearmoon.shield.security.integrity`) adds a hardware-rooted self-protection layer that verifies the SHIELD APK has not been tampered with before the protection service does any work. It uses the Android Keystore's Trusted Execution Environment (TEE) — or a StrongBox secure element when available — to hold a secret HMAC key that never leaves secure hardware. Every service start recomputes the APK's SHA-256 digest, re-signs it with the hardware key, and compares the result to the stored baseline. If the comparison fails, the service halts and alerts the user before any detection or recovery logic runs.

---

### 1. IntegrityResult — Verification Outcome Enum

**File:** `IntegrityResult.java`

`IntegrityResult` is a six-value enum that maps every possible outcome of an integrity verification pass to a named, typed constant. `CLEAN` means all checks passed. `APK_TAMPERED` means the recomputed APK hash does not match the HMAC-authenticated baseline — the APK bytes have changed. `BASELINE_FORGED` means the HMAC is inconsistent with the stored raw hash, indicating the SharedPreferences record itself was altered by an external actor. `TEE_KEY_MISSING` means the expected Keystore alias was not found — either first run or deliberate deletion. `KEY_INVALIDATED` maps specifically to `KeyPermanentlyInvalidatedException`, which Android throws when a screen-lock or biometric change invalidates all existing Keystore keys. `STRONGBOX_UNAVAILABLE` is a non-fatal signal that the device lacks a physical secure element and a TEE-backed software key was used instead. Representing all outcomes as an enum allows the calling code to use a `switch` statement with no implicit fall-through risk and forces every new result variant to be handled explicitly at compile time.

---

### 2. ShieldIntegrityManager — APK Hash + HMAC Baseline

**File:** `ShieldIntegrityManager.java`

`ShieldIntegrityManager` is a non-instantiable utility class (private constructor, all-static API) that owns the full lifecycle of the integrity key and baseline. On `initialize()`: it calls `KeyGenerator` with `HmacSHA256` and provider `AndroidKeyStore`, first attempting `setIsStrongBoxBacked(true)` (API 28+) and silently falling back to a plain TEE-backed key if the device returns an exception. It then streams the APK file at `context.getPackageCodePath()` in 8 KB chunks through a `MessageDigest("SHA-256")`, Base64-encodes the digest, uses `Mac.getInstance("HmacSHA256")` initialised with the Keystore key to sign that Base64 string, and writes both values to a private `SharedPreferences` file (`shield_integrity`). On `verify()`: it repeats the same two computations and performs a four-branch comparison — `CLEAN` when both match, `APK_TAMPERED` when neither matches, `BASELINE_FORGED` when only the HMAC or only the raw hash is off — with `KeyPermanentlyInvalidatedException` caught in its own block before the general `Exception` handler so Device Admin key wipes never false-positive as tampering. `regenerate()` deletes the Keystore alias and clears SharedPreferences before calling `initialize()` again, providing a clean reset path for `KEY_INVALIDATED` and `TEE_KEY_MISSING` cases.

---

### 3. IntegrityLogger — Persistent Event Audit Trail

**File:** `IntegrityLogger.java`

`IntegrityLogger` writes every integrity check result to the `integrity_events` table inside the existing `shield_events.db` SQLite database, accessed via the `EventDatabase` singleton. Each row stores: Unix timestamp (`System.currentTimeMillis()`), the `IntegrityResult` name, a snapshot of the current APK hash from SharedPreferences, a boolean flag for whether StrongBox is present, and an optional free-text `additional_info` field used to capture exception messages or human-readable context. Co-locating integrity events in the same database as all other telemetry means the existing log viewer UI, cleanup routines, and export paths all work for integrity events without any additional wiring.

---

### 4. EventDatabase v3 Migration

**File:** `EventDatabase.java`

The `EventDatabase` schema version was bumped from 2 to 3. The `integrity_events` table and its timestamp index are created in `onCreate()` using `CREATE TABLE IF NOT EXISTS` so the statement is idempotent. In `onUpgrade()`, a guard `if (oldVersion < 3)` runs the same DDL and returns immediately when `oldVersion == 2`, leaving all six existing telemetry tables and their data untouched. Only when upgrading from a version older than 2 does the code fall through to the full drop-and-recreate path. This means existing users who had version 2 of the database simply gain the new table on their next app launch — no data loss.

---

### 5. ShieldProtectionService — Integrity Gate

**File:** `ShieldProtectionService.java`

`onStartCommand()` now begins with a two-step integrity gate before any other service logic runs. If `ShieldIntegrityManager.isFirstRun()` returns true (SharedPreferences has never been written), `initialize()` is called to establish the baseline on first launch. Then `ShieldIntegrityManager.verify()` is called and the six-case `switch` statement dispatches on the result: `APK_TAMPERED` and `BASELINE_FORGED` call `IntegrityLogger.log()`, then `postIntegrityAlert()` (which fires a high-priority notification on the existing `shield_protection_channel`), then `stopSelf()` — returning `START_NOT_STICKY` so Android does not restart the service automatically after a tampering halt. `KEY_INVALIDATED` and `TEE_KEY_MISSING` call `IntegrityLogger.log()` and then `ShieldIntegrityManager.regenerate()` before falling through to normal start. `STRONGBOX_UNAVAILABLE` is logged and treated as non-fatal. `CLEAN` proceeds silently. The integrity result name is appended to the startup log line so it is always visible in logcat.

---

## Summary Table

| # | Feature | Category | Status |
|---|---------|----------|--------|
| 1 | Event-Driven File System Monitoring | Data Collection | Existing |
| 2 | Honeyfile Planting and Monitoring | Data Collection | Existing |
| 3 | VPN-Based Network Packet Collection | Data Collection | Existing |
| 4 | Accessibility Service Event Collection | Data Collection | Existing |
| 5 | Shannon Entropy Analysis | Detection | Existing |
| 6 | KL-Divergence Analysis | Detection | Existing |
| 7 | SPRT Statistical Rate Detection | Detection | Existing |
| 8 | Unified Detection Engine (Composite Score) | Detection | Existing |
| 9 | Three-Tier Network Blocking | Mitigation | Existing |
| 10 | Automatic Emergency Network Isolation | Mitigation | Existing |
| 11 | File Baseline Snapshot | Recovery | Existing |
| 12 | Attack-Scoped File Tracking | Recovery | Existing |
| 13 | Selective File Restoration | Recovery | Existing |
| 14 | Recovery UI | Recovery | Existing |
| 15 | RASP Runtime Anti-Tampering | Security | Existing |
| 16 | Auto-Restart on Boot | Resilience | Existing |
| 17 | Auto-Restart on Crash | Resilience | Existing |
| 18 | Screen-Locker Threat Detection | LockerGuard | Existing |
| 19 | Locker Risk Scoring | LockerGuard | Existing |
| 20 | Emergency Recovery UI | LockerGuard | Existing |
| 21 | Main Dashboard | UI | Existing |
| 22 | Real-Time Log Viewer | UI | Existing |
| 23 | SQLite Telemetry Storage | Storage | Existing |
| 24 | SQLite Snapshot Database | Storage | Existing |
| 25 | Snapshot Hash Chain | Snapshot Security | **New** |
| 26 | AES-256-GCM Backup Encryption | Snapshot Security | **New** |
| 27 | Encrypted DB Columns | Snapshot Security | **New** |
| 28 | Integrity Self-Check on Startup | Snapshot Security | **New** |
| 29 | Snapshot Directory Monitoring | Snapshot Security | **New** |
| 30 | Per-File Key Rotation (Keystore) | Snapshot Security | **New** |
| 31 | Snapshot Expiry and Retention Policy | Snapshot Security | **New** |
| 32 | IntegrityResult Outcome Enum | TEE Integrity | **New** |
| 33 | ShieldIntegrityManager (HMAC-SHA256 / Keystore) | TEE Integrity | **New** |
| 34 | IntegrityLogger (integrity_events table) | TEE Integrity | **New** |
| 35 | EventDatabase v3 Non-Destructive Migration | TEE Integrity | **New** |
| 36 | ShieldProtectionService Integrity Gate | TEE Integrity | **New** |
