package com.tacmap.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone
import kotlin.math.abs

/** Pins offline sun/moon times to `testdata/sun_moon_times.json`, the same
 * fixture the iOS suite reads. */
class SunMoonCalculatorTest {
    private val fixture: JsonObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val file = File(dir, "testdata/sun_moon_times.json")
            if (file.exists()) return@lazy Json.parseToJsonElement(file.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("Could not locate testdata/sun_moon_times.json")
    }

    private fun JsonObject.d(key: String): Double = this[key]!!.jsonPrimitive.double

    @Test
    fun eventsMatchSharedFixture() {
        val tolerance = fixture.d("toleranceSeconds")
        for (element in fixture["cases"]!!.jsonArray) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val day = SunMoonCalculator.day(
                latitude = case.d("lat"),
                longitude = case.d("lon"),
                startMillis = Math.round(case.d("windowStartUnix") * 1000),
                endMillis = Math.round(case.d("windowEndUnix") * 1000),
            )
            val actual = mapOf(
                "bmnt" to day.bmnt, "bmct" to day.bmct, "sunrise" to day.sunrise, "sunset" to day.sunset,
                "eect" to day.eect, "eent" to day.eent, "moonrise" to day.moonrise, "moonset" to day.moonset,
            )
            val expected = case["expectedUnix"]!!.jsonObject
            for ((key, millis) in actual) {
                val want = expected[key]!!
                if (want is JsonNull) {
                    assertNull("$name $key", millis)
                } else {
                    val error = abs(millis!! / 1000.0 - want.jsonPrimitive.double)
                    assertTrue("$name $key off by $error s", error <= tolerance)
                }
            }
            assertEquals(name, case.d("moonIllumination"), day.moonIllumination, fixture.d("illuminationTolerance"))
            assertEquals(name, case["moonWaxing"]!!.jsonPrimitive.boolean, day.moonWaxing)
        }
    }

    @Test
    fun localDayFollowsTheDeviceCalendarAcrossDaylightSavingChanges() {
        val zone = TimeZone.getTimeZone("America/New_York")
        // 2026-11-01 12:00 UTC is 07:00 EST on the 25-hour DST-end day.
        val (start, end) = SunMoonCalculator.localDay(1_793_534_400_000L, 0, zone)
        assertEquals(1_793_505_600_000L, start) // 2026-11-01 04:00 UTC = local midnight EDT
        assertEquals(25 * 3_600_000L, end - start)
        val (tomorrow, _) = SunMoonCalculator.localDay(1_793_534_400_000L, 1, zone)
        assertEquals(end, tomorrow)
    }
}
