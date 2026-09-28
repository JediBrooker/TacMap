package com.tacmap.map

import android.Manifest
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.tacmap.app.MainActivity
import com.tacmap.app.TacticalApp
import com.tacmap.localization.L10n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Night mode must turn everything the app draws red: the map in the activity
 * window, the menu popup and a settings dialog, which both draw in their own
 * windows. Screenshots are sampled on a grid; no sampled pixel may carry
 * noticeable green or blue.
 */
@RunWith(AndroidJUnit4::class)
class NightModeTest {

    @Test
    fun nightModeTurnsMapMenuAndDialogsRed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        val opsec = (context.applicationContext as TacticalApp).opsec
        FirstRunTips.markSeen(context)
        assertTrue(opsec.setNightMode(true))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitFor(device, By.desc(L10n.text("Menu")))
            device.waitForIdle()
            Thread.sleep(2_000)
            assertOnlyRed(instrumentation.uiAutomation.takeScreenshot(), "map")

            waitFor(device, By.desc(L10n.text("Menu"))).click()
            val settings = waitFor(device, By.text(PRIVACY_OPSEC_LABEL))
            Thread.sleep(500)
            assertOnlyRed(instrumentation.uiAutomation.takeScreenshot(), "menu")

            settings.click()
            assertNotNull(waitFor(device, By.text(com.tacmap.localization.Messages.nightModeTitle())))
            Thread.sleep(500)
            assertOnlyRed(instrumentation.uiAutomation.takeScreenshot(), "settings dialog")
        } finally {
            scenario.close()
            opsec.setNightMode(false)
        }
    }

    private fun assertOnlyRed(screenshot: Bitmap?, context: String) {
        assertNotNull("No $context screenshot", screenshot)
        val bitmap = screenshot!!
        var sampled = 0
        var coloured = 0
        var lit = 0
        // Skip the bottom 4% where a gesture handle may be drawn by the system.
        val usableHeight = (bitmap.height * 0.96).toInt()
        val stepY = maxOf(1, usableHeight / 80)
        val stepX = maxOf(1, bitmap.width / 40)
        for (y in 0 until usableHeight step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                sampled++
                if (maxOf(green, blue) > 24) coloured++
                if (red > 24) lit++
            }
        }
        assertEquals("$coloured of $sampled sampled $context pixels are not red", 0, coloured)
        assertTrue("The $context screenshot is almost black", lit > sampled / 200)
    }

    private fun waitFor(device: UiDevice, selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), 30_000)
            ?: throw AssertionError("Timed out waiting for $selector")
}
