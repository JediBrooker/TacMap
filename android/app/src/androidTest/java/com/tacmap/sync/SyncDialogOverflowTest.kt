package com.tacmap.sync

import android.Manifest
import android.graphics.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tacmap.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncDialogOverflowTest {

    @Test
    fun identitySwitchesRemainSeparateAndReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)

        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitFor(device, By.desc("Menu")).click()
            waitFor(device, By.text("Unit Sync")).click()

            val scrollView = waitFor(device, By.clazz("android.widget.ScrollView"))
            var attempts = 0
            var moved = true
            while (moved && attempts < 10) {
                moved = scrollView.scroll(Direction.DOWN, 0.9f, 1_000)
                attempts += 1
            }

            val headquarters = waitFor(device, By.text("Headquarters"))
            val shareLocation = waitFor(device, By.text("Share my location"))
            val scrollBounds = scrollView.visibleBounds
            val switches = device.findObjects(By.checkable(true))
                .filter { Rect.intersects(scrollBounds, it.visibleBounds) }
                .sortedBy { it.visibleBounds.top }

            assertEquals("Expected the two identity switches", 2, switches.size)
            assertTrue(
                "Headquarters and Share my location switches overlap",
                switches[0].visibleBounds.bottom <= switches[1].visibleBounds.top,
            )
            assertTrue(
                "Headquarters label is not aligned with the first switch",
                headquarters.visibleBounds.centerY() in switches[0].visibleBounds.top..switches[0].visibleBounds.bottom,
            )
            assertTrue(
                "Share my location label is not aligned with the second switch",
                shareLocation.visibleBounds.centerY() in switches[1].visibleBounds.top..switches[1].visibleBounds.bottom,
            )

            val done = waitFor(device, By.text("Done"))
            assertTrue(
                "Done should remain pinned below the scrolling content",
                done.visibleBounds.top >= scrollBounds.bottom,
            )
        } finally {
            scenario.close()
        }
    }

    private fun waitFor(
        device: UiDevice,
        selector: androidx.test.uiautomator.BySelector,
        timeoutMs: Long = 10_000L,
    ) = requireNotNull(device.wait(Until.findObject(selector), timeoutMs)) {
        "Timed out waiting for $selector"
    }
}
