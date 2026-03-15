package com.dearmoon.shield.security;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import androidx.test.core.app.ApplicationProvider;
import com.dearmoon.shield.data.EventDatabase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ConfigAuditCheckerTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // Clear ADB setting for tests
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
    }

    @Test
    public void testAudit_Debuggable() throws Exception {
        Context mockContext = mock(Context.class);
        PackageManager mockPm = mock(PackageManager.class);
        when(mockContext.getPackageManager()).thenReturn(mockPm);
        when(mockContext.getPackageName()).thenReturn("com.dearmoon.shield");
        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        when(mockContext.getContentResolver()).thenReturn(context.getContentResolver());

        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE; // DANGEROUS
        when(mockContext.getApplicationInfo()).thenReturn(appInfo);

        PackageInfo pkgInfo = new PackageInfo();
        pkgInfo.activities = new ActivityInfo[0];
        when(mockPm.getPackageInfo(anyString(), anyInt())).thenReturn(pkgInfo);

        try (MockedStatic<EventDatabase> mockedDb = mockStatic(EventDatabase.class)) {
            EventDatabase mockDb = mock(EventDatabase.class);
            mockedDb.when(() -> EventDatabase.getInstance(any())).thenReturn(mockDb);

            List<ConfigAuditChecker.ConfigFinding> findings = ConfigAuditChecker.audit(mockContext);

            boolean foundDebuggableField = false;
            for (ConfigAuditChecker.ConfigFinding f : findings) {
                if (f.category.equals("DEBUGGABLE")) {
                    assertEquals(ConfigAuditChecker.Severity.FAIL, f.severity);
                    foundDebuggableField = true;
                }
            }
            assertTrue("Should have finding for Debuggable", foundDebuggableField);
        }
    }

    @Test
    public void testAudit_AdbEnabled() throws Exception {
        // Case: ADB enabled (DANGEROUS)
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);

        try (MockedStatic<EventDatabase> mockedDb = mockStatic(EventDatabase.class)) {
            EventDatabase mockDb = mock(EventDatabase.class);
            mockedDb.when(() -> EventDatabase.getInstance(any())).thenReturn(mockDb);

            List<ConfigAuditChecker.ConfigFinding> findings = ConfigAuditChecker.audit(context);

            boolean foundAdb = false;
            for (ConfigAuditChecker.ConfigFinding f : findings) {
                if (f.category.equals("ADB_ENABLED")) {
                    assertEquals(ConfigAuditChecker.Severity.WARN, f.severity);
                    foundAdb = true;
                }
            }
            assertTrue("Should have finding for ADB Debugging", foundAdb);
        }
    }

    @Test
    public void testAudit_ExportedActivityWithoutIntentFilter() throws Exception {
        Context mockContext = mock(Context.class);
        PackageManager mockPm = mock(PackageManager.class);
        when(mockContext.getPackageManager()).thenReturn(mockPm);
        when(mockContext.getPackageName()).thenReturn("com.dearmoon.shield");
        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        when(mockContext.getContentResolver()).thenReturn(context.getContentResolver());

        ApplicationInfo appInfo = new ApplicationInfo();
        when(mockContext.getApplicationInfo()).thenReturn(appInfo);

        PackageInfo pkgInfo = new PackageInfo();
        ActivityInfo activity = new ActivityInfo();
        activity.name = "UnsafeActivity";
        activity.exported = true; // DANGEROUS if no filter (though PM returns true if there is a filter, 
                                  // but our checker checks if it's exported and warns/fails)
        pkgInfo.activities = new ActivityInfo[]{activity};
        when(mockPm.getPackageInfo(anyString(), anyInt())).thenReturn(pkgInfo);

        try (MockedStatic<EventDatabase> mockedDb = mockStatic(EventDatabase.class)) {
            EventDatabase mockDb = mock(EventDatabase.class);
            mockedDb.when(() -> EventDatabase.getInstance(any())).thenReturn(mockDb);

            List<ConfigAuditChecker.ConfigFinding> findings = ConfigAuditChecker.audit(mockContext);

            boolean foundExported = false;
            for (ConfigAuditChecker.ConfigFinding f : findings) {
                if (f.category.equals("EXPORTED_ACTIVITY") && f.description.contains("UnsafeActivity")) {
                    foundExported = true;
                }
            }
            assertTrue("Should flag exported activity", foundExported);
        }
    }
}
