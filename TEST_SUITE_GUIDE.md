# SHIELD Ransomware Test Suite Guide

## Overview
Safe ransomware simulator that mimics malicious behavior WITHOUT actually encrypting or destroying files. Tests all detection features in isolated environment.

---

## Prerequisites

### 1. Start Protection Services
```
1. Open SHIELD app
2. Tap "Request Permissions" → Grant all permissions
3. Tap "Mode B" → Start protection service
4. Tap "Network Guard" → Accept VPN permission
5. (Optional) Tap "Blocking: OFF" → Enable network blocking
```

### 2. Access Test Suite
```
1. Tap "🧪 Test Suite" button on main screen
2. Test Activity opens with 7 test buttons
```

---

## Test Descriptions

### Test 1: Rapid File Modification (SPRT Detector)
**What it tests:** Sequential Probability Ratio Test for abnormal file modification rate

**Behavior:**
- Creates 20 files in 2 seconds (10 files/sec)
- Writes 5KB random data per file
- Triggers SPRT H₁ hypothesis (ransomware activity)

**Expected Detection:**
- SPRT State: `ACCEPT_H1`
- Confidence Score: +30 points from SPRT
- Detection Log: Multiple entries with increasing confidence

**How to verify:**
```
1. Tap "Test 1: SPRT"
2. Wait 3 seconds
3. Tap "View Logs" → Check detection_results.json
4. Look for: "sprtState": "ACCEPT_H1"
```

---

### Test 2: High Entropy Files (Entropy Analyzer)
**What it tests:** Shannon entropy calculation for encrypted data detection

**Behavior:**
- Creates 5 files with highly random data
- Each file: 8KB of random bytes (entropy ~8.0)
- Simulates encrypted ransomware payload

**Expected Detection:**
- Entropy: >7.5 (typically 7.9-8.0)
- Confidence Score: +40 points from entropy
- Detection Log: "High entropy detected"

**How to verify:**
```
1. Tap "Test 2: Entropy"
2. Wait 3 seconds
3. Check logs for: "entropy": 7.9+
4. Confidence should be ≥40
```

---

### Test 3: Uniform Byte Distribution (KL-Divergence)
**What it tests:** Kullback-Leibler divergence for uniformity detection

**Behavior:**
- Creates 5 files with uniform byte distribution
- All byte values (0-255) equally likely
- Low KL-divergence indicates encryption

**Expected Detection:**
- KL-Divergence: <0.1 (typically 0.05-0.08)
- Confidence Score: +30 points from KL
- Detection Log: "Uniform distribution detected"

**How to verify:**
```
1. Tap "Test 3: KL-Div"
2. Wait 3 seconds
3. Check logs for: "klDivergence": <0.1
4. Confidence should be ≥30
```

---

### Test 4: Honeyfile Access (Honeyfile Collector)
**What it tests:** Unauthorized access to decoy files

**Behavior:**
- Searches for honeyfiles in Documents/Downloads/Pictures
- Attempts to read files with "IMPORTANT", "BACKUP", "PRIVATE" in name
- Triggers honeyfile access event

**Expected Detection:**
- Honeyfile Event: Logged in telemetry
- Event Type: `HONEYFILE_ACCESS`
- Severity: `CRITICAL`

**How to verify:**
```
1. Ensure ShieldProtectionService is running (creates honeyfiles)
2. Tap "Test 4: Honeyfile"
3. Wait 2 seconds
4. Check modeb_telemetry.json for: "eventType": "HONEYFILE_ACCESS"
```

**Note:** If no honeyfiles found, test will log warning. Start protection service first.

---

### Test 5: Suspicious Network Activity (Network Guard)
**What it tests:** VPN-based network monitoring and blocking

**Behavior:**
- Attempts connections to malicious ports: 4444, 5555, 6666, 7777
- Attempts connections to Tor exit nodes: 185.220.101.1, 45.61.185.1
- All connections should be blocked if VPN + Blocking enabled

