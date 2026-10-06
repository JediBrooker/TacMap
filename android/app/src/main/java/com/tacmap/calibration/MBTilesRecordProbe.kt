package com.tacmap.calibration

import java.io.File
import java.io.RandomAccessFile

/**
 * s15.2 recordProbe (3.0.3 U2, SEC-M1-ANDROID-OLDSQLITE-OOM). sqlite loads all of a TEXT value
 * before length() or substr(CAST(... AS BLOB)) gets to look at it, and below android 12 there's
 * no sqlite3_limit and no heap cap, so a pack with a metadata name or value of a few hundred MB
 * (or a huge schema statement, parsed at the first statement) got the import or the restore OOM
 * killed. So before sqlite gets to those rows we read the file's own b-tree pages: page headers,
 * cell pointers and each leaf cell's first varint, which is that row's whole record size. Nothing
 * sqlite loads from the row can be bigger. Never a value, an overflow page or a record header,
 * except for the schema's own rows (statistics below), which the walk has already capped.
 * Straight port of the generator's probe_header() / probe_walk() / probe_schema()
 * (scripts/gen_calibration_fixtures.py), pinned by import_limits.json recordProbe.cases. Pure, no SQLite in here
 */
internal object MBTilesRecordProbe {
    const val MAX_SCHEMA_ROWS = 1_000
    const val MAX_SCHEMA_BYTES = 1_048_576L
    const val MAX_METADATA_ROWS = MBTilesStore.MAX_METADATA_ROWS
    // twice the value cap, a row with one value right at the cap still fits
    const val MAX_METADATA_RECORD_BYTES = 2L * MBTilesStore.MAX_VALUE_BYTES
    const val MAX_DEPTH = 20
    const val MAX_PAGES = 4_096

    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    // what ANALYZE names its tables (sqlite_stat1, and 2/3/4 on builds that had them)
    private val STATISTICS_MARK = "sqlite_stat".toByteArray(Charsets.US_ASCII)
    private val CREATE_TABLE = "CREATE TABLE ".toByteArray(Charsets.US_ASCII)
    // body sizes of serial types 0..9, 10 and 11 are reserved
    private val SERIAL_SIZES = intArrayOf(0, 1, 2, 3, 4, 6, 8, 8, 0, 0)

    /**
     * sqlite_master from page 1, before sqlite opens the file. null = fine, else the fixture's reason.
     * PROBE-RT-1: the schema load at the first statement also reads every row of the ANALYZE tables
     * whole (sqlite_stat1, sqlite_stat4), and nothing else bounded those. so each schema row's record
     * gets read, and every row that mentions sqlite_stat has to be a plain table whose b-tree stays
     * under the schema's own caps, all of them walked as one
     */
    fun schema(file: File): String? = RandomAccessFile(file, "r").use { f ->
        val cells = ArrayList<Long>()
        walk(f, longArrayOf(1L), MAX_SCHEMA_ROWS, MAX_SCHEMA_BYTES, MAX_SCHEMA_BYTES, cells) ?: statistics(f, cells)
    }

    /** one table the metadata relation reads, from its sqlite_master rootpage. null = fine */
    fun metadataTable(file: File, root: Long): String? = RandomAccessFile(file, "r").use {
        walk(it, longArrayOf(root), MAX_METADATA_ROWS, MAX_METADATA_RECORD_BYTES, null, null)
    }

    private class Geometry(val size: Int, val usable: Int, val pageCount: Long)

    /** page size, usable size and page count off the 100 byte header, null = header */
    private fun geometry(f: RandomAccessFile): Geometry? {
        val length = f.length()
        if (length < 100) return null
        val head = ByteArray(100)
        f.seek(0)
        f.readFully(head)
        if (!head.copyOf(MAGIC.size).contentEquals(MAGIC)) return null
        val size = be16(head, 16).let { if (it == 1) 65_536 else it }
        if (size < 512 || size > 65_536 || size and (size - 1) != 0) return null
        val usable = size - (head[20].toInt() and 0xFF)
        if (usable < 480) return null
        // schema text gets read as UTF-8 below. sqlite takes the low two bits, 0 is UTF-8 too.
        // admission refuses any other encoding anyway, just later than this
        if (be32(head, 56) and 3L > 1L) return null
        return Geometry(size, usable, length / size)
    }

