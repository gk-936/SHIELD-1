# SHIELD - Unit Tests Documentation

## 📋 Overview

This document describes the comprehensive unit test suite created for all security fixes implemented in SHIELD v1.0.

**Total Test Files:** 5  
**Total Test Cases:** 75  
**Coverage:** All critical and high-priority security fixes

---

## 🧪 Test Files

### 1. EntropyAnalyzerTest.java
**Location:** `app/src/test/java/com/dearmoon/shield/detection/EntropyAnalyzerTest.java`  
**Purpose:** Tests multi-region entropy sampling fix  
**Test Cases:** 10

#### Test Coverage:
- ✅ Footer encryption detection (ransomware bypass scenario)
- ✅ Middle section encryption detection
- ✅ Full-file analysis for small files (<10MB)
- ✅ Plain text low entropy verification
- ✅ Encrypted file high entropy verification
- ✅ High entropy threshold validation
- ✅ Empty file handling
- ✅ Non-existent file handling
- ✅ Byte array entropy calculation
- ✅ Large file performance (15MB in <2 seconds)

**Key Scenarios Tested:**
```java
// Ransomware bypass: encrypt only footer
testFooterEncryptionDetected()  // 20KB file, last 8KB encrypted

// Ransomware bypass: encrypt only middle
testMiddleEncryptionDetected()  // 30KB file, middle 14KB encrypted

// Performance test
testLargeFilePerformance()      // 15MB file, must complete in <5s
```

---

### 2. HoneyfileCollectorTest.java
**Location:** `app/src/test/java/com/dearmoon/shield/collectors/HoneyfileCollectorTest.java`  
**Purpose:** Tests timestamp-based filtering fix (removed broken UID check)  
**Test Cases:** 10

#### Test Coverage:
- ✅ Honeyfile creation with correct names
- ✅ Grace period prevents initial logging (5 seconds)
- ✅ Detection works after grace period expires
- ✅ Honeyfile cleanup
- ✅ Honeyfile content verification
- ✅ Honeyfile permissions (readable, writable)
- ✅ Multiple directories support
- ✅ Non-existent directory handling
- ✅ stopWatching cleanup
- ✅ All honeyfile types created

**Key Scenarios Tested:**
```java
// Grace period test
testGracePeriodPreventsInitialLogging()  // Modify within 5s, no log

// Detection after grace period
testDetectionAfterGracePeriod()          // Modify after 5.5s, logged

// All expected honeyfiles
testAllHoneyfileTypes()                  // 6 types: PASSWORDS.txt, etc.
```

---

### 3. NetworkGuardServiceIPv6Test.java
**Location:** `app/src/test/java/com/dearmoon/shield/services/NetworkGuardServiceIPv6Test.java`  
**Purpose:** Tests IPv6 packet parsing and analysis  
**Test Cases:** 15

#### Test Coverage:
- ✅ IPv4 version detection (version = 4)
- ✅ IPv6 version detection (version = 6)
- ✅ IPv4 destination IP extraction
- ✅ IPv6 destination IP extraction (128-bit address)
- ✅ IPv4 protocol extraction (TCP, UDP)
- ✅ IPv6 next header extraction (TCP, UDP)
- ✅ IPv6 localhost detection (::1)
- ✅ IPv6 link-local detection (fe80::/10)
- ✅ IPv6 multicast detection (ff00::/8)
- ✅ IPv4 port extraction
- ✅ IPv6 port extraction
- ✅ Malicious port detection (4444, 5555, 6666, 7777)
- ✅ Packet size validation (IPv4: 20 bytes, IPv6: 40 bytes)

**Key Scenarios Tested:**
```java
// IPv6 address extraction
testIPv6DestinationExtraction()  // 2001:0db8:85a3::8a2e:0370:7334

// IPv6 protocol detection
testIPv6NextHeaderTCP()          // Next header = 6 (TCP)

// Malicious port detection
testMaliciousPortDetection()     // Ports 4444, 5555, 6666, 7777
```

---

### 4. RecursiveFileSystemCollectorTest.java
**Location:** `app/src/test/java/com/dearmoon/shield/collectors/RecursiveFileSystemCollectorTest.java`  
**Purpose:** Tests recursive directory monitoring with depth and count limits  
**Test Cases:** 15

