# SHIELD Dataset Requirements - Complete Feature List

This document lists **every feature** that SHIELD needs to properly test ransomware detection.

---

## 📋 **PRIMARY: File System Events** (Required for Core Detection)

### **1. File Content (Bytes)**
- **Purpose**: Calculate entropy and KL divergence
- **Required**: 
  - Actual file bytes/content (not just metadata)
  - Minimum file size: 100 bytes
  - Multi-region sampling: Beginning (0-8KB), Middle, End (last 8KB)
- **Used by**: `EntropyAnalyzer`, `KLDivergenceCalculator`
- **Critical**: Without actual file content, entropy/KL cannot be calculated accurately

### **2. File Modification Events with Timestamps**
- **Purpose**: Calculate SPRT (modification rate detection)
- **Required fields**:
  - `filePath` (String) - Full path to modified file
  - `operation` (String) - Must be "MODIFY" (CREATE/DELETE ignored)
  - `timestamp` (long) - Unix timestamp in milliseconds
  - `fileSizeBefore` (long) - File size before modification
  - `fileSizeAfter` (long) - File size after modification
  - `fileExtension` (String) - File extension (e.g., ".txt", ".pdf")
- **Temporal requirements**:
  - Precise timestamps (millisecond accuracy)
  - Sequential events ordered by time
  - Time delta between events (for SPRT calculation)
- **Used by**: `SPRTDetector` (NORMAL_RATE=0.1 files/sec, RANSOMWARE_RATE=5.0 files/sec)
- **Critical**: SPRT needs actual event timestamps, not aggregate counts

### **3. File Metadata**
- **Required**:
  - File existence check (file must exist at analysis time)
  - File readability (must be readable)
  - File size (must be ≥ 100 bytes)

---

## 🔗 **SECONDARY: Behavior Correlation Events** (Required for Behavior Score 0-30)

### **4. Network Events** (Within 5-second correlation window)
- **Purpose**: Detect C2 communication + file encryption pattern
- **Required fields**:
  - `timestamp` (long) - Unix timestamp in milliseconds
  - `destinationIp` (String) - Target IP address
  - `destinationPort` (int) - Target port number
  - `protocol` (String) - Protocol (TCP/UDP/HTTP/HTTPS)
  - `bytesSent` (int) - Bytes transmitted
  - `bytesReceived` (int) - Bytes received
  - `appUid` (int) - Application UID (for correlation with file events)
- **Temporal requirement**: Events within 5 seconds of file modification
- **Used by**: `BehaviorCorrelationEngine` (Pattern 1: file + network = 0-10 points)
- **Critical**: Must be correlated by UID and timestamp window

### **5. Honeyfile Access Events** (Within 5-second correlation window)
- **Purpose**: Detect ransomware probing for important files
- **Required fields**:
  - `timestamp` (long) - Unix timestamp in milliseconds
  - `filePath` (String) - Path to honeyfile accessed
  - `accessType` (String) - Type of access (READ/WRITE/OPEN)
  - `callingUid` (int) - UID of process accessing honeyfile
  - `packageName` (String) - Package name of accessing app
- **Temporal requirement**: Events within 5 seconds of file modification
- **Used by**: `BehaviorCorrelationEngine` (Pattern 2: honeyfile access = 0-15 points)
- **Critical**: Must track which files are honeyfiles

### **6. Locker/UI Threat Events** (Within 5-second correlation window)
- **Purpose**: Detect screen-locking ransomware behavior
- **Required fields**:
  - `timestamp` (long) - Unix timestamp in milliseconds
  - `packageName` (String) - Package name of threat
  - `threatType` (String) - Type of UI threat
  - `riskScore` (int) - Risk score (0-100)
  - `details` (String) - Additional threat details
- **Temporal requirement**: Events within 5 seconds of file modification
- **Used by**: `BehaviorCorrelationEngine` (Pattern 3: locker + file = 0-5 points)
- **Critical**: Must correlate with file modification events

---

## 📊 **AGGREGATE FEATURES** (For Behavior Correlation)

### **7. File Event Counts** (Within 5-second window)
- **Purpose**: Count rapid file modifications
- **Required**: 
  - Count of file modification events in 5-second window
  - Threshold: >5 events = high activity, >3 events = medium activity
- **Used by**: `BehaviorCorrelationEngine.calculateBehaviorScore()`

### **8. Network Event Counts** (Within 5-second window, filtered by UID)
- **Purpose**: Count network connections during file encryption
- **Required**:
  - Count of network events in 5-second window
  - Filtered by same UID as file modification
  - Threshold: >0 events = network activity detected
- **Used by**: `BehaviorCorrelationEngine.calculateBehaviorScore()`

### **9. Honeyfile Event Counts** (Within 5-second window)
- **Purpose**: Count honeyfile accesses
- **Required**:
  - Count of honeyfile access events in 5-second window
  - Scoring: count * 5 points (max 15 points)
- **Used by**: `BehaviorCorrelationEngine.calculateBehaviorScore()`

### **10. Locker Event Counts** (Within 5-second window)
- **Purpose**: Count UI/locker threats
- **Required**:
  - Count of locker events in 5-second window
  - Must have file events >0 to score (Pattern 3)
- **Used by**: `BehaviorCorrelationEngine.calculateBehaviorScore()`

---

## 🏷️ **ATTRIBUTION FEATURES** (For Correlation)

### **11. Application UID (User ID)**
- **Purpose**: Correlate events from same app
- **Required**: 
  - UID for each file modification event
  - UID for network events (for filtering)
  - UID for honeyfile access events
- **Used by**: `BehaviorCorrelationEngine` (to filter network events by UID)

### **12. Package Name**
- **Purpose**: Identify which app triggered events
- **Required**:
  - Package name for UID mapping
  - Used for logging and attribution