    /**
     * header, structure, pageType, rows, recordBytes or totalBytes for the first problem in walk
     * order, null if there's none. several roots walk as one: same pages seen, rows and total, each
     * at depth 0 in order. [cells] gets every leaf cell as page shl 16 or offset. throws IOException
     * when the file can't be read, callers refuse on it
     */
    private fun walk(
        f: RandomAccessFile, roots: LongArray, maxRows: Int, maxRecord: Long, maxTotal: Long?, cells: MutableList<Long>?,
    ): String? {
        val g = geometry(f) ?: return "header"
        val size = g.size
        val usable = g.usable
        val page = ByteArray(size)
        val seen = HashSet<Long>()
        val stack = PageStack()
        var rows = 0
        var total = 0L
        for (i in roots.indices.reversed()) stack.push(roots[i], 0)
        while (stack.isNotEmpty()) {
            val depth = stack.topDepth()
            val p = stack.pop()
            if (p < 1 || p > g.pageCount || p in seen || depth >= MAX_DEPTH || seen.size >= MAX_PAGES) return "structure"
            seen += p
            f.seek((p - 1) * size)
            f.readFully(page)
            // page 1 starts with the 100 byte file header, cell offsets still count from the page start
            val h = if (p == 1L) 100 else 0
            val n = be16(page, h + 3)
            when (page[h].toInt() and 0xFF) {
                0x05 -> {
                    val first = h + 12 + 2 * n
                    if (first > usable) return "structure"
                    // every cell checked before going down, same as the reference, so the first problem matches
                    val children = LongArray(n + 1)
                    for (i in 0 until n) {
                        val o = be16(page, h + 12 + 2 * i)
                        if (o < first || o + 4 > usable) return "structure"
                        children[i] = be32(page, o)
                    }
                    children[n] = be32(page, h + 8)
                    // backwards onto the stack so the left most child comes off first (pre-order)
                    for (i in n downTo 0) stack.push(children[i], depth + 1)
                }
                0x0D -> {
                    val first = h + 8 + 2 * n
                    if (first > usable) return "structure"
                    for (i in 0 until n) {
                        val o = be16(page, h + 8 + 2 * i)
                        if (o < first || o >= usable) return "structure"
                        val record = varint(page, o, usable) ?: return "structure"
                        rows++
                        if (rows > maxRows) return "rows"
                        // unsigned: the 9 byte form goes up to 2^64 - 1, which a Long reads as -1
                        if (java.lang.Long.compareUnsigned(record, maxRecord) > 0) return "recordBytes"
                        total += record
                        if (maxTotal != null && total > maxTotal) return "totalBytes"
                        cells?.add((p shl 16) or o.toLong())
                    }
                }
                else -> return "pageType"
            }
        }
        return null
    }

    /**
     * the statistics step over the schema's leaf cells, in walk order: structure if a record can't be
     * read whole, statistics if one that mentions sqlite_stat isn't sqlite's own kind of statistics
     * table, then their b-trees walked as one under the schema caps
     */
    private fun statistics(f: RandomAccessFile, cells: List<Long>): String? {
        val g = geometry(f) ?: return "header"
        val page = ByteArray(g.size)
        var loaded = 0L
        val roots = ArrayList<Long>()
        for (cell in cells) {
            val p = cell ushr 16
            if (p != loaded) {
                f.seek((p - 1) * g.size)
                f.readFully(page)
                loaded = p
            }
            val record = record(f, g, page, (cell and 0xFFFF).toInt()) ?: return "structure"
            if (mentionsStatistics(record)) roots += statisticsRoot(record) ?: return "statistics"
        }
        if (roots.isEmpty()) return null
        return walk(f, roots.toLongArray(), MAX_SCHEMA_ROWS, MAX_SCHEMA_BYTES, MAX_SCHEMA_BYTES, null)
    }

    /**
     * the whole record of the table leaf cell at [at], read like sqlite does: after the size and
     * rowid varints the local part (all of it up to U - 35, else M = (U - 12) * 32 / 255 - 23 and
     * K = M + (size - M) % (U - 4), K if that's <= U - 35 else M), then the overflow chain, U - 4
     * bytes off each page after its 4 byte next pointer. null = structure. schema rows only, the walk
     * already capped them at MAX_SCHEMA_BYTES so an Int's plenty
     */
    private fun record(f: RandomAccessFile, g: Geometry, page: ByteArray, at: Int): ByteArray? {
        val u = g.usable
        val length = varint(page, at, u) ?: return null
        val rowidAt = at + varintLength(page, at)
        varint(page, rowidAt, u) ?: return null
        val start = rowidAt + varintLength(page, rowidAt)
        val total = length.toInt()
        var local = total
        if (total > u - 35) {
            val least = (u - 12) * 32 / 255 - 23
            local = least + (total - least) % (u - 4)
            if (local > u - 35) local = least
        }
        if (start + local + (if (local < total) 4 else 0) > u) return null
        val out = ByteArray(total)
        System.arraycopy(page, start, out, 0, local)
        var filled = local
        var next = if (local < total) be32(page, start + local) else 0L
        val over = ByteArray(g.size)
        while (filled < total) {
            if (next < 1 || next > g.pageCount) return null
            f.seek((next - 1) * g.size)
            f.readFully(over)
            val take = minOf(total - filled, u - 4)
            System.arraycopy(over, 4, out, filled, take)
            filled += take
            next = be32(over, 0)
        }
        return out
    }

