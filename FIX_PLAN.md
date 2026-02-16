# SHIELD - FIX & IMPROVEMENT PLAN (PHASE 7)

## CRITICAL FIXES REQUIRED

### FIX 1: LockerShieldEvent Database Integration ⚠️ HIGH PRIORITY

**Issue**: LockerShieldEvent not stored in EventDatabase
**Impact**: Accessibility threat events are lost
**Solution**: Add locker_shield_events table to EventDatabase

### FIX 2: SnapshotManager Integration ⚠️ HIGH PRIORITY

**Issue**: trackFileChange() never called from collectors
**Impact**: Snapshot system non-functional
**Solution**: Call snapshotManager.trackFileChange() from FileSystemCollector

### FIX 3: HoneyfileCollector UID Detection ⚠️ MEDIUM PRIORITY

**Issue**: Binder.getCallingUid() returns app's own UID in FileObserver
**Impact**: Cannot distinguish between app and external access
**Solution**: Use inotify or accept limitation (document behavior)

### FIX 4: FileSystemCollector Recursive Monitoring ⚠️ LOW PRIORITY

**Issue**: Only monitors top-level directories
**Impact**: Misses subdirectory changes
**Solution**: Implement recursive FileObserver creation

### FIX 5: IPv6 Network Support ⚠️ LOW PRIORITY

**Issue**: NetworkGuardService only handles IPv4
**Impact**: IPv6 traffic not monitored
**Solution**: Add IPv6 header parsing

### FIX 6: User-Facing Alert Dialog ⚠️ MEDIUM PRIORITY

**Issue**: No immediate user notification on high-risk detection
**Impact**: User may not know ransomware detected
**Solution**: Show AlertDialog on confidence ≥70

### FIX 7: Process-Level Mitigation ⚠️ LOW PRIORITY

**Issue**: No process killing or app quarantine
**Impact**: Ransomware process continues running
**Solution**: Add UsageStatsManager-based app blocking (requires permission)

---

## IMPLEMENTATION ORDER

1. ✅ Fix 1: LockerShieldEvent database (CRITICAL)
2. ✅ Fix 2: SnapshotManager integration (CRITICAL)
3. ✅ Fix 6: User alert dialog (HIGH)
4. ⏭️ Fix 3: Document UID limitation (MEDIUM)
5. ⏭️ Fix 4: Recursive monitoring (LOW - optional)
6. ⏭️ Fix 5: IPv6 support (LOW - optional)
7. ⏭️ Fix 7: Process mitigation (LOW - requires new permissions)