- **Used by**: `PackageAttributor`, `CorrelationResult`

---

## ⏱️ **TEMPORAL FEATURES** (Critical for SPRT)

### **13. Event Timestamps**
- **Purpose**: Calculate time deltas for SPRT
- **Required**:
  - Unix timestamp in milliseconds for each file modification
  - Precision: Millisecond accuracy
  - Sequential ordering: Events must be ordered by time
  - Time delta calculation: `(currentTime - lastEventTime) / 1000.0` seconds
  - Cap: Maximum delta of 5.0 seconds (to handle service suspension)
- **Used by**: `SPRTDetector.recordTimePassed()`

### **14. Correlation Window**
- **Purpose**: Group related events for behavior correlation
- **Required**:
  - 5-second sliding window (`CORRELATION_WINDOW_MS = 5000`)
  - All events within windowStart = eventTimestamp - 5000ms
- **Used by**: `BehaviorCorrelationEngine.correlateFileEvent()`

---

## 📈 **CALCULATED FEATURES** (Derived from Above)

### **15. Shannon Entropy** (Calculated from file content)
- **Formula**: H = -Σ p(x) log₂ p(x)
- **Sample size**: 8KB per region (beginning, middle, end)
- **Returns**: Maximum entropy across all regions
- **Thresholds**: 
  - >7.8 → 40 points
  - >7.5 → 30 points
  - >7.0 → 20 points
  - >6.0 → 10 points

### **16. KL Divergence** (Calculated from file content)
- **Formula**: D_KL(P || U) = Σ P(x) log₂(P(x) / U(x))
- **Sample size**: First 8KB only
- **Uniform probability**: 1/256 = 0.00390625
- **Thresholds**:
  - <0.05 → 30 points (very uniform/encrypted)
  - <0.1 → 20 points
  - <0.2 → 10 points

### **17. SPRT State** (Calculated from modification rate)
- **Input**: File modification events with timestamps
- **Parameters**:
  - NORMAL_RATE = 0.1 files/sec
  - RANSOMWARE_RATE = 5.0 files/sec
  - ALPHA = 0.05 (false positive rate)
  - BETA = 0.05 (false negative rate)
- **States**: CONTINUE, ACCEPT_H0 (normal), ACCEPT_H1 (ransomware)
- **Scoring**:
  - ACCEPT_H1 → 30 points
  - CONTINUE → 10 points

### **18. Behavior Score** (Calculated from correlated events)
- **Pattern 1**: File + Network
  - fileCount > 5 AND networkCount > 0 → 10 points
  - fileCount > 3 AND networkCount > 0 → 5 points
- **Pattern 2**: Honeyfile Access
  - honeyfileCount * 5 points (max 15 points)
- **Pattern 3**: Locker + File
  - lockerCount > 0 AND fileCount > 0 → 5 points
- **Total**: Capped at 30 points

### **19. Total Score** (Final detection score)
- **Formula**: `min(fileScore + behaviorScore, 130)`
- **File Score**: 0-100 (entropy + KL + SPRT)
- **Behavior Score**: 0-30 (correlation patterns)
- **High-Risk Threshold**: totalScore >= 70

---

## ✅ **MINIMUM DATASET REQUIREMENTS**

For a dataset to properly test SHIELD, it must include:

### **Essential (Core Detection)**:
1. ✅ **File content/bytes** for each modified file
2. ✅ **File modification events** with precise timestamps
3. ✅ **File paths** for each modification
4. ✅ **File sizes** (before/after modification)

### **Important (Behavior Correlation)**:
5. ✅ **Network events** with timestamps and UID
6. ✅ **Honeyfile access events** (if honeyfiles are used)
7. ✅ **Application UID** for event correlation

### **Optional (Enhanced Detection)**:
8. ⚠️ **Locker/UI events** (for Pattern 3 scoring)
9. ⚠️ **Package names** (for attribution/logging)

---

## ❌ **WHAT CURRENT DATASETS PROVIDE (Mismatch)**

### **CICMalDroid-2020**:
- ❌ No file content (only syscall frequencies)
- ❌ No file modification timestamps (only aggregate counts)
- ❌ No file paths
- ✅ Has syscall frequencies (better proxy than API calls)
- **Result**: 89.2% detection (good proxy, but still synthetic)

### **AndMal2020**:
- ❌ No file content (only API call frequencies)
- ❌ No file modification timestamps (only aggregate counts)
- ❌ No file paths
- ❌ Higher-level API calls (further from file operations)
- **Result**: 5.69% detection (poor proxy)

---

## 🎯 **IDEAL DATASET STRUCTURE**

```csv
file_modification_events:
  timestamp_ms, file_path, operation, file_size_before, file_size_after, file_content_bytes, app_uid, package_name

network_events:
  timestamp_ms, destination_ip, destination_port, protocol, bytes_sent, bytes_received, app_uid

honeyfile_events:
  timestamp_ms, file_path, access_type, calling_uid, package_name

locker_events:
  timestamp_ms, package_name, threat_type, risk_score, details
```

**OR** a dataset with pre-calculated features:
- File entropy (multi-region)
- KL divergence
- File modification rate (events/second)
- Network activity counts (within windows)
- Honeyfile access counts

---

## 📝 **SUMMARY**

**SHIELD needs**:
- **File-level features**: Content, timestamps, paths
- **Event-level features**: Individual events with precise timing
- **Correlation features**: Multiple event types within time windows

**Current datasets provide**:
- **Behavioral features**: API/syscall frequencies, aggregate counts
- **No file content**: Cannot calculate entropy/KL directly
- **No timestamps**: Cannot calculate SPRT accurately

**Gap**: Behavioral features → File content synthesis → SHIELD detection
**Impact**: Detection rates are approximations, not true measurements
