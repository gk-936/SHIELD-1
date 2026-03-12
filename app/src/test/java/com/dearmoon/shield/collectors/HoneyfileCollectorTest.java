package com.dearmoon.shield.collectors;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import com.dearmoon.shield.data.TelemetryStorage;
import com.dearmoon.shield.data.HoneyfileEvent;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

/**
 * Unit tests for HoneyfileCollector timestamp-based filtering fix.
 * Tests verify that honeyfile detection works correctly after removing broken UID check.
 */
public class HoneyfileCollectorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Mock
    private TelemetryStorage mockStorage;

    @Mock
    private Context mockContext;

    private HoneyfileCollector collector;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockContext.getPackageName()).thenReturn("com.dearmoon.shield");
        collector = new HoneyfileCollector(mockStorage, mockContext);
    }

    // Helper method to simulate FileObserver event
    private void simulateEvent(String path, int event) throws Exception {
        Field observersField = HoneyfileCollector.class.getDeclaredField("observers");
        observersField.setAccessible(true);
        List<?> observers = (List<?>) observersField.get(collector);
        
        if (observers != null && !observers.isEmpty()) {
            // Get the first observer
            Object observer = observers.get(0);
            
            // Invoke onEvent method
            Method onEventMethod = observer.getClass().getDeclaredMethod("onEvent", int.class, String.class);
            onEventMethod.setAccessible(true);
            onEventMethod.invoke(observer, event, path);
        }
    }

    @Test
    public void testHoneyfileCreation() throws Exception {
        String testDir = tempFolder.newFolder("test_dir").getAbsolutePath();
        String[] dirs = {testDir};
        
        collector.createHoneyfiles(mockContext, dirs);
        
        File dir = new File(testDir);
        String[] files = dir.list();
        assertNotNull(files);
        assertTrue(files.length > 0);
    }

    @Test
    public void testGracePeriodPreventsLogging() throws Exception {
        String testDir = tempFolder.newFolder("grace_test").getAbsolutePath();
        String[] dirs = {testDir};
        
        collector.createHoneyfiles(mockContext, dirs);
        
        // Simulate event immediately (within grace period)
        simulateEvent("PASSWORDS.txt", 2); // MODIFY
        
        // Should NOT log event
        verify(mockStorage, never()).store(any(HoneyfileEvent.class));
    }

    @Test
    public void testDetectionAfterGracePeriod() throws Exception {
        String testDir = tempFolder.newFolder("detection_test").getAbsolutePath();
        String[] dirs = {testDir};
        
        collector.createHoneyfiles(mockContext, dirs);
        
        // Wait for grace period
        Thread.sleep(5500);
        
        // Simulate event
        simulateEvent("PASSWORDS.txt", 2); // MODIFY
        
        // Should log event
        verify(mockStorage, atLeastOnce()).store(any(HoneyfileEvent.class));
    }

    @Test
    public void testHoneyfileCleanup() throws Exception {
        String testDir = tempFolder.newFolder("cleanup_test").getAbsolutePath();
        String[] dirs = {testDir};
        collector.createHoneyfiles(mockContext, dirs);
        collector.clearAllHoneyfiles();
        assertEquals(0, new File(testDir).list().length);
    }
    
    @Test
    public void testMultipleDirectories() throws Exception {
        String dir1 = tempFolder.newFolder("dir1").getAbsolutePath();
        String dir2 = tempFolder.newFolder("dir2").getAbsolutePath();
        String[] dirs = {dir1, dir2};
        
        collector.createHoneyfiles(mockContext, dirs);
        
        assertTrue(new File(dir1).list().length > 0);
        assertTrue(new File(dir2).list().length > 0);
    }
}
