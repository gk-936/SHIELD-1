# Database Error Fix Summary

## Issues Identified

### 1. **UNION Query Column Mismatch**
**Problem:** The `getAllEvents()` method attempted to use SQL UNION to combine three tables with different column structures:
- `file_system_events` (8 columns)
- `honeyfile_events` (7 columns)  
- `network_events` (9 columns)

SQL UNION requires all SELECT statements to have the same number of columns with compatible data types.

**Error Symptoms:**
- Database-related crashes when viewing event logs
- "Column count mismatch" or "no such column" errors
- LogViewerActivity failing to load events

### 2. **Missing Column Handling**
**Problem:** The cursor parsing used `getColumnIndexOrThrow()` which throws exceptions if columns don't exist or contain NULL values.

### 3. **Obsolete Helper Method**
**Problem:** The `cursorToJsonList()` method was designed for the broken UNION query and tried to read a non-existent 'source' column.

---

## Solutions Implemented

### Fix 1: Separate Table Queries
**Changed:** Replaced single UNION query with individual table queries

**Before:**
```java
String query = "SELECT * FROM (" +
    "SELECT *, 'FILE_SYSTEM' as source FROM file_system_events" +
    " UNION ALL SELECT *, 'HONEYFILE_ACCESS' as source FROM honeyfile_events" +
    " UNION ALL SELECT *, 'NETWORK' as source FROM network_events" +
    ") ORDER BY timestamp DESC LIMIT " + limit;
```

**After:**
```java
if ("ALL".equals(eventType)) {
    events.addAll(queryTable(db, TABLE_FILE_SYSTEM, "FILE_SYSTEM", limit));
    events.addAll(queryTable(db, TABLE_HONEYFILE, "HONEYFILE_ACCESS", limit));
    events.addAll(queryTable(db, TABLE_NETWORK, "NETWORK", limit));
    
    // Sort by timestamp descending
    events.sort((a, b) -> Long.compare(b.optLong("timestamp", 0), a.optLong("timestamp", 0)));
    
    // Limit results
    if (events.size() > limit) {
        events = events.subList(0, limit);
    }
}
```

### Fix 2: Null-Safe Column Reading
**Changed:** Replaced `getColumnIndexOrThrow()` with safe index checking

**Before:**
```java
json.put("operation", cursor.getString(cursor.getColumnIndexOrThrow("operation")));
```

**After:**
```java
int opIdx = cursor.getColumnIndex("operation");
json.put("operation", opIdx >= 0 && !cursor.isNull(opIdx) ? cursor.getString(opIdx) : "UNKNOWN");
```

### Fix 3: New Helper Method
**Added:** `queryTable()` method that safely queries individual tables

**Features:**
- Proper cursor management (try-finally with close)
- Null-safe column reading
- Type-specific JSON parsing
- Detailed error logging
- Returns empty list on errors (no crashes)

### Fix 4: Removed Obsolete Code
**Deleted:** `cursorToJsonList()` method that was causing column mismatch errors

---

## Database Schema (Verified Correct)

### file_system_events
- id, timestamp, event_type, operation, file_path, file_extension, file_size_before, file_size_after

### honeyfile_events  
- id, timestamp, event_type, file_path, access_type, calling_uid, package_name

### network_events
- id, timestamp, event_type, destination_ip, destination_port, protocol, bytes_sent, bytes_received, app_uid

### detection_results
- id, timestamp, file_path, entropy, kl_divergence, sprt_state, confidence_score

### locker_shield_events
- id, timestamp, event_type, package_name, threat_type, risk_score, details

### correlation_results
- id, timestamp, event_type, file_path, package_name, uid, behavior_score, file_event_count, network_event_count, honeyfile_event_count, locker_event_count, syscall_pattern

---

## Testing Checklist

- [x] EventDatabase compiles without errors
- [x] getAllEvents("ALL", 1000) returns merged results from all tables
- [x] getAllEvents("FILE_SYSTEM", 100) returns only file system events
- [x] getAllEvents("HONEYFILE_ACCESS", 100) returns only honeyfile events
- [x] getAllEvents("NETWORK", 100) returns only network events
- [x] getAllDetectionResults(100) returns detection results
- [x] Null values in database don't cause crashes
- [x] LogViewerActivity can load and display events
- [x] Event filtering works correctly
- [x] Timestamp sorting works correctly

---

## Files Modified

1. **EventDatabase.java**
   - Fixed `getAllEvents()` method
   - Added `queryTable()` helper method
   - Removed `cursorToJsonList()` method
   - Added null-safe column reading

---

## Performance Impact

**Positive:**
- More efficient: Queries only needed tables (not all 3 for filtered views)
- Better error handling: Crashes prevented with try-catch and null checks
- Cleaner code: Separate method for table queries

**Neutral:**
- For "ALL" filter: 3 separate queries + in-memory sort vs 1 UNION query
- Performance difference negligible for typical event counts (<1000 events)

---

## Backward Compatibility

✅ **Fully compatible** - No database schema changes
- Existing data remains intact
- No migration needed
- Only query logic changed

---

## Error Handling Improvements

1. **Cursor Management:** Proper try-finally blocks ensure cursors are always closed
2. **Column Validation:** Check column index before reading
3. **Null Handling:** Use `cursor.isNull()` before accessing values
4. **Default Values:** Provide sensible defaults for missing/null data
5. **Logging:** Detailed error messages for debugging

---

## Status: ✅ FIXED

The database implementation is now correct and robust. Event logs should load without errors.
