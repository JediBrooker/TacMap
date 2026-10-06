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
        assertTrue("only ${cases.size} cases", cases.size >= 51)
        val images = cases.filter { it["packBase64"] != null }
        assertTrue("only ${images.size} page images", images.size >= 40)
        // the 9 byte varint is 2^64 - 1, a signed compare would let it through as -1
        assertTrue(images.any { it["id"]!!.jsonPrimitive.content == "nineByteVarint" })
        // PROBE-RT-1: the ANALYZE tables, one of them only findable on its overflow page
        assertTrue(images.any { it["id"]!!.jsonPrimitive.content == "statisticsMarkOnOverflowPage" })
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
        assertEquals(setOf("header", "structure", "pageType", "rows", "recordBytes", "totalBytes", "statistics"), reasons)
    }

    @Test
    fun aHugeMetadataValueIsJudgedOffItsRecordSizeWithoutReadingIt() {
        // the OOM pack's shape: a sane schema and one metadata row claiming 300 MB. the file really is
        // that long (sparse, nothing's written past page 2) and the probe only ever reads two pages
        val size = 4096
        val file = tmp.newFile("huge.mbtiles")
        RandomAccessFile(file, "rw").use { f ->
            // page 1: the file header, then sqlite_master's leaf header at 100 with one small schema row
            f.write(leafPage(size, listOf(60L), headerAt = 100, withRecords = true).also { sqliteHeader(size).copyInto(it) })
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

    @Test
    fun aHugeStatisticsRowIsJudgedOffItsRecordSizeBeforeSqliteOpensTheFile() {
        // PROBE-RT-1: the red team's pack, a sane schema plus sqlite_stat1 with one 150 MB row. sqlite reads
        // that whole with the schema at the first statement, and the first probe only walked sqlite_master,
        // so it said fine. the file really is that long (sparse) and the probe reads three pages
        val size = 4096
        val file = tmp.newFile("stat1.mbtiles")
        val statRow = schemaRecord("table", "sqlite_stat1", "sqlite_stat1", 2L, "CREATE TABLE sqlite_stat1(tbl,idx,stat)")
        RandomAccessFile(file, "rw").use { f ->
            f.write(cellPage(size, listOf(statRow), headerAt = 100).also { sqliteHeader(size).copyInto(it) })
            f.write(leafPage(size, listOf(20L, 150_000_000L)))
            f.setLength(2L * size + 150_000_000L)
        }
        assertEquals("recordBytes", MBTilesRecordProbe.schema(file))
        // ANALYZE's usual few bytes a row are fine
        RandomAccessFile(file, "rw").use { f ->
            f.seek(size.toLong())
            f.write(leafPage(size, listOf(20L, 40L)))
        }
        assertNull(MBTilesRecordProbe.schema(file))
        // and the same row as a view would run sql from the file when sqlite reads it
        val view = schemaRecord("view", "sqlite_stat1", "sqlite_stat1", 0L,
            "CREATE VIEW sqlite_stat1(tbl,idx,stat) AS SELECT 'tiles', NULL, hex(zeroblob(400000000))")
        RandomAccessFile(file, "rw").use { f -> f.write(cellPage(size, listOf(view), headerAt = 100).also { sqliteHeader(size).copyInto(it) }) }
        assertEquals("statistics", MBTilesRecordProbe.schema(file))
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

    /**
     * a table leaf page, b-tree header at [headerAt], one cell per record size packed at the end. offsets from
     * the page start. [withRecords] puts that many zero bytes in too, the schema's rows get read whole
     */
    private fun leafPage(size: Int, records: List<Long>, headerAt: Int = 0, withRecords: Boolean = false): ByteArray =
        cellPage(size, records.map { if (withRecords) ByteArray(it.toInt()) else null }, headerAt, records)

    /** the same with real records (the sizes come from them), or with sizes only where a record's null */
    private fun cellPage(size: Int, bodies: List<ByteArray?>, headerAt: Int = 0, sizes: List<Long>? = null): ByteArray {
        val page = ByteArray(size)
        page[headerAt] = 0x0D
        page[headerAt + 3] = (bodies.size shr 8).toByte()
        page[headerAt + 4] = bodies.size.toByte()
        var at = size
        bodies.forEachIndexed { i, body ->
            val cell = varint(sizes?.get(i) ?: body!!.size.toLong()) + varint(i + 1L) + (body ?: ByteArray(0))
            at -= cell.size
            cell.copyInto(page, at)
            page[headerAt + 8 + 2 * i] = (at shr 8).toByte()
            page[headerAt + 9 + 2 * i] = at.toByte()
        }
        return page
    }

    /** a real record: header (its own size, a serial type per column) then the bodies. Long or String columns */
    private fun schemaRecord(vararg columns: Any): ByteArray {
        val types = ArrayList<Long>()
        var body = ByteArray(0)
        for (c in columns) when (c) {
            is Long -> { types += 4L; body += ByteArray(4) { i -> (c shr (24 - 8 * i)).toByte() } }
            else -> { val text = c.toString().toByteArray(); types += 13L + 2 * text.size; body += text }
        }
        val head = types.fold(ByteArray(0)) { acc, t -> acc + varint(t) }
        return varint(head.size + 1L) + head + body
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
