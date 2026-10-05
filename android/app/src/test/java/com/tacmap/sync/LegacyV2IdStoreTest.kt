package com.tacmap.sync

import com.tacmap.util.SealedEnvelope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The sealed per-room store behind the v2 casing rule (plans/04 section 16, 3.0.1). */
class LegacyV2IdStoreTest {
    private val dir: File = Files.createTempDirectory("v2-ids").toFile()
    private val roomId = "q3w8x2kz5r9m1n4p7t0v6y3b8c2d5f1g9h4j7k0l3m6"
    private val otherRoomId = "z9y8x7w6v5u4t3s2r1q0p9o8n7m6l5k4j3i2h1g0f9e"
    private val upper = "3F2A1B4C-0D5E-4F60-8A7B-9C8D7E6F5A4B"
    private val key = upper.lowercase()

    @Before fun setUp() = SyncHarness.installStoreKey()

    @After fun tearDown() {
        SyncHarness.restoreStoreKey()
        dir.deleteRecursively()
    }

    private fun loaded(room: String = roomId) = LegacyV2IdStore(dir, room).apply { load() }

    private fun dataFile(): File = dir.listFiles()!!.single { !it.name.startsWith(".") && it.name.endsWith(".json") }

    @Test
    fun learnedIdsRoundTripUnderAnOpaqueSealedName() {
        val store = LegacyV2IdStore(dir, roomId)
        store.learn(upper)
        store.learn(key) // lowercase later never replaces it
        store.learn("aaaaaaaa-0000-4000-8000-000000000001") // nothing to remember
        store.learn("Bbbbbbbb-0000-4000-8000-000000000002") // mixed case kept as is
        store.learn("3F2A1B4C0D5E4F608A7B9C8D7E6F5A4B") // not canonical
        assertEquals(2, store.count)
        val text = store.takePendingWrite()!!
        assertNull("one write per batch", store.takePendingWrite())
        assertTrue(store.write(text))

        assertTrue(dir.listFiles()!!.none { roomId in it.name })
        val bytes = dataFile().readBytes()
        assertTrue(SealedEnvelope.isSealedFile(bytes))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("3F2A1B4C"))

        val reloaded = loaded()
        assertEquals(upper, reloaded.remembered(key))
        assertEquals("Bbbbbbbb-0000-4000-8000-000000000002", reloaded.remembered("bbbbbbbb-0000-4000-8000-000000000002"))
        assertNull(reloaded.remembered("aaaaaaaa-0000-4000-8000-000000000001"))
        assertNull("loading isnt learning", reloaded.takePendingWrite())
        assertEquals(0, loaded(otherRoomId).count)
    }

    @Test
    fun learningStopsAtTenThousandEntries() {
        // trailing A so every one of them carries an uppercase letter
        fun id(i: Int) = "%08X-0000-4000-8000-00000000000A".format(i)
        val store = LegacyV2IdStore(dir, roomId)
        repeat(LegacyV2IdStore.MAX_ENTRIES + 5) { store.learn(id(it)) }
        assertEquals(10_000, store.count)
        assertEquals(id(9_999), store.remembered(id(9_999).lowercase()))
        assertNull(store.remembered(id(10_000).lowercase()))
        assertTrue(store.write(store.takePendingWrite()!!))
        assertEquals(10_000, loaded().count)
    }

    @Test
    fun aPlaintextOrDamagedFileLoadsEmptyAndTheNextWriteReplacesIt() {
        val store = LegacyV2IdStore(dir, roomId)
        store.learn(upper)
        assertTrue(store.write(store.takePendingWrite()!!))
        val file = dataFile()

        file.writeText("""{"version":1,"ids":["$upper"]}""")
        assertEquals("a bare file was never ours", 0, loaded().count)

        assertTrue(store.write("""{"version":1,"ids":["$upper"]}"""))
        val sealed = file.readBytes()
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        file.writeBytes(sealed)
        val afterDamage = loaded()
        assertEquals(0, afterDamage.count)

        afterDamage.learn(upper)
        assertTrue(afterDamage.write(afterDamage.takePendingWrite()!!))
        assertEquals(upper, loaded().remembered(key))
    }
}
