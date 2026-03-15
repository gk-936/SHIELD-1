package com.dearmoon.shield.modea;

import android.content.Context;
import android.os.Environment;
import androidx.test.core.app.ApplicationProvider;
import com.dearmoon.shield.data.EventMerger;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ModeAExclusionTest {

    @Mock
    private UnifiedDetectionEngine mockEngine;

    @Mock
    private EventMerger mockMerger;

    private ModeAFileCollector collector;
    private Context context;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        context = ApplicationProvider.getApplicationContext();
        when(mockEngine.getContext()).thenReturn(context);
        
        collector = new ModeAFileCollector(mockEngine, mockMerger);
    }

    @Test
    public void testExcludeOwnUid() {
        int myUid = context.getApplicationInfo().uid;
        
        ShieldEventData data = new ShieldEventData();
        data.uid = myUid;
        data.pid = 1234;
        data.operation = "WRITE";
        data.filename = "/sdcard/hack.txt";
        
        collector.onKernelEvent(data);
        
        // Should NOT call mergeEvent or processFileEvent
        verify(mockMerger, never()).mergeEvent(any(), anyString());
        verify(mockEngine, never()).processFileEvent(any());
    }

    @Test
    public void testExcludeOwnDataDir() {
        // ModeAFileCollector calculates dataDir as:
        // (Environment.getDataDirectory().getAbsolutePath() + "/data/com.dearmoon.shield")
        String dataDir = Environment.getDataDirectory().getAbsolutePath() + "/data/com.dearmoon.shield";
        
        ShieldEventData data = new ShieldEventData();
        data.uid = 9999; // Different UID
        data.pid = 1234;
        data.operation = "WRITE";
        data.filename = dataDir + "/databases/shield_events.db";
        
        collector.onKernelEvent(data);
        
        verify(mockMerger, never()).mergeEvent(any(), anyString());
    }

    @Test
    public void testAllowOtherEvents() {
        // Configure mockMerger to return a non-null MergedEvent
        EventMerger.MergedEvent mockMerged = new EventMerger.MergedEvent();
        mockMerged.source = "MODE_A";
        mockMerged.mergeFlag = false;
        when(mockMerger.mergeEvent(any(), eq("MODE_A"))).thenReturn(mockMerged);

        ShieldEventData data = new ShieldEventData();
        data.uid = 10050; // Malicious app UID
        data.pid = 2000;
        data.operation = "WRITE";
        data.filename = "/sdcard/photos/vacation.jpg";
        
        collector.onKernelEvent(data);
        
        verify(mockMerger, times(1)).mergeEvent(any(), eq("MODE_A"));
        verify(mockEngine, times(1)).processFileEvent(any());
    }
}