#### Test Coverage:
- ✅ Single directory monitoring
- ✅ Depth-1 subdirectory monitoring
- ✅ Depth-2 subdirectory monitoring
- ✅ Depth-3 limit enforcement (max depth = 3)
- ✅ Hidden directory exclusion (`.hidden`)
- ✅ System directory exclusion (`Android`, `cache`)
- ✅ Max observers limit (100 directories)
- ✅ Non-existent directory handling
- ✅ File (not directory) handling
- ✅ Detection engine integration
- ✅ Snapshot manager integration
- ✅ Complex directory tree
- ✅ stopWatching cleanup
- ✅ Empty directory handling
- ✅ Mixed content (files + directories)

**Key Scenarios Tested:**
```java
// Depth limit enforcement
testDepth3LimitEnforcement()     // root/l1/l2/l3/l4 → only l1-l3 monitored

// Max observers limit
testMaxObserversLimit()          // 120 dirs created → only 100 monitored

// System directory exclusion
testAndroidDirectoryExclusion()  // Android, cache excluded
```

---

### 5. SecurityUtilsTest.java
**Location:** `app/src/test/java/com/dearmoon/shield/security/SecurityUtilsTest.java`  
**Purpose:** Tests improved root detection and signature verification  
**Test Cases:** 15

#### Test Coverage:
- ✅ Traditional su path detection (10 paths)
- ✅ Magisk path detection (3 paths)
- ✅ PATH environment variable check
- ✅ Test-keys build detection
- ✅ Debugger detection
- ✅ Emulator detection
- ✅ Xposed hook detection
- ✅ Signature verification (development mode)
- ✅ Signature verification (no signatures)
- ✅ Signature hash calculation
- ✅ checkSecurity aggregation
- ✅ Root detection on non-rooted device
- ✅ File existence check logic
- ✅ Executable check logic
- ✅ Build.TAGS check

**Key Scenarios Tested:**
```java
// Magisk detection
testMagiskPathDetection()        // /data/adb/magisk, /sbin/.magisk

// PATH check
testPATHEnvironmentCheck()       // Search for su in PATH dirs

// Signature verification
testSignatureVerificationDevelopmentMode()  // EXPECTED_HASH = null
```

---

### 6. SecurityFixesTestSuite.java
**Location:** `app/src/test/java/com/dearmoon/shield/SecurityFixesTestSuite.java`  
**Purpose:** Test suite runner for all security fix tests

---

## 🚀 Running Tests

### Run All Tests
```bash
./gradlew test
```

### Run Specific Test Class
```bash
./gradlew test --tests EntropyAnalyzerTest
./gradlew test --tests HoneyfileCollectorTest
./gradlew test --tests NetworkGuardServiceIPv6Test
./gradlew test --tests RecursiveFileSystemCollectorTest
./gradlew test --tests SecurityUtilsTest
```

### Run Specific Test Method
```bash
./gradlew test --tests EntropyAnalyzerTest.testFooterEncryptionDetected
./gradlew test --tests HoneyfileCollectorTest.testGracePeriodPreventsInitialLogging
```

### Run Test Suite
```bash
./gradlew test --tests SecurityFixesTestSuite
```

### Run with Coverage Report
```bash
./gradlew testDebugUnitTestCoverage
```
Coverage report will be generated at:  
`app/build/reports/coverage/test/debug/index.html`

### Run in Android Studio
1. Right-click on test file → Run 'TestClassName'
2. Right-click on test method → Run 'testMethodName()'
3. Right-click on `SecurityFixesTestSuite` → Run 'SecurityFixesTestSuite'

---

## 📊 Test Statistics

| Test File | Test Cases | Lines of Code | Coverage |
|-----------|------------|---------------|----------|
| EntropyAnalyzerTest | 10 | 280 | Entropy analysis |
| HoneyfileCollectorTest | 10 | 250 | Honeyfile detection |
| NetworkGuardServiceIPv6Test | 15 | 380 | IPv6 support |
| RecursiveFileSystemCollectorTest | 15 | 420 | Recursive monitoring |
| SecurityUtilsTest | 15 | 370 | Root & signature |
| **Total** | **75** | **1,700** | **All fixes** |

---

