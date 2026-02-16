# SHIELD - Ransomware Detection System

## Project Overview
SHIELD is an Android ransomware detection application that implements "Mode B" functionality - a comprehensive behavioral analysis system for detecting ransomware activity on Android devices.

## Mode B Architecture

### Core Components

#### 1. Data Layer (`com.dearmoon.shield.data`)
- **TelemetryEvent** - Abstract base class for all telemetry events
- **FileSystemEvent** - Captures file system operations (create, modify, delete)
- **NetworkEvent** - Captures network metadata (destination IP, port, protocol, bytes)
- **HoneyfileEvent** - Logs unauthorized access to honeyfiles
- **AccessibilityEventData** - Captures accessibility service events
- **TelemetryStorage** - Stores all events in plain JSON format

#### 2. Collectors (`com.dearmoon.shield.collectors`)
- **FileSystemCollector** - Monitors file system changes using FileObserver
  - Watches for CREATE, MODIFY, CLOSE_WRITE, MOVED_TO, DELETE events
  - Forwards events to UnifiedDetectionEngine for analysis
  
- **HoneyfileCollector** - Creates and monitors honeyfiles
  - Places decoy files in monitored directories
  - Detects unauthorized access attempts
  - Logs all honeyfile interactions

#### 3. Detection Engine (`com.dearmoon.shield.detection`)
- **UnifiedDetectionEngine** - Main detection orchestrator
  - Processes file events in background thread
  - Coordinates all detection algorithms
  - Generates composite confidence scores
  - Logs detection results

- **EntropyAnalyzer** - Shannon entropy calculation
  - Analyzes file randomness
  - High entropy (>7.5) indicates encryption
  - Low entropy (<5.0) indicates plain text

- **KLDivergenceCalculator** - Kullback-Leibler divergence
  - Measures uniformity of byte distribution
  - Low divergence (<0.1) indicates encrypted data
  - High divergence indicates structured data

- **SPRTDetector** - Sequential Probability Ratio Test
  - Statistical hypothesis testing
  - H₀: Normal file modification rate (0.1 files/sec)
  - H₁: Ransomware activity (5.0 files/sec)
  - α = β = 0.05 (5% error rates)

- **DetectionResult** - Encapsulates detection outcomes
  - Combines entropy, KL-divergence, and SPRT state
  - Confidence score (0-100)
  - High risk threshold: ≥70

#### 4. Services (`com.dearmoon.shield.services`)
- **ShieldProtectionService** - Main orchestrator service
  - Foreground service for continuous monitoring
  - Initializes all collectors and detection engine
  - Monitors multiple directories (Documents, Downloads, Pictures, DCIM)
  - Creates honeyfiles in monitored locations

- **NetworkGuardService** - VPN-based network monitor & blocker
  - Captures network packets via VPN interface
  - Extracts metadata (IP, port, protocol, size)
  - Logs network events to telemetry storage
  - **Blocks suspicious traffic** (malicious IPs, ports, Tor nodes)
  - **Emergency mode**: Blocks ALL traffic when ransomware detected
  - User-controlled blocking toggle (default: OFF)

#### 5. User Interface
- **MainActivity** - Control center
  - Start/Stop protection
  - Request runtime permissions
  - Start VPN service
  - **Toggle network blocking** (ON/OFF)
  - View detection logs
  - Real-time status display

- **LogViewerActivity** - Comprehensive event log viewer
  - Real-time display of all monitoring events
  - Color-coded severity indicators (CRITICAL, HIGH, MEDIUM, LOW)
  - Event filtering (ALL, FILE_SYSTEM, HONEYFILE_ACCESS, NETWORK, DETECTION)
  - Detailed event information with timestamps
  - Parses both telemetry and detection logs
  - User-friendly card-based interface
  - See [LOG_VIEWER_GUIDE.md](LOG_VIEWER_GUIDE.md) for detailed usage

## Detection Algorithm

### Confidence Score Calculation (0-100 points)

**Entropy Contribution (0-40 points):**
- Entropy > 7.8: +40 points
- Entropy > 7.5: +30 points
- Entropy > 7.0: +20 points
- Entropy > 6.0: +10 points