    /** sqlite_stat anywhere in the record, ASCII case folded like sqlite compares names */
    private fun mentionsStatistics(r: ByteArray): Boolean {
        outer@ for (i in 0..r.size - STATISTICS_MARK.size) {
            for (j in STATISTICS_MARK.indices) {
                val b = r[i + j].toInt()
                if ((if (b in 'A'.code..'Z'.code) b + 32 else b) != STATISTICS_MARK[j].toInt()) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * rootpage of a schema record that mentions sqlite_stat, or null (statistics) unless it's what sqlite
     * itself writes for those: exactly five columns filling the record exactly (no serial type 10 or 11),
     * rootpage an integer and sql text starting CREATE TABLE. a view or virtual table by that name would
     * run SQL from the file when sqlite reads it
     */
    private fun statisticsRoot(r: ByteArray): Long? {
        val header = varint(r, 0, r.size) ?: return null
        var at = varintLength(r, 0)
        if (java.lang.Long.compareUnsigned(header, at.toLong()) < 0 ||
            java.lang.Long.compareUnsigned(header, r.size.toLong()) > 0) return null
        val end = header.toInt()
        val types = ArrayList<Long>()
        while (at < end) {
            types += varint(r, at, end) ?: return null
            at += varintLength(r, at)
            if (types.size > 5) return null
        }
        if (types.size != 5) return null
        val starts = IntArray(5)
        var body = end.toLong()
        for (i in 0 until 5) {
            val t = types[i]
            val n = when {
                t == 10L || t == 11L -> return null
                java.lang.Long.compareUnsigned(t, 12L) < 0 -> SERIAL_SIZES[t.toInt()].toLong()
                else -> (t - 12) ushr 1
            }
            // too big for the record is too big, however it'd add up
            if (java.lang.Long.compareUnsigned(n, r.size.toLong()) > 0) return null
            starts[i] = body.toInt()
            body += n
            if (body > r.size) return null
        }
        if (body != r.size.toLong()) return null
        val rootType = types[3]
        val sqlType = types[4]
        if (java.lang.Long.compareUnsigned(sqlType, 13L) < 0 || sqlType and 1L == 0L) return null
        val sqlAt = starts[4]
        if (r.size - sqlAt < CREATE_TABLE.size) return null
        for (j in CREATE_TABLE.indices) if (r[sqlAt + j] != CREATE_TABLE[j]) return null
        return when (rootType) {
            8L -> 0L
            9L -> 1L
            in 1L..6L -> {
                var v = if (r[starts[3]] < 0) -1L else 0L
                for (k in 0 until SERIAL_SIZES[rootType.toInt()]) v = (v shl 8) or (r[starts[3] + k].toLong() and 0xFF)
                v
            }
            else -> null
        }
    }

    /** sqlite's varint: up to 8 bytes of 7 bits (high bit = more), a 9th gives all 8. null if it runs into [end] */
    private fun varint(b: ByteArray, at: Int, end: Int): Long? {
        var value = 0L
        for (i in 0 until 9) {
            if (at + i >= end) return null
            val byte = b[at + i].toLong() and 0xFF
            if (i == 8) return (value shl 8) or byte
            value = (value shl 7) or (byte and 0x7F)
            if (byte < 0x80) return value
        }
        return null
    }

    /** how many bytes the varint at [at] takes, once varint() has said it ends in time */
    private fun varintLength(b: ByteArray, at: Int): Int {
        for (i in 0 until 8) if (b[at + i].toInt() and 0x80 == 0) return i + 1
        return 9
    }

    private fun be16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /**
     * pages still to visit and their depth. flat arrays, an interior page can list ~32k children
     * and boxing that many pairs would cost more than the probe is meant to save
     */
    private class PageStack {
        private var pages = LongArray(16)
        private var depths = ByteArray(16)
        private var size = 0

        fun isNotEmpty() = size > 0

        fun push(page: Long, depth: Int) {
            if (size == pages.size) {
                pages = pages.copyOf(size * 2)
                depths = depths.copyOf(size * 2)
            }
            pages[size] = page
            depths[size] = depth.toByte()
            size++
        }

        fun topDepth(): Int = depths[size - 1].toInt()

        fun pop(): Long = pages[--size]
    }
}