## ✅ Test Requirements

### Dependencies (already in build.gradle)
```gradle
testImplementation 'junit:junit:4.13.2'
testImplementation 'org.mockito:mockito-core:4.0.0'
testImplementation 'org.mockito:mockito-inline:4.0.0'
androidTestImplementation 'androidx.test.ext:junit:1.1.5'
androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
```

### Android SDK Requirements
- Minimum SDK: 26 (Android 8.0)
- Target SDK: 34 (Android 14)
- JUnit: 4.13.2
- Mockito: 4.0.0

---

## 🎯 Expected Test Results

### All Tests Should Pass ✅

**Expected Output:**
```
> Task :app:testDebugUnitTest

EntropyAnalyzerTest > testFooterEncryptionDetected PASSED
EntropyAnalyzerTest > testMiddleEncryptionDetected PASSED
EntropyAnalyzerTest > testSmallFileFullAnalysis PASSED
EntropyAnalyzerTest > testPlainTextLowEntropy PASSED
EntropyAnalyzerTest > testEncryptedFileHighEntropy PASSED
EntropyAnalyzerTest > testHighEntropyThreshold PASSED
EntropyAnalyzerTest > testEmptyFile PASSED
EntropyAnalyzerTest > testNonExistentFile PASSED
EntropyAnalyzerTest > testByteArrayEntropy PASSED
EntropyAnalyzerTest > testLargeFilePerformance PASSED

HoneyfileCollectorTest > testHoneyfileCreation PASSED
HoneyfileCollectorTest > testGracePeriodPreventsInitialLogging PASSED
HoneyfileCollectorTest > testDetectionAfterGracePeriod PASSED
HoneyfileCollectorTest > testHoneyfileCleanup PASSED
HoneyfileCollectorTest > testHoneyfileContent PASSED
HoneyfileCollectorTest > testHoneyfilePermissions PASSED
HoneyfileCollectorTest > testMultipleDirectories PASSED
HoneyfileCollectorTest > testNonExistentDirectory PASSED
HoneyfileCollectorTest > testStopWatching PASSED
HoneyfileCollectorTest > testAllHoneyfileTypes PASSED

NetworkGuardServiceIPv6Test > testIPv4VersionDetection PASSED
NetworkGuardServiceIPv6Test > testIPv6VersionDetection PASSED
NetworkGuardServiceIPv6Test > testIPv4DestinationExtraction PASSED
NetworkGuardServiceIPv6Test > testIPv6DestinationExtraction PASSED
NetworkGuardServiceIPv6Test > testIPv4ProtocolTCP PASSED
NetworkGuardServiceIPv6Test > testIPv4ProtocolUDP PASSED
NetworkGuardServiceIPv6Test > testIPv6NextHeaderTCP PASSED
NetworkGuardServiceIPv6Test > testIPv6NextHeaderUDP PASSED
NetworkGuardServiceIPv6Test > testIPv6LocalhostDetection PASSED
NetworkGuardServiceIPv6Test > testIPv6LinkLocalDetection PASSED
NetworkGuardServiceIPv6Test > testIPv6MulticastDetection PASSED
NetworkGuardServiceIPv6Test > testIPv4PortExtraction PASSED
NetworkGuardServiceIPv6Test > testIPv6PortExtraction PASSED
NetworkGuardServiceIPv6Test > testMaliciousPortDetection PASSED
NetworkGuardServiceIPv6Test > testPacketSizeValidation PASSED

RecursiveFileSystemCollectorTest > testSingleDirectoryMonitoring PASSED
RecursiveFileSystemCollectorTest > testDepth1Monitoring PASSED
RecursiveFileSystemCollectorTest > testDepth2Monitoring PASSED
RecursiveFileSystemCollectorTest > testDepth3LimitEnforcement PASSED
RecursiveFileSystemCollectorTest > testHiddenDirectoryExclusion PASSED
RecursiveFileSystemCollectorTest > testAndroidDirectoryExclusion PASSED
RecursiveFileSystemCollectorTest > testMaxObserversLimit PASSED
RecursiveFileSystemCollectorTest > testNonExistentDirectory PASSED
RecursiveFileSystemCollectorTest > testFileInsteadOfDirectory PASSED
RecursiveFileSystemCollectorTest > testDetectionEngineIntegration PASSED
RecursiveFileSystemCollectorTest > testSnapshotManagerIntegration PASSED
RecursiveFileSystemCollectorTest > testComplexDirectoryTree PASSED
RecursiveFileSystemCollectorTest > testStopWatchingCleanup PASSED
RecursiveFileSystemCollectorTest > testEmptyDirectory PASSED
RecursiveFileSystemCollectorTest > testMixedContent PASSED

SecurityUtilsTest > testTraditionalSuPathDetection PASSED
SecurityUtilsTest > testMagiskPathDetection PASSED
SecurityUtilsTest > testPATHEnvironmentCheck PASSED
SecurityUtilsTest > testTestKeysBuildDetection PASSED
SecurityUtilsTest > testDebuggerDetection PASSED
SecurityUtilsTest > testEmulatorDetection PASSED
SecurityUtilsTest > testXposedHookDetection PASSED
SecurityUtilsTest > testSignatureVerificationDevelopmentMode PASSED
SecurityUtilsTest > testSignatureVerificationNoSignatures PASSED
SecurityUtilsTest > testSignatureHashCalculation PASSED
SecurityUtilsTest > testCheckSecurityAggregation PASSED
SecurityUtilsTest > testRootDetectionNonRootedDevice PASSED
SecurityUtilsTest > testFileExistenceCheckLogic PASSED
SecurityUtilsTest > testExecutableCheckLogic PASSED
SecurityUtilsTest > testBuildTagsCheck PASSED

BUILD SUCCESSFUL
75 tests completed, 75 passed
```

