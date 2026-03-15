package com.dearmoon.shield.detection;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import androidx.test.core.app.ApplicationProvider;
import com.dearmoon.shield.data.EventDatabase;
import com.dearmoon.shield.lockerguard.LockerShieldService;
import com.dearmoon.shield.modea.ModeAService;
import com.dearmoon.shield.snapshot.SnapshotManager;
import com.dearmoon.shield.ui.KillGuidanceOverlay;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class KillMechanismTest {

    private UnifiedDetectionEngine engine;
    private Context context;
    private SnapshotManager mockSnapshotManager;

    @Before
    public void setUp() {
        ShadowLog.stream = System.out;
        context = ApplicationProvider.getApplicationContext();
        mockSnapshotManager = mock(SnapshotManager.class);
        // Use a direct executor to make tests synchronous and preserve static mock context
        engine = new UnifiedDetectionEngine(context, mockSnapshotManager, Runnable::run);
    }

    @Test
    public void testKillSequence_ModeAConnected() throws Exception {
        String targetPackage = "com.malware.ransom";
        int targetPid = 1234;

        try (MockedStatic<LockerShieldService> mockedLocker = mockStatic(LockerShieldService.class);
             MockedStatic<ModeAService> mockedModeA = mockStatic(ModeAService.class);
             MockedStatic<KillGuidanceOverlay> mockedOverlay = mockStatic(KillGuidanceOverlay.class)) {

            // Mock Layer 1
            LockerShieldService mockLocker = mock(LockerShieldService.class);
            mockedLocker.when(LockerShieldService::getInstance).thenReturn(mockLocker);

            // Mock Layer 2
            mockedModeA.when(ModeAService::isConnected).thenReturn(true);

            // Mock Layer 3
            KillGuidanceOverlay mockOverlay = mock(KillGuidanceOverlay.class);
            mockedOverlay.when(() -> KillGuidanceOverlay.getInstance(any())).thenReturn(mockOverlay);

            // We need a latch to wait for the background thread in killMaliciousProcess
            CountDownLatch restoreLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                restoreLatch.countDown();
                return null;
            }).when(mockSnapshotManager).performAutomatedRestore();

            // Invoke private killMaliciousProcess via reflection
            Method killMethod = UnifiedDetectionEngine.class.getDeclaredMethod("killMaliciousProcess", String.class);
            killMethod.setAccessible(true);
            killMethod.invoke(engine, targetPackage);

            // Verify Layer 1 (Synchronous now)
            verify(mockLocker).performNavigationEscape();
            
            // Check for Layer 4 completion
            verify(mockSnapshotManager).performAutomatedRestore();
        }
    }

    @Test
    public void testKillSequence_ModeADisconnected_FallbackToLayer3() throws Exception {
        String targetPackage = "com.malware.ransom";

        try (MockedStatic<LockerShieldService> mockedLocker = mockStatic(LockerShieldService.class);
             MockedStatic<ModeAService> mockedModeA = mockStatic(ModeAService.class);
             MockedStatic<KillGuidanceOverlay> mockedOverlay = mockStatic(KillGuidanceOverlay.class)) {

            mockedModeA.when(ModeAService::isConnected).thenReturn(false);
            
            KillGuidanceOverlay mockOverlay = mock(KillGuidanceOverlay.class);
            mockedOverlay.when(() -> KillGuidanceOverlay.getInstance(any())).thenReturn(mockOverlay);

            CountDownLatch restoreLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                restoreLatch.countDown();
                return null;
            }).when(mockSnapshotManager).performAutomatedRestore();

            Method killMethod = UnifiedDetectionEngine.class.getDeclaredMethod("killMaliciousProcess", String.class);
            killMethod.setAccessible(true);
            killMethod.invoke(engine, targetPackage);

            // Verify Layer 3 was triggered
            verify(mockOverlay).show(eq(targetPackage), anyString());
            
            // Verify automated restore triggered
            verify(mockSnapshotManager).performAutomatedRestore();
        }
    }
}
