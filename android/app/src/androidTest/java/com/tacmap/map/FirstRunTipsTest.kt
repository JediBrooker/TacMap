package com.tacmap.map

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.tacmap.app.MainActivity
import com.tacmap.localization.Messages
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The first-run tips appear once on a fresh map, page through to the end,
 * and stay away after that. */
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

        FirstRunTips.reset(context)
        assertTrue(FirstRunTips.shouldShow(context))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val tips = FirstRunTips.tips
            tips.forEachIndexed { index, tip ->
                assertNotNull("Tip ${index + 1} is missing", waitFor(device, By.text(tip.title)))
                val button = if (index == tips.lastIndex) Messages.tipsDone() else Messages.tipsNext()
                waitFor(device, By.text(button)).click()
            }
            assertTrue(
                "Tips should close after the last one",
                device.wait(Until.gone(By.text(tips.last().title)), 5_000),
            )
            assertFalse(FirstRunTips.shouldShow(context))
        } finally {
            scenario.close()
            FirstRunTips.markSeen(context)
        }
    }

    private fun waitFor(device: UiDevice, selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), 30_000)
            ?: throw AssertionError("Timed out waiting for $selector")
}