**KL-Divergence Contribution (0-30 points):**
- KL < 0.05: +30 points (very uniform - encrypted)
- KL < 0.1: +20 points
- KL < 0.2: +10 points

**SPRT Contribution (0-30 points):**
- ACCEPT_H1 (ransomware detected): +30 points
- CONTINUE (testing): +10 points
- ACCEPT_H0 (normal): +0 points

**Risk Classification:**
- Score ≥ 70: HIGH RISK (potential ransomware)
- Score < 70: Normal or suspicious activity

## Permissions Required

### Runtime Permissions
- `READ_EXTERNAL_STORAGE` - Read files for analysis
- `WRITE_EXTERNAL_STORAGE` - Monitor file modifications
- `MANAGE_EXTERNAL_STORAGE` - Full file system access (Android 11+)
- `POST_NOTIFICATIONS` - Show foreground service notification (Android 13+)
- `RECEIVE_BOOT_COMPLETED` - Auto-start service after device reboot

### Special Permissions
- `BIND_VPN_SERVICE` - Network monitoring via VPN
- `FOREGROUND_SERVICE` - Continuous background operation
- `FOREGROUND_SERVICE_SPECIAL_USE` - Ransomware detection service

### Network Permissions
- `INTERNET` - Network metadata collection
- `ACCESS_NETWORK_STATE` - Network status monitoring

## Data Storage

### Telemetry Log
- **Location:** `<app_files_dir>/modeb_telemetry.json`
- **Format:** Newline-delimited JSON
- **Contents:** All file system (filtered to modified/deleted), network, and honeyfile events

### Detection Log
- **Location:** `<app_files_dir>/detection_results.json`
- **Format:** Newline-delimited JSON
- **Contents:** Detection results with confidence scores

## Usage Instructions

### 1. Grant Permissions
1. Open SHIELD app
2. Tap "Request Permissions"
3. Grant "All files access" in system settings
4. Grant notification permission (Android 13+)

### 2. Start Protection
1. Tap "Start Protection"
2. Service starts in foreground
3. File system monitoring begins
4. Honeyfiles are created

### 3. Enable Network Monitoring (Optional)
1. Tap "Start Network Monitoring (VPN)"
2. Accept VPN permission dialog
3. Network metadata collection begins
4. **Toggle "Blocking: ON"** to enable traffic blocking (default: OFF)
5. System monitors and optionally blocks suspicious connections

### 4. View Logs
1. Tap "View Detection Logs"
2. Check events in real-time
3. Access logs via `adb pull` for analysis

## Monitored Directories
- External storage root
- Documents folder
- Downloads folder
- Pictures folder
- DCIM (Camera) folder
- App-specific external directories

## Technical Details

### File System Monitoring
- Uses Android FileObserver API
- Monitors CREATE, MODIFY, CLOSE_WRITE, MOVED_TO, DELETE events
- **Logged Events:** Filtered to only SHOW MODIFY, CLOSE_WRITE, and DELETE (User requirement)
- Processes files > 100 bytes
- Samples first 8KB for entropy/KL analysis

### Network Monitoring
- VPN-based packet capture
- IPv4 only (currently)
- Extracts: destination IP, port, protocol, packet size
- **Blocking modes:**
  - **OFF (default)**: Monitor only, no blocking
  - **ON (user-enabled)**: Block suspicious IPs/ports
  - **Emergency (auto-triggered)**: Block ALL traffic when ransomware detected
- **Blocked targets:**
  - Malicious ports: 4444, 5555, 6666, 7777
  - Tor exit nodes: 185.220.101.x, 45.61.185.x, etc.
  - Private networks: 10.x, 192.168.x, 172.16-31.x
  - Localhost/link-local: 127.x, 169.254.x

### Detection Processing
- Background thread processing
- 1-second time window for modification rate
- Asynchronous event handling
- Thread-safe storage
- **Auto-triggers network blocking** when confidence ≥70

## Build Instructions

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run all tests
./gradlew test

