package com.dearmoon.shield;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import com.dearmoon.shield.detection.EntropyAnalyzerTest;
import com.dearmoon.shield.collectors.HoneyfileCollectorTest;
import com.dearmoon.shield.collectors.RecursiveFileSystemCollectorTest;
import com.dearmoon.shield.services.NetworkGuardServiceIPv6Test;
import com.dearmoon.shield.security.SecurityUtilsTest;
import com.dearmoon.shield.security.ConfigAuditCheckerTest;
import com.dearmoon.shield.security.DependencyIntegrityTest;
import com.dearmoon.shield.data.PrivacyConsentTest;
import com.dearmoon.shield.snapshot.BackupEncryptionTest;
import com.dearmoon.shield.detection.KillMechanismTest;
import com.dearmoon.shield.data.EventMergerTest;

/**
 * SHIELD Security Fixes Test Suite
 * 
 * Runs all unit tests for the security fixes implemented.
 * 
 * To run all tests:
 *   ./gradlew test
 * 
 * To run specific test class:
 *   ./gradlew test --tests EntropyAnalyzerTest
 * 
 * To run with coverage:
 *   ./gradlew testDebugUnitTestCoverage
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    EntropyAnalyzerTest.class,
    HoneyfileCollectorTest.class,
    RecursiveFileSystemCollectorTest.class,
    NetworkGuardServiceIPv6Test.class,
    SecurityUtilsTest.class,
    ConfigAuditCheckerTest.class,
    DependencyIntegrityTest.class,
    PrivacyConsentTest.class,
    BackupEncryptionTest.class,
    KillMechanismTest.class,
    EventMergerTest.class
})
public class SecurityFixesTestSuite {
    // Test suite runner
    // All tests will be executed when this suite is run
}