**Expected Detection:**
- Network Events: Logged in telemetry
- Blocked Connections: If blocking enabled
- Event Type: `NETWORK`

**How to verify:**
```
1. Ensure Network Guard VPN is running
2. Tap "Test 5: Network"
3. Wait 5 seconds
4. Check modeb_telemetry.json for network events
5. If blocking enabled: All connections should fail
```

---

### Test 6: Full Ransomware Simulation ⚠️
**What it tests:** Complete attack sequence (ALL detectors)

**Behavior:**
- Phase 1: C2 communication (port 4444)
- Phase 2: Honeyfile reconnaissance
- Phase 3: Rapid encryption (15 files, 6-7 files/sec)
- Combines: High entropy + Uniform distribution + Rapid modification

**Expected Detection:**
- Confidence Score: ≥70 (HIGH RISK)
- SPRT: `ACCEPT_H1`
- Entropy: >7.5
- KL-Divergence: <0.1
- Emergency Network Blocking: AUTO-TRIGGERED

**How to verify:**
```
1. Tap "Test 6: FULL RANSOMWARE SIMULATION"
2. Wait 10 seconds
3. Check detection_results.json for HIGH RISK entries
4. Network blocking should auto-enable (emergency mode)
5. All network traffic blocked until manually disabled
```

**⚠️ WARNING:** This test triggers emergency mode. You'll need to manually disable blocking after test.

---

### Test 7: Benign Activity (False Positive Check)
**What it tests:** Normal app behavior that should NOT trigger detection

**Behavior:**
- Creates 5 files slowly (0.33 files/sec)
- Writes structured text (low entropy ~4.5)
- Simulates normal document creation

**Expected Detection:**
- Confidence Score: <70 (LOW RISK)
- SPRT: `ACCEPT_H0` or `CONTINUE`
- Entropy: <6.0
- No emergency blocking

**How to verify:**
```
1. Tap "Test 7: Benign"
2. Wait 20 seconds (slow test)
3. Check detection_results.json
4. Confidence should be <70
5. No HIGH RISK alerts
```

---

## Test Controls

### Stop Test
```
- Tap "Stop Test" to interrupt running test
- Useful if test hangs or takes too long
```

### Cleanup
```
- Tap "Cleanup" to delete all test files
- Removes: /data/data/com.dearmoon.shield/files/test_files/
- Run after testing to free storage
```

### View Logs
```
- Tap "View Logs" to open LogViewerActivity
- Real-time display of all events
- Filter by: FILE_SYSTEM, HONEYFILE_ACCESS, NETWORK, DETECTION
```

---

## Expected Results Summary

| Test | Entropy | KL-Div | SPRT | Confidence | Risk Level |
|------|---------|--------|------|------------|------------|
| Test 1 | N/A | N/A | ACCEPT_H1 | 30+ | Medium |
| Test 2 | >7.5 | N/A | N/A | 40+ | Medium |
| Test 3 | N/A | <0.1 | N/A | 30+ | Medium |
| Test 4 | N/A | N/A | N/A | N/A | Critical Event |
| Test 5 | N/A | N/A | N/A | N/A | Network Event |
| Test 6 | >7.5 | <0.1 | ACCEPT_H1 | **70+** | **HIGH RISK** |
| Test 7 | <6.0 | >0.2 | ACCEPT_H0 | <70 | Low |

---

## Troubleshooting

### Test 1-3 Not Detecting
**Problem:** No detection logs generated  
**Solution:**
1. Ensure ShieldProtectionService is running
2. Check FileSystemCollector is monitoring app files directory
3. Verify UnifiedDetectionEngine is processing events

### Test 4 Shows "No Honeyfiles Found"
**Problem:** Honeyfiles not created  
**Solution:**
1. Stop and restart ShieldProtectionService
2. Check HoneyfileCollector created files in Documents/Downloads/Pictures
3. Grant MANAGE_EXTERNAL_STORAGE permission

