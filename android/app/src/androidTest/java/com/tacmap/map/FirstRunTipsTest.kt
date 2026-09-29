package com.tacmap.map

import android.Manifest
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
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
import com.tacmap.localization.Messages
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The guided tour appears once on a fresh map, can step back, pages through
 * to the end, and stays away after that. */
@RunWith(AndroidJUnit4::class)
class FirstRunTipsTest {

    @Test
    fun tipsShowOnceAndPageToTheEnd() {
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
        // Secure windows screenshot as black; the default blocks capture.
        assertTrue(opsec.setBlockScreenCapture(false))
        shell(instrumentation, "rm -rf $SCREENSHOT_DIR")
        shell(instrumentation, "mkdir -p $SCREENSHOT_DIR")

        FirstRunTips.reset(context)
        assertTrue(FirstRunTips.shouldShow(context))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val steps = FirstRunTips.steps
            steps.forEachIndexed { index, step ->
                assertNotNull("Step ${index + 1} is missing", waitFor(device, By.text(step.title)))
                Thread.sleep(800) // let the spotlight settle
                shell(instrumentation, "screencap -p $SCREENSHOT_DIR/step-${index + 1}.png")
                if (index == 2) {
                    // Back returns to the previous step; Next comes back.
                    waitFor(device, By.text(Messages.tourBack())).click()
                    assertNotNull("Back did not return", waitFor(device, By.text(steps[1].title)))
                    waitFor(device, By.text(Messages.tipsNext())).click()
                    waitFor(device, By.text(step.title))
                }
                val button = if (index == steps.lastIndex) Messages.tipsDone() else Messages.tipsNext()
                waitFor(device, By.text(button)).click()
            }
            assertTrue(
                "The tour should close after the last step",
                device.wait(Until.gone(By.text(steps.last().title)), 5_000),
            )
            assertFalse(FirstRunTips.shouldShow(context))
        } finally {
            scenario.close()
            FirstRunTips.markSeen(context)
            opsec.setBlockScreenCapture(blockedCaptureBefore)
        }
    }

    /** Runs as the shell user, which can write the screenshot folder CI pulls. */
    private fun shell(instrumentation: android.app.Instrumentation, command: String) {
        instrumentation.uiAutomation.executeShellCommand(command)
            .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
    }

    private companion object {
        const val SCREENSHOT_DIR = "/data/local/tmp/tacmap-tour"
    }

    private fun waitFor(device: UiDevice, selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), 30_000)
            ?: throw AssertionError("Timed out waiting for $selector. Screen: ${hierarchy(device)}")

    /** Visible labels UiAutomator sees, on one line so CI logs keep it. */
    private fun hierarchy(device: UiDevice): String {
        val xml = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString(Charsets.UTF_8.name())
        return Regex("""(?:text|content-desc)="([^"]+)"[^>]*bounds="([^"]+)"""").findAll(xml)
            .map { "${it.groupValues[1]}@${it.groupValues[2]}" }
            .distinct()
            .joinToString(" | ")
            .take(3_000)
    }
}