# Install on device
./gradlew installDebug
```

## Project Structure
```
app/src/main/java/com/dearmoon/shield/
├── MainActivity.java                    # UI and user controls
├── LogViewerActivity.java               # Event log viewer
├── LogAdapter.java                      # RecyclerView adapter for logs
├── collectors/
│   ├── FileSystemCollector.java        # File system monitoring
│   ├── HoneyfileCollector.java         # Honeyfile management
│   └── MediaStoreCollector.java        # MediaStore monitoring (disabled)
├── data/
│   ├── TelemetryEvent.java             # Base event class
│   ├── FileSystemEvent.java            # File system events
│   ├── NetworkEvent.java               # Network events
│   ├── HoneyfileEvent.java             # Honeyfile access events
│   ├── AccessibilityEventData.java     # Accessibility events
│   └── TelemetryStorage.java           # Event persistence
├── detection/
│   ├── UnifiedDetectionEngine.java     # Main detection logic
│   ├── EntropyAnalyzer.java            # Shannon entropy
│   ├── KLDivergenceCalculator.java     # KL-divergence
│   ├── SPRTDetector.java               # Statistical testing
│   └── DetectionResult.java            # Detection outcomes
├── services/
│   ├── ShieldProtectionService.java    # Main orchestrator
│   └── NetworkGuardService.java        # VPN network monitor & blocker
├── receivers/
│   ├── BootReceiver.java               # Auto-start on boot
│   ├── ServiceRestartReceiver.java     # Auto-restart on crash
│   └── NetworkBlockReceiver.java       # Emergency blocking trigger
├── security/
│   └── SecurityUtils.java              # RASP & anti-tampering
├── snapshot/
│   ├── SnapshotManager.java            # File backup system
│   ├── RestoreEngine.java              # Recovery engine
│   ├── SnapshotDatabase.java           # Snapshot metadata
│   ├── FileMetadata.java               # File tracking
│   └── RecoveryActivity.java           # Recovery UI
└── lockerguard/
    ├── LockerShieldService.java        # Accessibility monitor
    ├── RiskEvaluator.java              # Threat scoring
    ├── LockerShieldEvent.java          # Locker events
    └── EmergencyRecoveryActivity.java  # Emergency UI
```

## Completion Status

### ✅ Completed Components
1. All data models (TelemetryEvent hierarchy)
2. File system collector with FileObserver
3. Honeyfile collector with monitoring
4. Entropy analyzer (Shannon entropy)
5. KL-divergence calculator
6. SPRT detector (statistical testing)
7. Unified detection engine
8. **Network guard VPN service with blocking**
9. Shield protection orchestrator service
10. MainActivity with full UI
11. AndroidManifest with all permissions
12. Layout with status and controls
13. **LogViewerActivity** - Comprehensive event log viewer
14. **LogAdapter** - RecyclerView adapter for log entries
15. Log viewer layouts (activity and item)
16. **RASP & Anti-Tampering** - SecurityUtils with runtime protection
17. **Auto-Restart Mechanism** - Boot receiver & service restart
18. **Emergency Network Blocking** - Auto-triggered on ransomware detection

### 🎯 Ready for Testing
The project is now complete and ready for:
- Device installation
- Runtime permission testing
- File system monitoring verification (Filtered to modified/deleted)
- Detection algorithm validation
- Network monitoring testing

## Notes
- The project successfully builds with `./gradlew assembleDebug`
- All Mode B components have been migrated from the original `modeb` project
- The architecture follows the original design specifications
- **Bug Fixed:** Telemetry storage now uses plain JSON (appending to GZIP was failing)
- **Update:** Log viewer now correctly filters file system events to show only modifications and deletions
- **Update:** MediaStoreCollector disabled to prevent duplicate telemetry entries
- **Security:** RASP checks for debugger, emulator, root, hooks, and signature tampering
- **Reliability:** Auto-restart on boot and service crash
- **Network Protection:** VPN blocks ransomware C2 communication (user-controlled + auto-emergency mode)
#   s h i e l d - d s c i -  
 