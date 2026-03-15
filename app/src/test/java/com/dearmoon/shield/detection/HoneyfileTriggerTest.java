package com.dearmoon.shield.detection;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.dearmoon.shield.collectors.HoneyfileCollector;
import com.dearmoon.shield.data.TelemetryStorage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HoneyfileTriggerTest {

    private Context context;
    private TelemetryStorage mockStorage;
    private UnifiedDetectionEngine mockEngine;
    private HoneyfileCollector collector;
    private File tempDir;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        mockStorage = mock(TelemetryStorage.class);
        mockEngine = mock(UnifiedDetectionEngine.class);
        
        collector = new HoneyfileCollector(mockStorage, context, mockEngine);
        
        tempDir = new File(context.getFilesDir(), "test_honeyfiles");
        if (!tempDir.exists()) tempDir.mkdirs();
        
        String[] dirs = {tempDir.getAbsolutePath()};
        collector.createHoneyfiles(context, dirs);
    }

    @Test
    public void testHoneyfileModificationTriggersUnconditionalKill() throws Exception {
        // Wait for creation grace period to expire
        Thread.sleep(5100);
        
        // Find one of the generated honeyfiles
        File[] files = tempDir.listFiles();
        assertNotNull("Honeyfiles should have been created", files);
        assertTrue("At least one honeyfile should exist", files.length > 0);
        
        File triggerFile = files[0];
        
        // Simulate ransomware modifying the file
        try (FileWriter writer = new FileWriter(triggerFile, true)) {
            writer.write("\nRANSOMWARE ENCRYPTED DATA");
        }
        
        // Allow FileObserver event to process asynchronously
        Thread.sleep(500);
        
        // Verify that the unconditional kill method was directly called on the engine
        verify(mockEngine, atLeastOnce()).triggerHoneyfileKill(eq(triggerFile.getAbsolutePath()), anyInt());
    }
}
