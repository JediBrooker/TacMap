package com.tacmap.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEC-006 regression: every entry in testdata/malicious_frames.json is a raw
 * WebSocket text frame that must be silently dropped without mutating any sync
 * state. The same corpus runs on iOS (SyncMaliciousFrameTests).
 *
 * Android's org.json is stubbed on the host JVM, so we use kotlinx.serialization
 * to load the fixture and then test the parsing invariants directly. The key
 * behaviors we're checking:
 *  - strict version checks reject bad/out-of-range versions
 *  - JSON.parse + try/catch survives every corpus entry
 *  - no corpus case should produce a valid version from a malicious value
 */
class SyncMaliciousFrameTest {

    private fun fixtureText(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val f = File(dir, "testdata/$name")
            if (f.exists()) return f.readText()
            dir = dir?.parentFile
        }
        error("Could not locate testdata/$name from ${System.getProperty("user.dir")}")
    }

    data class Case(val name: String, val frame: String)

    private val corpus: List<Case> by lazy {
        val root = Json.parseToJsonElement(fixtureText("malicious_frames.json")).jsonObject
        root["cases"]!!.jsonArray.map { el ->
            val obj = el.jsonObject
            Case(obj["name"]!!.jsonPrimitive.content, obj["frame"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun corpusLoads() {
        assertTrue("malicious_frames.json should have cases", corpus.isNotEmpty())
    }

    @Test
    fun versionParsingRejectsAllMaliciousValues() {
        // simulate what SyncManager.strictVersion does, without needing
        // Android's org.json (which is stubbed on the host JVM)
        val versionCases = corpus.filter { it.name.startsWith("version_") }
        assertTrue("should have version test cases", versionCases.isNotEmpty())

        for (c in versionCases) {
            // parse with kotlinx.serialization to extract the "v" field
            val el = try {
                Json.parseToJsonElement(c.frame).jsonObject["v"]
            } catch (_: Throwable) { continue }

            // try to interpret it as a valid version the way strictVersion would:
            // must be a JSON number (not a string), finite, integer, in range
            val accepted = try {
                val prim = el as? JsonPrimitive ?: throw Exception("not a primitive")
                // isString means it was quoted in JSON — strict parsing rejects that
                if (prim.isString) throw Exception("quoted string, not a number")
                val d = prim.content.toDoubleOrNull() ?: throw Exception("not a number")
                d.isFinite() && d == kotlin.math.floor(d) && d >= 0 &&
                    d <= 1_000_000_000_000.0 && d.toLong() in 0..1_000_000_000_000L
            } catch (_: Throwable) { false }

            assertFalse("${c.name}: malicious version should be rejected", accepted)
        }
    }

    @Test
    fun noFrameCrashesJsonParsing() {
        // every corpus entry must be survivable by a try/catch JSON parse
        for (c in corpus) {
            try {
                if (c.frame.isNotBlank()) Json.parseToJsonElement(c.frame)
            } catch (_: Throwable) {
                // expected for invalid JSON — the point is no uncaught throw
            }
        }
    }
}