### Test 5 Not Blocking Connections
**Problem:** Network connections succeed  
**Solution:**
1. Ensure Network Guard VPN is running (check notification)
2. Enable "Blocking: ON" in main screen
3. Verify VPN permission granted

### Test 6 Doesn't Trigger Emergency Mode
**Problem:** Confidence <70, no auto-blocking  
**Solution:**
1. Check detection_results.json for actual confidence score
2. Verify all 3 detectors (entropy, KL, SPRT) are working
3. May need to run test multiple times to accumulate SPRT evidence

### Test 7 Triggers False Positive
**Problem:** Benign activity flagged as HIGH RISK  
**Solution:**
1. This indicates detection thresholds too aggressive
2. Tune thresholds in UnifiedDetectionEngine:
   - Increase entropy threshold (7.5 → 7.8)
   - Decrease KL threshold (0.1 → 0.05)
   - Increase SPRT modification rate (5.0 → 10.0)

---

## Log File Locations

### Telemetry Log
```
Path: /data/data/com.dearmoon.shield/files/modeb_telemetry.json
Format: Newline-delimited JSON
Contains: File system, network, honeyfile events
```

### Detection Log
```
Path: /data/data/com.dearmoon.shield/files/detection_results.json
Format: Newline-delimited JSON
Contains: Detection results with confidence scores
```

### ADB Commands
```bash
# Pull telemetry log
adb pull /data/data/com.dearmoon.shield/files/modeb_telemetry.json

# Pull detection log
adb pull /data/data/com.dearmoon.shield/files/detection_results.json

# View logs in real-time
adb logcat | grep "RansomwareSimulator\|UnifiedDetectionEngine"

# Clear logs
adb shell run-as com.dearmoon.shield rm /data/data/com.dearmoon.shield/files/*.json
```

---

## Safety Notes

✅ **SAFE:**
- All tests operate in isolated app directory
- No system files modified
- No actual encryption performed
- Test files automatically cleaned up

⚠️ **CAUTION:**
- Test 6 triggers emergency network blocking (blocks ALL traffic)
- Manually disable blocking after Test 6
- Tests generate lots of log entries (may fill storage)
- Run cleanup after testing

❌ **DO NOT:**
- Run tests on production device with important data
- Run Test 6 while using network-dependent apps
- Run multiple tests simultaneously
- Ignore emergency blocking warnings

---

## Real Ransomware Testing (Advanced)

**For actual ransomware validation, use:**

1. **VirusTotal Samples** (Requires security research access)
   - Download known ransomware samples
   - Run in isolated VM/sandbox
   - Monitor SHIELD detection

2. **Ransomware Simulation Tools**
   - RanSim (KnowBe4)
   - Infection Monkey
   - Atomic Red Team

3. **Custom Malware Lab**
   - Isolated network
   - Disposable test devices
   - Backup all data before testing

**⚠️ NEVER test real ransomware on production devices or networks.**

---

## Accuracy Validation

To measure detection accuracy:

1. **Run 100 test iterations:**
   ```
   - Test 6 (ransomware): 50 times
   - Test 7 (benign): 50 times
   ```

2. **Calculate metrics:**
   ```
   True Positives (TP): Test 6 detections with confidence ≥70
   False Negatives (FN): Test 6 detections with confidence <70
   True Negatives (TN): Test 7 detections with confidence <70
   False Positives (FP): Test 7 detections with confidence ≥70
   
   Accuracy = (TP + TN) / 100
   Precision = TP / (TP + FP)
   Recall = TP / (TP + FN)
   ```

3. **Target metrics:**
   ```
   Accuracy: >90%
   Precision: >95% (minimize false alarms)
   Recall: >85% (catch most ransomware)
   ```

---

## Support

For issues or questions:
1. Check LogViewerActivity for detailed event logs
2. Run `adb logcat` for debug output
3. Review detection_results.json for confidence scores
4. Verify all services running in Android Settings → Apps → SHIELD
