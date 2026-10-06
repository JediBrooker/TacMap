package com.tacmap.calibration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

/**
 * import_limits.json mbtilesMetadataAdmission recordProbe (s15.2): the constants and the hand made page
 * images, which only the probe reads. the sql[] rows need sqlite to build, so they run instrumented
 * (MBTilesLifecycleInstrumentedTest, which runs these again on the device too)
 */
class MBTilesRecordProbeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val admission = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText())
        .jsonObject["mbtilesMetadataAdmission"]!!.jsonObject
    private val probe = admission["recordProbe"]!!.jsonObject

    @Test
    fun capsAreTheSharedOnes() {
        assertEquals(probe.i("maxSchemaRows"), MBTilesRecordProbe.MAX_SCHEMA_ROWS)
        assertEquals(probe.l("maxSchemaBytes"), MBTilesRecordProbe.MAX_SCHEMA_BYTES)
        assertEquals(probe.i("maxMetadataRows"), MBTilesRecordProbe.MAX_METADATA_ROWS)
        assertEquals(probe.l("maxMetadataRecordBytes"), MBTilesRecordProbe.MAX_METADATA_RECORD_BYTES)
        assertEquals(probe.i("maxDepth"), MBTilesRecordProbe.MAX_DEPTH)
        assertEquals(probe.i("maxPages"), MBTilesRecordProbe.MAX_PAGES)
        // the admission's own 64 row cap, and a record that holds two values at the value cap
        assertEquals(admission.i("maxRows"), MBTilesRecordProbe.MAX_METADATA_ROWS)
        assertEquals(2 * admission["connection"]!!.jsonObject.l("maxValueBytes"), MBTilesRecordProbe.MAX_METADATA_RECORD_BYTES)
    }

    @Test
    fun everyHandMadePageImageGetsTheSharedVerdict() {
        val cases = probe["cases"]!!.jsonArray.map { it.jsonObject }
        // a generator change that drops rows shouldn't pass by testing less
        assertTrue("only ${cases.size} cases", cases.size >= 34)
        val images = cases.filter { it["packBase64"] != null }
        assertTrue("only ${images.size} page images", images.size >= 26)
        // the 9 byte varint is 2^64 - 1, a signed compare would let it through as -1
        assertTrue(images.any { it["id"]!!.jsonPrimitive.content == "nineByteVarint" })
        val reasons = HashSet<String>()
        for (case in images) {
            val id = case["id"]!!.jsonPrimitive.content
            val file = tmp.newFile("$id.mbtiles")
            file.writeBytes(Base64.getDecoder().decode(case["packBase64"]!!.jsonPrimitive.content))
            val schema = case["schema"]!!.jsonObject.reason()
            assertEquals("$id schema", schema, MBTilesRecordProbe.schema(file))
            schema?.let { reasons += it }
            for (table in case["metadataTables"]!!.jsonArray.map { it.jsonObject }) {
                val root = table["root"]!!.jsonPrimitive.long
                val want = table["expect"]!!.jsonObject.reason()
                assertEquals("$id root $root", want, MBTilesRecordProbe.metadataTable(file, root))
                want?.let { reasons += it }
            }
        }
        assertEquals(setOf("header", "structure", "pageType", "rows", "recordBytes", "totalBytes"), reasons)
    }

    @Test
    fun aHugeMetadataValueIsJudgedOffItsRecordSizeWithoutReadingIt() {
        // the OOM pack's shape: a sane schema and one metadata row claiming 300 MB. the file really is
        // that long (sparse, nothing's written past page 2) and the probe only ever reads two pages
        val size = 4096
        val file = tmp.newFile("huge.mbtiles")
        RandomAccessFile(file, "rw").use { f ->
            // page 1: the file header, then sqlite_master's leaf header at 100 with one small schema row
            f.write(leafPage(size, listOf(60L), headerAt = 100).also { sqliteHeader(size).copyInto(it) })
            f.write(leafPage(size, listOf(300_000_000L)))
            f.setLength(2L * size + 300_000_000L)
        }
        assertNull(MBTilesRecordProbe.schema(file))
        assertEquals("recordBytes", MBTilesRecordProbe.metadataTable(file, 2))
        // the same row a byte under the cap is fine, it's the size alone that decides
        RandomAccessFile(file, "rw").use { f ->
            f.seek(size.toLong())
            f.write(leafPage(size, listOf(MBTilesRecordProbe.MAX_METADATA_RECORD_BYTES)))
        }
        assertNull(MBTilesRecordProbe.metadataTable(file, 2))
    }

    @Test(expected = java.io.IOException::class)
    fun aFileThatCantBeReadThrowsSoTheOpenRefusesIt() {
        MBTilesRecordProbe.schema(File(tmp.root, "gone.mbtiles"))
    }

    private fun JsonObject.reason(): String? =
        if (this["ok"]!!.jsonPrimitive.boolean) null else this["reason"]!!.jsonPrimitive.content

    private fun JsonObject.i(k: String): Int = this[k]!!.jsonPrimitive.int
    private fun JsonObject.l(k: String): Long = this[k]!!.jsonPrimitive.long

    /** the 100 byte file header, only what the probe looks at filled in */
    private fun sqliteHeader(pageSize: Int): ByteArray = ByteArray(100).also { h ->
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(h)
        h[16] = (pageSize shr 8).toByte()
        h[17] = pageSize.toByte()
        h[18] = 1
        h[19] = 1
    }

    /** a table leaf page, b-tree header at [headerAt], one cell per record size packed at the end. offsets from the page start */
    private fun leafPage(size: Int, records: List<Long>, headerAt: Int = 0): ByteArray {
        val page = ByteArray(size)
        page[headerAt] = 0x0D
        page[headerAt + 3] = (records.size shr 8).toByte()
        page[headerAt + 4] = records.size.toByte()
        var at = size
        records.forEachIndexed { i, record ->
            val cell = varint(record) + varint(i + 1L)
            at -= cell.size
            cell.copyInto(page, at)
            page[headerAt + 8 + 2 * i] = (at shr 8).toByte()
            page[headerAt + 9 + 2 * i] = at.toByte()
        }
        return page
    }

    /** sqlite's varint for values under 2^56 */
    private fun varint(value: Long): ByteArray {
        val groups = ArrayList<Int>()
        var v = value
        do {
            groups.add(0, (v and 0x7F).toInt())
            v = v ushr 7
        } while (v != 0L)
        return ByteArray(groups.size) { i -> (groups[i] or if (i < groups.size - 1) 0x80 else 0).toByte() }
    }
}
