package com.tacmap.map

import android.Manifest
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
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
import com.tacmap.localization.Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Night mode must turn everything the app draws red: the map in the activity
 * window, the menu popup and the symbol dialog, which both draw in their own
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
            // UiAutomation.grantRuntimePermission needs API 28; CI also runs API 26.
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
                .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        }
        val opsec = (context.applicationContext as TacticalApp).opsec
        val blockedCaptureBefore = opsec.blockScreenCapture.value
        FirstRunTips.markSeen(context)
        assertTrue(opsec.setNightMode(false))
        // Secure windows screenshot as black; the default blocks capture.
        assertTrue(opsec.setBlockScreenCapture(false))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // Turn it on the way a user does: the moon button on the map.
            waitFor(device, By.desc(Messages.nightModeTurnOn())).click()
            dismissImmersiveConfirmation(device)
            waitFor(device, By.desc(Messages.nightModeTurnOff()))
            assertTrue(opsec.nightMode.value)
            device.waitForIdle()
            Thread.sleep(1_500)
            assertOnlyRed(instrumentation.uiAutomation.takeScreenshot(), "map", device)

            // A popup window: the main menu (its first rows are always on screen).
            waitFor(device, By.desc(L10n.text("Menu"))).click()
            waitFor(device, By.text(L10n.text("Symbology")))
            Thread.sleep(500)
            assertOnlyRed(instrumentation.uiAutomation.takeScreenshot(), "menu", device)
            device.pressBack()
            device.wait(Until.gone(By.text(L10n.text("Symbology"))), 5_000)

            // A dialog window: the symbol editor from the + button.
            waitFor(device, By.desc(L10n.text("Add symbol at crosshair"))).click()
            waitFor(device, By.text(L10n.text("Military Unit"))).click()
            waitFor(device, By.text(L10n.text("New Military Unit")))
            Thread.sleep(500)
            assertOnlyRed(instrumentation.uiAutomation.takeScreenshot(), "symbol dialog", device)
            device.pressBack()
        } finally {
            scenario.close()
            opsec.setNightMode(false)
            opsec.setBlockScreenCapture(blockedCaptureBefore)
        }
    }

    /** Reopening the app with night mode left on must show the map, red. */
    @Test
    fun appRelaunchedInNightModeShowsTheMap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val opsec = (context.applicationContext as TacticalApp).opsec
        val blockedCaptureBefore = opsec.blockScreenCapture.value
        FirstRunTips.markSeen(context)
        assertTrue(opsec.setBlockScreenCapture(false))
        assertTrue(opsec.setNightMode(true))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            dismissImmersiveConfirmation(device)
            waitFor(device, By.desc(Messages.nightModeTurnOff()))
            waitFor(device, By.desc(L10n.text("Menu")))
            device.waitForIdle()
            Thread.sleep(1_500)
            assertOnlyRed(instrumentation.uiAutomation.takeScreenshot(), "relaunched map", device)
        } finally {
            scenario.close()
            opsec.setNightMode(false)
            opsec.setBlockScreenCapture(blockedCaptureBefore)
        }
    }

    /** Some Android versions explain immersive mode the first time the bars hide. */
    private fun dismissImmersiveConfirmation(device: UiDevice) {
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)got it"))), 2_000)?.click()
    }

    private fun assertOnlyRed(screenshot: Bitmap?, context: String, device: UiDevice) {
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
        assertEquals("$coloured of $sampled sampled $context pixels are not red. Screen: ${hierarchy(device)}", 0, coloured)
        assertTrue("The $context screenshot is almost black. Screen: ${hierarchy(device)}", lit > sampled / 200)
    }

    private fun waitFor(device: UiDevice, selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), 30_000)
            ?: throw AssertionError("Timed out waiting for $selector. Screen: ${hierarchy(device)}")

    /** Visible labels UiAutomator sees, on one line so CI logs keep it. */
    private fun hierarchy(device: UiDevice): String {
        val xml = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString(Charsets.UTF_8.name())
        return Regex("""(?:text|content-desc|package)="([^"]+)"""").findAll(xml)
            .map { it.groupValues[1] }
            .distinct()
            .joinToString(" | ")
            .take(3_000)
    }
}
