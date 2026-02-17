package com.dearmoon.shield;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class RobolectricCheckTest {
    @Test
    public void testRobolectricWorks() {
        System.out.println("✅ Robolectric setup is working");
        assertTrue(true);
    }
}
