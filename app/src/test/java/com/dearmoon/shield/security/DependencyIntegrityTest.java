package com.dearmoon.shield.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Base64;
import androidx.test.core.app.ApplicationProvider;
import com.dearmoon.shield.data.EventDatabase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;
import android.content.pm.Signature;

import java.io.File;
import java.security.MessageDigest;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DependencyIntegrityTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        
        // Register package signature in ShadowPackageManager to avoid CHECK_FAILED
        ShadowPackageManager shadowPm = Shadows.shadowOf(context.getPackageManager());
        PackageInfo pi = new PackageInfo();
        pi.packageName = context.getPackageName();
        pi.signatures = new Signature[]{ new Signature(new byte[16]) };
        shadowPm.addPackage(pi);

        // Clear preferences before each test
        context.getSharedPreferences("shield_supply_chain", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void testAudit_BaselineEnrollment() throws Exception {
        try (MockedStatic<EventDatabase> mockedDb = mockStatic(EventDatabase.class)) {
            EventDatabase mockDb = mock(EventDatabase.class);
            mockedDb.when(() -> EventDatabase.getInstance(any())).thenReturn(mockDb);

            DependencyIntegrityChecker.Finding result = DependencyIntegrityChecker.check(context);

            assertEquals(DependencyIntegrityChecker.Finding.BASELINE_ENROLLED, result);

            // SharedPreferences should now have the hash
            SharedPreferences prefs = context.getSharedPreferences("shield_supply_chain", Context.MODE_PRIVATE);
            assertTrue(prefs.contains("cert_hash_baseline"));
        }
    }

    @Test
    public void testAudit_CertChanged() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("shield_supply_chain", Context.MODE_PRIVATE);
        prefs.edit().putString("cert_hash_baseline", "fake_baseline_hash").commit();

        Context mockContext = mock(Context.class);
        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        when(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(mockContext.getPackageName()).thenReturn("com.dearmoon.shield");
        
        PackageManager mockPm = mock(PackageManager.class);
        when(mockContext.getPackageManager()).thenReturn(mockPm);
        
        // Mock a different certificate hash
        android.content.pm.Signature mockSig = mock(android.content.pm.Signature.class);
        byte[] differentCert = new byte[]{0, 1, 2, 3};
        when(mockSig.toByteArray()).thenReturn(differentCert);
        
        PackageInfo pkgInfo = new PackageInfo();
        pkgInfo.signatures = new android.content.pm.Signature[]{mockSig};
        when(mockPm.getPackageInfo(anyString(), anyInt())).thenReturn(pkgInfo);

        try (MockedStatic<EventDatabase> mockedDb = mockStatic(EventDatabase.class)) {
            EventDatabase mockDb = mock(EventDatabase.class);
            mockedDb.when(() -> EventDatabase.getInstance(any())).thenReturn(mockDb);

            DependencyIntegrityChecker.Finding result = DependencyIntegrityChecker.check(mockContext);

            assertEquals(DependencyIntegrityChecker.Finding.CERT_CHANGED, result);
        }
    }
}
