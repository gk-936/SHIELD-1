package com.dearmoon.shield.data;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PrivacyConsentTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // Clear preferences
        context.getSharedPreferences("shield_privacy", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void testConsentGating_NewUser() {
        // Initially no consent
        assertFalse(PrivacyConsentManager.hasConsent(context));
        
        // Grant consent
        PrivacyConsentManager.recordConsent(context, true);
        assertTrue(PrivacyConsentManager.hasConsent(context));
        
        // Deny consent
        PrivacyConsentManager.recordConsent(context, false);
        assertFalse(PrivacyConsentManager.hasConsent(context));
    }

    @Test
    public void testConsentGating_VersionMismatch() {
        // Record consent for an old version
        context.getSharedPreferences("shield_privacy", Context.MODE_PRIVATE).edit()
                .putBoolean("user_consented", true)
                .putInt("policy_version", 0) // Current is 1
                .commit();
        
        // Should require re-consent
        assertFalse(PrivacyConsentManager.hasConsent(context));
    }

    @Test
    public void testPurgeAllTelemetry() {
        try (MockedStatic<EventDatabase> mockedDb = mockStatic(EventDatabase.class)) {
            EventDatabase mockDb = mock(EventDatabase.class);
            mockedDb.when(() -> EventDatabase.getInstance(any())).thenReturn(mockDb);

            PrivacyConsentManager.purgeAllTelemetry(context);

            // Verify audit row was inserted
            verify(mockDb, times(1)).insertPrivacyConsentEvent(eq("TELEMETRY_PURGE"), eq("USER_INITIATED"), anyString());
            
            // Verify tables were cleared (at least some of them)
            verify(mockDb, atLeastOnce()).purgeTable(anyString());
        }
    }
}
