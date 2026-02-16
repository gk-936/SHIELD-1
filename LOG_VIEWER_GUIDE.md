# Log Viewer Feature - Implementation Guide

## Overview
The SHIELD app now includes a comprehensive **Event Log Viewer** that displays all monitoring activities in real-time, including:
- 📁 **File System Changes** (create, modify, delete operations)
- 🍯 **Honeyfile Access** (unauthorized access attempts)
- 🌐 **Network Events** (connection metadata)
- 🔍 **Detection Results** (ransomware detection analysis)

## Features

### 1. Real-Time Event Display
- All events are displayed in chronological order (newest first)
- Color-coded severity indicators:
  - 🔴 **CRITICAL** - High-risk detections (confidence ≥70)
  - 🟠 **HIGH** - Honeyfile access, file deletions
  - 🟡 **MEDIUM** - File modifications, medium-risk detections
  - 🔵 **LOW/INFO** - Normal operations, network events

### 2. Event Filtering
Filter events by type using the dropdown:
- **ALL** - Show all events
- **FILE_SYSTEM** - Only file operations
- **HONEYFILE_ACCESS** - Only honeyfile access attempts
- **NETWORK** - Only network connections
- **DETECTION** - Only detection results

### 3. Detailed Event Information

#### File System Events
Shows:
- Operation type (CREATE, MODIFY, DELETE, etc.)
- Full file path
- File extension
- File size before and after operation

#### Honeyfile Access Events
Shows:
- Access type (OPEN, MODIFY, DELETE, WRITE)
- Honeyfile path
- Calling UID (process ID)
- Package name (if available)

#### Network Events
Shows:
- Protocol (TCP, UDP, etc.)
- Destination IP and port
- Bytes sent and received
- Application UID
- **Blocked status** (if blocking enabled)

#### Detection Results
Shows:
- File being analyzed
- Entropy score (0-8)
- KL-Divergence value
- SPRT state (CONTINUE, ACCEPT_H0, ACCEPT_H1)
- Confidence score (0-100)
- Risk level assessment

## How to Use

### Accessing the Log Viewer
1. Open SHIELD app
2. Tap **"View Detection Logs"** button
3. The Event Logs screen will open

### Reading the Logs

Each log entry displays:
```
┌─────────────────────────────────────┐
│ [Severity] Event Title        [TYPE]│
│ Timestamp: Jan 12, 13:30:45         │
│                                     │
│ Detailed Information:               │
│ - Field 1: Value                    │
│ - Field 2: Value                    │
│ - Field 3: Value                    │
└─────────────────────────────────────┘
```

### Understanding Severity Colors

**Left Border Color Indicates Severity:**
- **Red** (CRITICAL): Immediate attention required
  - Detection confidence ≥70
  - Potential ransomware activity
  
- **Orange** (HIGH): Suspicious activity
  - Honeyfile accessed
  - File deletion operations
  
- **Amber** (MEDIUM): Worth monitoring
  - File modifications
  - Detection confidence 40-69
  
- **Blue** (LOW/INFO): Normal operations
  - File creation
  - Network activity
  - Low-confidence detections

### Example Events

#### 1. File System Event (Normal)
```
File System Event                [FILE_SYSTEM]
Jan 12, 13:25:10

Operation: CREATE
File: /storage/emulated/0/Documents/test.txt
Extension: .txt
Size Before: 0 bytes
Size After: 1024 bytes
```

#### 2. Honeyfile Access (ALERT!)
```
⚠️ HONEYFILE ACCESSED          [HONEYFILE_ACCESS]
Jan 12, 13:26:45

Access Type: OPEN
File: /storage/emulated/0/Documents/.important_document.txt
Calling UID: 10234
Package: com.suspicious.app
```

#### 3. Detection Result (High Risk)
```
🔍 Detection Result              [DETECTION]
Jan 12, 13:27:30

File: /storage/emulated/0/Pictures/photo.jpg

Entropy: 7.85
KL-Divergence: 0.03
SPRT State: ACCEPT_H1
Confidence Score: 85/100

Risk Level: ⚠️ HIGH RISK
```

## Technical Implementation

### Data Sources
The log viewer reads from two files:

1. **Telemetry Log** (`modeb_telemetry.json`)
   - Contains all file system, network, and honeyfile events
   - Format: Newline-delimited JSON

2. **Detection Log** (`detection_results.json`)
   - Contains detection analysis results
   - Format: Newline-delimited JSON

### Log Parsing
- **JSON Reading**: Reads structured event data line by line
- **JSON Parsing**: Extracts structured event data
- **Type Detection**: Identifies event type from JSON fields
- **Sorting**: Orders events by timestamp (newest first)

### Performance Considerations
- Events are loaded asynchronously
- Only visible events are rendered (RecyclerView optimization)
- Large log files are handled efficiently
- Filtering happens in-memory for fast response

## Monitoring Best Practices

### What to Look For

1. **Honeyfile Access**
   - ANY honeyfile access is suspicious
   - Check the package name of the accessing app
   - Investigate unfamiliar UIDs

2. **High Detection Scores**
   - Confidence ≥70 indicates potential ransomware
   - Check the file path and type
   - Look for patterns (multiple high-entropy files)

3. **Unusual File Operations**
   - Mass file deletions
   - Rapid file modifications
   - Changes to system directories

4. **Network Activity Correlation**
   - Network events during suspicious file operations
   - Connections to unknown IPs
   - High data transfer volumes
   - **Blocked connections** (check for C2 attempts)

### Response Actions

If you see suspicious activity:

1. **Enable Network Blocking** ("Blocking: ON" button)
2. **Review the logs** carefully
3. **Note the package name/UID** of suspicious apps
4. **Check blocked connections** in network events
5. **Export logs** for analysis:
   ```bash
   adb pull /data/data/com.dearmoon.shield/files/modeb_telemetry.json
   adb pull /data/data/com.dearmoon.shield/files/detection_results.json
   ```
6. **Uninstall suspicious apps**
7. **Restart protection**

## Troubleshooting

### No Events Showing
- Ensure protection service is running
- Check that permissions are granted
- Verify monitored directories exist
- Try creating a test file in Documents folder

### Events Not Updating
- Logs are loaded when activity opens
- Close and reopen the log viewer to refresh
- Check that the service hasn't crashed

### Missing Event Details
- Some events may have incomplete data
- Network events require VPN service to be running
- Honeyfile events require honeyfiles to be created

## Future Enhancements

Potential improvements:
- [ ] Real-time log updates (live streaming)
- [ ] Export logs to external storage
- [ ] Search/filter by file path or package name
- [ ] Event statistics and charts
- [ ] Push notifications for high-risk events
- [ ] Log rotation and cleanup
- [ ] Event correlation analysis

## File Locations

All logs are stored in the app's private directory:
```
/data/data/com.dearmoon.shield/files/
├── modeb_telemetry.json        # All telemetry events (plain JSON)
└── detection_results.json      # Detection analysis
```

Access via ADB:
```bash
# View detection results
adb shell cat /data/data/com.dearmoon.shield/files/detection_results.json

# Pull telemetry
adb pull /data/data/com.dearmoon.shield/files/modeb_telemetry.json
cat modeb_telemetry.json
```

## Summary

The Log Viewer provides complete visibility into SHIELD's monitoring activities:
- ✅ Real-time event tracking
- ✅ Color-coded severity indicators
- ✅ Detailed event information
- ✅ Flexible filtering options
- ✅ User-friendly interface
- ✅ Comprehensive detection insights

This feature transforms SHIELD from a background monitoring tool into a transparent, interactive security system that keeps you informed of all protection activities!
