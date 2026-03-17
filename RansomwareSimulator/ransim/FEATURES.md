# SHIELD RanSim — Features & Technical Specification

The SHIELD RanSim (located in `ransim/` sub-module) is the primary ransomware behavioral simulator for SHIELD research. It provides high-fidelity simulations of various ransomware attack chains in a safe, sandboxed environment.

## 1. Attack Scenarios
RanSim supports four distinct behavioral scenarios to test different layers of the SHIELD detection engine.

### 1.1 Crypto Ransomware
Mimics standard file-encrypting ransomware (e.g., Cerber, SOVA v5).
- **Targeting**: Automatically seeds and targets a high-fidelity sandbox containing diverse file types (DOCX, JPG, PNG, TXT).
- **Transformation**: Uses a 1-byte XOR cipher (key `0x5A`) to simulate file encryption while remaining 100% reversible.
- **C2 Simulation**: Attempts a socket connection to `127.0.0.1:4444` to mimic command-and-control communication after encryption is initiated.

### 1.2 Locker Ransomware
Mimics screen-locking ransomware (e.g., Koler, Svpeng).
- **Overlay Engine**: Deploys a persistent, full-screen `FLAG_APPLICATION_OVERLAY` that visually blocks the user interface.
- **Persistence**: Employs a `WakeLock` to keep the screen bright and a `BootReceiver` to ensure the locker persists across device restarts (for research only).
- **Safety Gating**: Provides a visible test password ("1234") and a dedicated "STOP TEST" button in both the UI and notification shade.

### 1.3 Hybrid Attack (Advanced)
Combines all attack layers simultaneously to mimic high-threat actors like SOVA.
- **Concurrent Execution**: Runs file encryption, screen locking, and multi-port C2 simulation (`4444`, `6666`, `8888`) in parallel.
- **Aggression Test**: Designed to trigger all SHIELD sensors (File IO, Network, RASP, and Behavior Correlation) at once.

### 1.4 Reconnaissance → Encryption
Simulates stealthy ransomware that performs a discovery phase before the final payload.
- **Discovery Phase**: Perfroms 30 seconds of slow file enumerations and metadata reads without modifications.
- **Transition**: Automatically triggers rapid encryption after the recon phase is complete.
- **SPRT Testing**: Specifically designed to test SHIELD's **Sequential Probability Ratio Test (SPRT)** as it accumulates evidence of malicious intent.

## 2. Environment Management
- **High-Fidelity Sandboxing**: Operates exclusively in `/sdcard/Android/data/com.dearmoon.shield.ransim/shield_ransim_sandbox/`.
- **Seeding Engine**: Generates realistic dummy data (Business Reports, Invoices, Contracts, Photos) to ensure detection heuristics see "valuable" targets.
- **Atomic Cleanup**: The "STOP ALL & RESTORE" feature instantly reverts the environment using in-memory byte buffers of the original files.
- **Environment Reset**: A one-tap "RESET" function that wipes the sandbox and re-seeds it with clean files.

## 3. Integration & Telemetry
- **Bidirectional Broadcasts**:
    - **Egress**: Sends specific intents (e.g., `LOCKER_ACTIVE`) to notify SHIELD of the current simulation phase.
    - **Ingress**: Listens for SHIELD's `HIGH_RISK_ALERT` to automatically stop the simulation and report detection success.
- **Real-time Log Panel**: Features an in-app terminal view that displays exactly which files are being enumerating, encrypted, or restored.
- **Standardized Tagging**: Uses the `SHIELD_RANSIM` logcat tag for all telemetry, allowing easy external monitoring via ADB.

## 4. Permissions & Compliance
- **Gated Initialization**: Requires a comprehensive permissions checklist (Manage Storage, System Overlay, Notifications) before any test can begin.
- **Safety First**: Operates with hard-coded security boundaries that prevent the simulator from interacting with any files outside its assigned sandbox.
