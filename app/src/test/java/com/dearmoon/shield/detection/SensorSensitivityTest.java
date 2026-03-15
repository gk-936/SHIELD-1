package com.dearmoon.shield.detection;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.dearmoon.shield.data.FileSystemEvent;
import com.dearmoon.shield.snapshot.SnapshotManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SensorSensitivityTest {

    private UnifiedDetectionEngine engine;
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        SnapshotManager mockSnapshotManager = mock(SnapshotManager.class);
        engine = new UnifiedDetectionEngine(context, mockSnapshotManager, Runnable::run);
    }

    @Test
    public void testSingleHighEntropyFile_DoesNotTriggerKill() throws Exception {
        // Given: The characteristics of a highly compressed/encrypted file
        double entropy = 7.95; // Maximum entropy boundary
        double kl = 0.01;      // Minimum KL divergence boundary

        // When: We calculate the confidence score based on SPRT CONTINUE state
        // (1 file isn't enough to trigger SPRT ACCEPT_H1)
        Method calculateScoreMethod = UnifiedDetectionEngine.class.getDeclaredMethod(
                "calculateConfidenceScore", double.class, double.class, SPRTDetector.SPRTState.class);
        calculateScoreMethod.setAccessible(true);
        
        int score = (int) calculateScoreMethod.invoke(
                engine, entropy, kl, SPRTDetector.SPRTState.CONTINUE);

        // Then: The score should be 50 (30 for entropy + 20 for KL + 0 for SPRT)
        // This is safely below the 70-point kill threshold.
        assertEquals("Single high-entropy file score should be exactly 50", 50, score);
        assertFalse("Score should not be High Risk", score >= 70);
    }


    @Test
    public void testMassHighEntropyFiles_TriggersKill() throws Exception {
        // Given: High entropy file characteristics
        double entropy = 7.95;
        double kl = 0.01;
        
        // When: SPRT detects a ransomware rate (ACCEPT_H1)
        Method calculateScoreMethod = UnifiedDetectionEngine.class.getDeclaredMethod(
                "calculateConfidenceScore", double.class, double.class, SPRTDetector.SPRTState.class);
        calculateScoreMethod.setAccessible(true);
        
        int score = (int) calculateScoreMethod.invoke(
                engine, entropy, kl, SPRTDetector.SPRTState.ACCEPT_H1);

        // Then: The score should be 100 (capped from 30 + 20 + 50)
        // This easily breaches the 70-point kill threshold.
        assertTrue("Mass modification score should be very high (100)", score == 100);
        assertTrue("Score MUST trigger High Risk", score >= 70);
    }
}
