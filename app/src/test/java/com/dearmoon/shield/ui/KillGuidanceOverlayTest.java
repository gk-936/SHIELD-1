package com.dearmoon.shield.ui;

import android.app.NotificationManager;
import android.content.Context;
import android.view.WindowManager;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowSettings;
import static org.robolectric.Shadows.shadowOf;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class KillGuidanceOverlayTest {

    private Context context;
    private NotificationManager notificationManager;
    private ShadowNotificationManager shadowNotificationManager;
    private KillGuidanceOverlay overlay;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        shadowNotificationManager = shadowOf(notificationManager);
        overlay = new KillGuidanceOverlay(context);
    }

    @Test
    public void testShow_WithOverlayPermission_ShowsFloatingWindow() {
        // Given: The app HAS the SYSTEM_ALERT_WINDOW permission
        ShadowSettings.setCanDrawOverlays(true);

        // When: The detection engine requests the guidance overlay
        overlay.show("com.malware.test", "Test Malware");

        // Then: The layout should be added (which doesn't crash since we use real context)
        // And no notification should be shown
        assertEquals("Notification should NOT be shown when overlay succeeds",
                0, shadowNotificationManager.getAllNotifications().size());
    }

    @Test
    public void testShow_WithoutOverlayPermission_FallsBackToNotification() {
        // Given: The app DOES NOT HAVE the SYSTEM_ALERT_WINDOW permission
        ShadowSettings.setCanDrawOverlays(false);

        // When: The detection engine requests the guidance overlay
        overlay.show("com.malware.test", "Test Malware");

        // Then: It MUST fall back to a high-priority system notification
        assertEquals("Fallback notification MUST be shown",
                1, shadowNotificationManager.getAllNotifications().size());
    }
}
