# EVENT PIPELINE VERIFICATION GUIDE

## Quick Test Procedure

### Step 1: Build and Install
```bash
cd "c:\Users\gokul D\SHIELD-1"
gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Step 2: Clear Existing Data
1. Open SHIELD app
2. Tap "View Detection Logs"
3. Tap "Clear All Logs" button
4. Confirm logs cleared (should show "Showing 0 of 0 events")
5. Go back to MainActivity

### Step 3: Generate Test Events
1. Tap "Open Test Suite"
2. Tap "Run Multi-Stage Ransomware Attack"
3. Wait for test to complete (~10 seconds)
4. Check for success toast message

### Step 4: Verify Event Display
1. Go back to MainActivity
2. Tap "View Detection Logs"
3. **EXPECTED RESULT:** Events should now be visible
4. Check event count (should show "Showing X of X events" where X > 0)

### Step 5: Test Filters
1. In LogViewerActivity, use the filter spinner at top
2. Select "FILE_SYSTEM" → Should show only file operations
3. Select "HONEYFILE_ACCESS" → Should show honeyfile access events
4. Select "NETWORK" → Should show network events
5. Select "DETECTION" → Should show detection results
6. Select "ALL" → Should show all events combined

### Step 6: Verify Logcat (Optional)
```bash
adb logcat -s TelemetryStorage:D EventDatabase:D LogViewerActivity:D FileSystemCollector:D
```

**Look for these log patterns:**

**Event Generation:**
```
FileSystemCollector: FS Event detected: CLOSE_WRITE on /path/file shouldLog=true
FileSystemCollector: LOGGED: MODIFY - /path/file (1024 bytes)
```

**Event Storage:**
```
TelemetryStorage: EVENT GENERATED: FILE_SYSTEM
TelemetryStorage: EVENT STORED: FILE_SYSTEM (ID: 42)
EventDatabase: Inserted FileSystemEvent: 42
```

**Event Retrieval:**
```
LogViewerActivity: Loading telemetry events from database...
EventDatabase: getAllEvents called with eventType=ALL, limit=1000
EventDatabase: Query returned 15 rows
EventDatabase: getAllEvents returning 15 events for type: ALL
LogViewerActivity: Database returned 15 telemetry events
LogViewerActivity: Successfully parsed 15 telemetry events
```

---

## Expected Results Summary

### ✅ SUCCESS CRITERIA

1. **Event Count > 0**
   - LogViewerActivity shows "Showing X of X events" where X > 0
   - Events visible in RecyclerView with proper formatting

2. **Event Details Correct**
   - FILE_SYSTEM events show: operation, file path, extension, size
   - HONEYFILE_ACCESS events show: access type, file path, UID, package
   - NETWORK events show: protocol, destination IP/port, bytes
   - DETECTION events show: entropy, KL-divergence, SPRT state, confidence score

3. **Filters Working**
   - ALL filter shows all event types
   - Specific filters show only matching events
   - Event count updates correctly when filter changes

4. **No Errors**
   - No crash or ANR
   - No error toasts
   - No exceptions in Logcat

### ❌ FAILURE INDICATORS

1. **Still Showing 0 Events**
   - Check Logcat for exceptions in cursorToJsonList()
   - Verify database has data: `adb shell run-as com.dearmoon.shield sqlite3 databases/shield_events.db "SELECT COUNT(*) FROM file_system_events;"`
   - Check if service is running: `adb shell dumpsys activity services | grep ShieldProtectionService`

2. **Events Missing Details**
   - Check Logcat for "Error parsing event" messages
   - Verify JSON structure matches expected format

3. **Filters Not Working**
   - Check if eventType column matches filter values exactly
   - Verify UNION query includes correct event_type values

---

## Troubleshooting

### Issue: No events generated
**Solution:**
1. Verify ShieldProtectionService is running
2. Check permissions granted (MANAGE_EXTERNAL_STORAGE)
3. Verify monitored directories exist (Documents, Downloads, Pictures, DCIM)
4. Check FileObserver is watching: Look for "Started monitoring: /path" in Logcat

### Issue: Events generated but not stored
**Solution:**
1. Check TelemetryStorage logs for "EVENT STORED" messages
2. Verify database file exists: `adb shell run-as com.dearmoon.shield ls databases/`
3. Check for database insert errors in Logcat

### Issue: Events stored but not retrieved
**Solution:**
1. Check EventDatabase logs for "Query returned X rows"
2. If rows > 0 but events.size() = 0, check cursorToJsonList() for exceptions
3. Verify column names match between schema and query

### Issue: Events retrieved but not displayed
**Solution:**
1. Check LogViewerActivity logs for "Successfully parsed X events"
2. Verify RecyclerView adapter is initialized
3. Check if notifyDataSetChanged() is called
4. Verify layout files are correct (activity_log_viewer.xml, item_log_entry.xml)

---

## Manual Database Inspection

### Connect to device shell
```bash
adb shell
run-as com.dearmoon.shield
cd databases
sqlite3 shield_events.db
```

### Check table counts
```sql
SELECT COUNT(*) FROM file_system_events;
SELECT COUNT(*) FROM honeyfile_events;
SELECT COUNT(*) FROM network_events;
SELECT COUNT(*) FROM detection_results;
```

### View recent events
```sql
SELECT * FROM file_system_events ORDER BY timestamp DESC LIMIT 5;
SELECT * FROM honeyfile_events ORDER BY timestamp DESC LIMIT 5;
SELECT * FROM network_events ORDER BY timestamp DESC LIMIT 5;
SELECT * FROM detection_results ORDER BY timestamp DESC LIMIT 5;
```

### Check event types
```sql
SELECT DISTINCT event_type FROM file_system_events;
SELECT DISTINCT event_type FROM honeyfile_events;
SELECT DISTINCT event_type FROM network_events;
```

### Exit sqlite
```sql
.quit
```

---

## Performance Verification

### Test with Large Dataset
1. Run multiple test cycles (5-10 times)
2. Generate 100+ events
3. Open LogViewerActivity
4. Verify:
   - No lag when scrolling RecyclerView
   - Filter changes are instant
   - No ANR (Application Not Responding)

### Memory Check
```bash
adb shell dumpsys meminfo com.dearmoon.shield
```
- Verify no memory leaks
- Check database size: `adb shell run-as com.dearmoon.shield du -h databases/shield_events.db`

---

## Regression Testing

After verifying the fix, ensure these features still work:

- [ ] File system monitoring detects file changes
- [ ] Detection engine analyzes files (entropy, KL-divergence, SPRT)
- [ ] High-risk alerts trigger at confidence ≥70
- [ ] Network monitoring captures packets (if VPN enabled)
- [ ] Network blocking activates on ransomware detection
- [ ] Honeyfile access triggers CRITICAL alerts
- [ ] Snapshot manager tracks file changes
- [ ] LockerShield detects UI threats (if accessibility enabled)
- [ ] Service auto-restarts on crash
- [ ] Service auto-starts on device boot

---

## Success Confirmation

**When you see this, the fix is working:**

```
LogViewerActivity UI:
┌─────────────────────────────────────┐
│ Event Log                      [←]  │
├─────────────────────────────────────┤
│ Filter: [ALL ▼]                     │
│ Showing 23 of 23 events             │
├─────────────────────────────────────┤
│ 🔴 CRITICAL                         │
│ Detection: test_file.txt            │
│ Dec 15, 24 14:32                    │
│ Confidence: 85/100                  │
├─────────────────────────────────────┤
│ 🟠 HIGH                             │
│ ⚠️ HONEYFILE ACCESSED              │
│ Dec 15, 24 14:32                    │
│ File: PASSWORDS.txt                 │
├─────────────────────────────────────┤
│ 🟡 MEDIUM                           │
│ test_file.txt                       │
│ Dec 15, 24 14:32                    │
│ Operation: MODIFY                   │
├─────────────────────────────────────┤
│ ... (more events)                   │
└─────────────────────────────────────┘
```

**Logcat output:**
```
TelemetryStorage: EVENT GENERATED: FILE_SYSTEM
TelemetryStorage: EVENT STORED: FILE_SYSTEM (ID: 42)
EventDatabase: Inserted FileSystemEvent: 42
LogViewerActivity: Loading telemetry events from database...
EventDatabase: getAllEvents called with eventType=ALL, limit=1000
EventDatabase: Query returned 23 rows
EventDatabase: getAllEvents returning 23 events for type: ALL
LogViewerActivity: Database returned 23 telemetry events
LogViewerActivity: Successfully parsed 23 telemetry events
```

---

**Fix Status:** ✅ READY FOR TESTING
**Estimated Test Time:** 5 minutes
**Risk Level:** LOW (only affects event display, not detection)