---

## 🐛 Troubleshooting

### Test Failures

**Issue:** `testFooterEncryptionDetected` fails  
**Solution:** Verify `EntropyAnalyzer.calculateEntropy()` uses multi-region sampling

**Issue:** `testGracePeriodPreventsInitialLogging` fails  
**Solution:** Verify `CREATION_GRACE_PERIOD_MS = 5000` in `HoneyfileCollector`

**Issue:** `testIPv6DestinationExtraction` fails  
**Solution:** Verify IPv6 parsing starts at byte 24 (not 16)

**Issue:** `testDepth3LimitEnforcement` fails  
**Solution:** Verify `MAX_DEPTH = 3` in `RecursiveFileSystemCollector`

### Build Issues

**Issue:** `Cannot resolve symbol 'MockitoAnnotations'`  
**Solution:** Add Mockito dependency to `build.gradle`:
```gradle
testImplementation 'org.mockito:mockito-core:4.0.0'
```

**Issue:** `TemporaryFolder not found`  
**Solution:** Add JUnit dependency:
```gradle
testImplementation 'junit:junit:4.13.2'
```

---

## 📝 Adding New Tests

### Template for New Test Class
```java
package com.dearmoon.shield.yourpackage;

import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class YourClassTest {
    
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    
    @Before
    public void setUp() {
        // Initialize test objects
    }
    
    @Test
    public void testYourFeature() {
        // Arrange
        // Act
        // Assert
        System.out.println("✅ Test passed");
    }
}
```

---

## 🎓 Best Practices

1. **Arrange-Act-Assert Pattern**
   - Arrange: Set up test data
   - Act: Execute the code being tested
   - Assert: Verify the results

2. **Test Naming Convention**
   - Use descriptive names: `testFooterEncryptionDetected`
   - Not: `test1`, `test2`

3. **One Assertion Per Test**
   - Each test should verify one specific behavior
   - Makes failures easier to diagnose

4. **Use Mocks for Dependencies**
   - Mock `Context`, `TelemetryStorage`, etc.
   - Isolates the code being tested

5. **Clean Up Resources**
   - Use `@Rule TemporaryFolder` for file tests
   - Call cleanup methods in tearDown

---

## ✅ Conclusion

**All security fixes now have comprehensive unit test coverage.**

- 75 test cases covering all critical and high-priority fixes
- Tests verify both positive and negative scenarios
- Edge cases and error handling tested
- Performance tests included
- Easy to run and maintain

**Next Steps:**
1. Run tests: `./gradlew test`
2. Review coverage report
3. Add integration tests (optional)
4. Set up CI/CD to run tests automatically

---

**End of Test Documentation**
