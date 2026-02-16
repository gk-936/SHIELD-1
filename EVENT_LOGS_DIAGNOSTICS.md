# Event Logs & Snapshot Timestamp - Fixes & Diagnostics

## Issues Fixed ✅

### 1. Snapshot Timestamp Always Showing "Just Now"
**Problem:** The snapshot timestamp was always showing "Just now" instead of the actual date/time.

**Root Cause:**  
The code was checking if less than 1 minute had passed and showing "Just now". Since snapshots are created when the service starts, they're always recent.

**Solution:**  
Changed to show actual timestamp in readable format:
```java
// Before: "Just now" or "X minutes ago"
// After: "14 Feb 2026 20:45:30"
```

**Format:** `dd MMM yyyy HH:mm:ss`  
**Example:** `Last snapshot: 16 Feb 2026 21:05:30`

---

### 2. Event Logs Not Showing Events
**Possible Causes & Solutions:**

#### A. Service Not Running
**Check:**
1. Go to Main screen
2. Look for "SHIELD Protection Active" notification
3. Status should say "Protection Active"

**Solution:** Tap "Non-Root" button to start the service

---

#### B. No Events Generated Yet
**The service only logs these events:**
- **FILE_SYSTEM:** File deletions, modifications, compressions (NOT creation, read, or moves)
- **HONEYFILE_ACCESS:** Access to honeyfiles
- **NETWORK:** Network connections (requires VPN permission)
- **DETECTION:** Ransomware detection alerts
- **ACCESSIBILITY:** Accessibility events (requires accessibility permission)

**To Generate Test Events:**
1. Go to Settings → Test Suite
2. Run the ransomware simulation
3. This will create file operations that get logged

---

#### C. Database Connection Issue
**New Diagnostic Features Added:**

1. **Event Count Toast:**
   - When you open Event Logs, you'll see a toast message
   - Shows: "Loaded X events from database"
   - If it says "Loaded 0 events", the database is empty

2. **Refresh Button:**
   - New refresh icon (🔄) next to the filter dropdown
   - Tap to manually reload events from database
   - Shows "Refreshing logs..." toast

3. **Log Messages:**
   - Check Android Logcat for detailed logging
   - Filter by tag: `LogViewerActivity`
   - Look for: "=== Loaded X total events from SQLite ==="

---

## How to Diagnose Event Logging Issues

### Step 1: Check Service Status
```
Main Screen → Should show "Protection Active"
Notification → Should see "SHIELD Protection Active"
```

### Step 2: Generate Test Events
```
Settings → Test Suite → Run Ransomware Test
```
This will:
- Create test files
- Modify them
- Delete them
- Trigger detection
- All should be logged

### Step 3: Check Event Logs
```
Event Logs → Look for toast: "Loaded X events from database"
```

**If X = 0:**
- Service might not be running
- No events have been generated yet
- Database might be empty

**If X > 0 but nothing shows:**
- Try changing the filter dropdown
- Tap the refresh button (🔄)
- Check if "View Detailed Logs" button is visible

### Step 4: Use Refresh Button
```
Event Logs → Tap refresh icon (🔄) next to filter
```
- Shows "Refreshing logs..." toast
- Then shows "Loaded X events from database"
- Reloads everything from database

---

## Event Types & When They're Logged

### FILE_SYSTEM Events
**Logged:**
- ✅ File deletions
- ✅ File modifications
- ✅ Archive compressions (.zip, .rar, .7z)

**NOT Logged:**
- ❌ File creation (unless it's an archive)
- ❌ File reads
- ❌ File moves/renames
- ❌ Directory operations

**Why:** To reduce noise and focus on ransomware-like behavior

### HONEYFILE_ACCESS Events
**Logged:**
- ✅ Any access to honeyfiles
- ✅ Read attempts
- ✅ Write attempts
- ✅ Delete attempts

**Honeyfiles are created in:**
- Documents/
- Download/
- Pictures/
- DCIM/

### NETWORK Events
**Logged:**
- ✅ Outgoing connections
- ✅ Protocol (TCP/UDP)
- ✅ Destination IP and port
- ✅ Bytes sent/received

**Requires:** VPN permission (auto-requested when service starts)

### DETECTION Events
**Logged:**
- ✅ High-confidence ransomware detections
- ✅ Entropy analysis results
- ✅ KL-divergence scores
- ✅ SPRT state changes

**Triggers:** Unusual file modification patterns

### ACCESSIBILITY Events
**Logged:**
- ✅ App interactions
- ✅ Window changes
- ✅ UI events

**Requires:** Accessibility permission

---

## Troubleshooting Checklist

- [ ] Service is running (check notification)
- [ ] Protection status shows "Active"
- [ ] Generated test events (Test Suite)
- [ ] Opened Event Logs screen
- [ ] Saw toast with event count
- [ ] Tried tapping refresh button (🔄)
- [ ] Tried different filters (ALL, FILE_SYSTEM, etc.)
- [ ] Tapped "View Detailed Logs" button
- [ ] Checked Android Logcat for errors

---

## Expected Behavior

### When Opening Event Logs:
1. Toast appears: "Loaded X events from database"
2. Event count shows: "Showing Y of X events"
3. Graph displays (line or bar based on filter)
4. "View Detailed Logs" button is visible

### When Tapping Refresh (🔄):
1. Toast: "Refreshing logs..."
2. Toast: "Loaded X events from database"
3. Graph updates
4. Event count updates

### When Changing Filter:
1. Event count updates
2. Graph switches type (line/bar)
3. Detailed logs update (if visible)

---

## Database Location

Events are stored in SQLite database:
```
/data/data/com.dearmoon.shield/databases/shield_events.db
```

Tables:
- `telemetry_events` - File system, network, honeyfile events
- `detection_results` - Ransomware detection results

Retention: 7 days (automatic cleanup)

---

## Quick Test Procedure

1. **Start Service:**
   - Main screen → Tap "Non-Root"
   - Grant VPN permission if asked
   - Check notification appears

2. **Generate Events:**
   - Settings → Test Suite
   - Run ransomware simulation
   - Wait for completion

3. **View Logs:**
   - Event Logs → Check toast message
   - Should show "Loaded X events" where X > 0
   - Tap refresh (🔄) to reload

4. **Check Filters:**
   - Try "ALL" filter
   - Try "FILE_SYSTEM" filter
   - Try "DETECTION" filter

5. **View Details:**
   - Tap "View Detailed Logs"
   - Should see list of events
   - Each event shows timestamp, title, details

---

## If Still No Events

1. **Check Logcat:**
   ```
   adb logcat | grep -i "shield\|telemetry\|logviewer"
   ```

2. **Check Database:**
   ```
   adb shell
   cd /data/data/com.dearmoon.shield/databases
   sqlite3 shield_events.db
   SELECT COUNT(*) FROM telemetry_events;
   SELECT COUNT(*) FROM detection_results;
   ```

3. **Check Service:**
   ```
   adb shell dumpsys activity services | grep -i shield
   ```

4. **Check Permissions:**
   - Settings → Apps → SHIELD → Permissions
   - Should have: Storage, VPN, Notifications
   - Optional: Accessibility

---

All diagnostic features are now in place! The app will tell you exactly how many events it loaded. 🎉
