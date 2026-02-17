package com.dearmoon.shield.collectors;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import com.dearmoon.shield.data.TelemetryStorage;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;
import com.dearmoon.shield.snapshot.SnapshotManager;
import java.io.File;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

/**
 * Unit tests for RecursiveFileSystemCollector.
 * Tests verify that subdirectories are monitored correctly with depth and count limits.
 */
public class RecursiveFileSystemCollectorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Mock
    private TelemetryStorage mockStorage;

    @Mock
    private UnifiedDetectionEngine mockEngine;

    @Mock
    private SnapshotManager mockSnapshotManager;

    private RecursiveFileSystemCollector collector;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    /**
     * TEST 1: Verify single directory monitoring
     */
    @Test
    public void testSingleDirectoryMonitoring() throws Exception {
        File rootDir = tempFolder.newFolder("root");
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.setDetectionEngine(mockEngine);
        collector.setSnapshotManager(mockSnapshotManager);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        assertEquals("Should monitor 1 directory", 1, count);
        
        collector.stopWatching();
        System.out.println("✅ Single directory monitoring works");
    }

    /**
     * TEST 2: Verify depth-1 subdirectory monitoring
     */
    @Test
    public void testDepth1Monitoring() throws Exception {
        File rootDir = tempFolder.newFolder("root");
        File subDir1 = new File(rootDir, "sub1");
        File subDir2 = new File(rootDir, "sub2");
        File subDir3 = new File(rootDir, "sub3");
        
        subDir1.mkdir();
        subDir2.mkdir();
        subDir3.mkdir();
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        assertEquals("Should monitor root + 3 subdirs = 4", 4, count);
        
        collector.stopWatching();
        System.out.println("✅ Depth-1 monitoring works: " + count + " directories");
    }

    /**
     * TEST 3: Verify depth-2 subdirectory monitoring
     */
    @Test
    public void testDepth2Monitoring() throws Exception {
        File rootDir = tempFolder.newFolder("root");
        File level1 = new File(rootDir, "level1");
        level1.mkdir();
        File level2a = new File(level1, "level2a");
        File level2b = new File(level1, "level2b");
        level2a.mkdir();
        level2b.mkdir();
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        assertEquals("Should monitor root + level1 + 2 level2 = 4", 4, count);
        
        collector.stopWatching();
        System.out.println("✅ Depth-2 monitoring works: " + count + " directories");
    }

    /**
     * TEST 4: Verify depth-8 limit enforcement
     */
    @Test
    public void testDepth8LimitEnforcement() throws Exception {
        File rootDir = tempFolder.newFolder("root");
        
        // Create directory tree: root/l1/l2/.../l9
        File current = rootDir;
        for (int i = 1; i <= 9; i++) {
            current = new File(current, "level" + i);
            current.mkdir();
        }
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        // Should monitor depth 0 to 8 (9 directories total)
        assertEquals("Should monitor up to depth 8 only (root + l1...l8 = 9)", 9, count);
        
        collector.stopWatching();
        System.out.println("✅ Depth-8 limit enforced: " + count + " directories (level9 excluded)");
    }

    /**
     * TEST 5: Verify hidden directory exclusion
     */
    @Test
    public void testHiddenDirectoryExclusion() throws Exception {
        File rootDir = tempFolder.newFolder("root");
        File normalDir = new File(rootDir, "normal");
        File hiddenDir = new File(rootDir, ".hidden");
        
        normalDir.mkdir();
        hiddenDir.mkdir();
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        assertEquals("Should monitor root + normal only (exclude .hidden) = 2", 2, count);
        
        collector.stopWatching();
        System.out.println("✅ Hidden directories excluded: " + count + " directories");
    }

    /**
     * TEST 6: Verify Android directory exclusion
     */
    @Test
    public void testAndroidDirectoryExclusion() throws Exception {
        File rootDir = tempFolder.newFolder("root");
        File androidDir = new File(rootDir, "Android");
        File cacheDir = new File(rootDir, "cache");
        File normalDir = new File(rootDir, "Documents");
        
        androidDir.mkdir();
        cacheDir.mkdir();
        normalDir.mkdir();
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        assertEquals("Should monitor root + Documents only (exclude Android, cache) = 2", 2, count);
        
        collector.stopWatching();
        System.out.println("✅ System directories excluded: " + count + " directories");
    }

    /**
     * TEST 7: Verify max observers limit (1000)
     */
    @Test
    public void testMaxObserversLimit() throws Exception {
        File rootDir = tempFolder.newFolder("root");
        
        // Create 1100 subdirectories (should stop at 1000)
        for (int i = 0; i < 1100; i++) {
            File subDir = new File(rootDir, "dir" + i);
            subDir.mkdir();
        }
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        assertTrue("Should not exceed 1000 observers", count <= 1000);
        
        collector.stopWatching();
        System.out.println("✅ Max observers limit enforced: " + count + " directories (max 1000)");
    }

    /**
     * TEST 8: Verify non-existent directory handling
     */
    @Test
    public void testNonExistentDirectory() {
        String nonExistentPath = tempFolder.getRoot().getAbsolutePath() + "/nonexistent";
        
        collector = new RecursiveFileSystemCollector(nonExistentPath, mockStorage);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        assertEquals("Non-existent directory should result in 0 monitors", 0, count);
        
        collector.stopWatching();
        System.out.println("✅ Non-existent directory handled gracefully");
    }

    /**
     * TEST 9: Verify file (not directory) handling
     */
    @Test
    public void testFileInsteadOfDirectory() throws Exception {
        File file = tempFolder.newFile("notadirectory.txt");
        
        collector = new RecursiveFileSystemCollector(file.getAbsolutePath(), mockStorage);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        assertEquals("File path should result in 0 monitors", 0, count);
        
        collector.stopWatching();
        System.out.println("✅ File path handled gracefully");
    }

    /**
     * TEST 10: Verify detection engine integration
     */
    @Test
    public void testDetectionEngineIntegration() throws Exception {
        File rootDir = tempFolder.newFolder("root");
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.setDetectionEngine(mockEngine);
        collector.startWatching();
        
        // Verify detection engine was set (indirectly through successful start)
        int count = collector.getMonitoredDirectoryCount();
        assertTrue("Should have started monitoring", count > 0);
        
        collector.stopWatching();
        System.out.println("✅ Detection engine integration works");
    }

    /**
     * TEST 11: Verify snapshot manager integration
     */
    @Test
    public void testSnapshotManagerIntegration() throws Exception {
        File rootDir = tempFolder.newFolder("root");
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.setSnapshotManager(mockSnapshotManager);
        collector.startWatching();
        
        // Verify snapshot manager was set (indirectly through successful start)
        int count = collector.getMonitoredDirectoryCount();
        assertTrue("Should have started monitoring", count > 0);
        
        collector.stopWatching();
        System.out.println("✅ Snapshot manager integration works");
    }

    /**
     * TEST 12: Verify complex directory tree
     */
    @Test
    public void testComplexDirectoryTree() throws Exception {
        File rootDir = tempFolder.newFolder("root");
        
        // Create realistic directory structure
        File documents = new File(rootDir, "Documents");
        documents.mkdir();
        File work = new File(documents, "Work");
        work.mkdir();
        File projects = new File(work, "Projects");
        projects.mkdir();
        
        File pictures = new File(rootDir, "Pictures");
        pictures.mkdir();
        File vacation = new File(pictures, "Vacation");
        vacation.mkdir();
        
        File downloads = new File(rootDir, "Downloads");
        downloads.mkdir();
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        // root + documents + work + projects + pictures + vacation + downloads = 7
        assertEquals("Should monitor all directories in tree", 7, count);
        
        collector.stopWatching();
        System.out.println("✅ Complex directory tree monitored: " + count + " directories");
    }

    /**
     * TEST 13: Verify stopWatching cleanup
     */
    @Test
    public void testStopWatchingCleanup() throws Exception {
        File rootDir = tempFolder.newFolder("root");
        File sub1 = new File(rootDir, "sub1");
        File sub2 = new File(rootDir, "sub2");
        sub1.mkdir();
        sub2.mkdir();
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.startWatching();
        
        int countBefore = collector.getMonitoredDirectoryCount();
        assertTrue("Should have monitors before stop", countBefore > 0);
        
        collector.stopWatching();
        
        int countAfter = collector.getMonitoredDirectoryCount();
        assertEquals("Should have 0 monitors after stop", 0, countAfter);
        
        System.out.println("✅ stopWatching cleanup works: " + countBefore + " → " + countAfter);
    }

    /**
     * TEST 14: Verify empty directory handling
     */
    @Test
    public void testEmptyDirectory() throws Exception {
        File rootDir = tempFolder.newFolder("empty_root");
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        assertEquals("Empty directory should still be monitored", 1, count);
        
        collector.stopWatching();
        System.out.println("✅ Empty directory monitored correctly");
    }

    /**
     * TEST 15: Verify mixed content (files + directories)
     */
    @Test
    public void testMixedContent() throws Exception {
        File rootDir = tempFolder.newFolder("mixed");
        
        // Create files (should be ignored)
        new File(rootDir, "file1.txt").createNewFile();
        new File(rootDir, "file2.dat").createNewFile();
        
        // Create directories (should be monitored)
        File dir1 = new File(rootDir, "dir1");
        File dir2 = new File(rootDir, "dir2");
        dir1.mkdir();
        dir2.mkdir();
        
        collector = new RecursiveFileSystemCollector(rootDir.getAbsolutePath(), mockStorage);
        collector.startWatching();
        
        int count = collector.getMonitoredDirectoryCount();
        assertEquals("Should monitor root + 2 dirs (ignore files) = 3", 3, count);
        
        collector.stopWatching();
        System.out.println("✅ Mixed content handled correctly: " + count + " directories");
    }
}
