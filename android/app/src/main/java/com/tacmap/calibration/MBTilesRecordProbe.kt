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
 * except for the schema's own rows (read whole since 3.0.4), which the walk has already capped.
 * 3.0.4 (s16.1) adds every index b-tree's key sizes, same idea: a seek mallocs a whole key.
 * Straight port of the generator's probe_header() / probe_walk() / probe_schema() / probe_index_walk()
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
    // s16.1: the most sqlite can be made to load from one key it compares, same as from one metadata row
    const val MAX_INDEX_KEY_BYTES = MAX_METADATA_RECORD_BYTES

    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    // what ANALYZE names its tables (sqlite_stat1, and 2/3/4 on builds that had them)
    private val STATISTICS_MARK = "sqlite_stat".toByteArray(Charsets.US_ASCII)
    private val CREATE_TABLE = "CREATE TABLE ".toByteArray(Charsets.US_ASCII)
    // body sizes of serial types 0..9, 10 and 11 are reserved
    private val SERIAL_SIZES = intArrayOf(0, 1, 2, 3, 4, 6, 8, 8, 0, 0)

    /**
     * sqlite_master from page 1, before sqlite opens the file. null = fine, else the fixture's reason.
     * then every schema row read whole (schemaRows below): the statistics tables and every index b-tree
     * hang off those rows' rootpages
     */
    fun schema(file: File): String? = RandomAccessFile(file, "r").use { f ->
        val cells = ArrayList<Long>()
        walk(f, longArrayOf(1L), MAX_SCHEMA_ROWS, MAX_SCHEMA_BYTES, MAX_SCHEMA_BYTES, cells) ?: schemaRows(f, cells)
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
     * every schema leaf cell in walk order, read whole (s16.1 schemaRecords): structure if it can't be,
     * schemaRecord if it doesn't split the way sqlite writes a row, statistics if it names a statistics
     * table (s16.2) and isn't sqlite's own kind, schemaRecord if it isn't five columns with an integer
     * rootpage and NULL or text sql, structure if that rootpage is past the end. then the statistics
     * tables walked as one under the schema caps, then every index b-tree under the key cap
     */
    private fun schemaRows(f: RandomAccessFile, cells: List<Long>): String? {
        val g = geometry(f) ?: return "header"
        val page = ByteArray(g.size)
        var loaded = 0L
        val statistics = ArrayList<Long>()
        val roots = ArrayList<Long>(cells.size)
        for (cell in cells) {
            val p = cell ushr 16
            if (p != loaded) {
                f.seek((p - 1) * g.size)
                f.readFully(page)
                loaded = p
            }
            val record = record(f, g, page, (cell and 0xFFFF).toInt()) ?: return "structure"
            val row = split(record) ?: return "schemaRecord"
            val root = rootpage(record, row) ?: return if (row.statistics) "statistics" else "schemaRecord"
            if (root < 0 || root > g.pageCount) return "structure"
            if (row.statistics) statistics += root
            roots += root
        }
        if (statistics.isNotEmpty())
            walk(f, statistics.toLongArray(), MAX_SCHEMA_ROWS, MAX_SCHEMA_BYTES, MAX_SCHEMA_BYTES, null)?.let { return it }
        // the page decides, never the type or tbl_name column: sqlite builds each object from its sql and
        // checks neither, and a WITHOUT ROWID table is an index b-tree with no row of its own for that
        val indexes = roots.filter { it >= 1 && indexPage(f, g, it) }
        return if (indexes.isEmpty()) null else indexWalk(f, g, indexes.toLongArray(), MAX_INDEX_KEY_BYTES)
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

    /**
     * what rootpage() needs from a split record: how many columns, columns 3 and 4 (rootpage and sql)
     * as serial type + where their bytes are, and whether any column names a statistics table
     */
    private class Row(
        val columns: Int, val rootType: Long, val rootAt: Int, val sqlType: Long, val sqlAt: Int, val sqlEnd: Int,
        val statistics: Boolean,
    )

    /**
     * a schema record split the way sqlite writes one, null = schemaRecord: the header size varint h with
     * its own length <= h <= the record size, serial type varints each ending by h and filling the header,
     * no 10 or 11, the bodies filling the record exactly. one pass, no list of columns kept: a 1 MiB
     * record can claim a million of them
     */
    private fun split(r: ByteArray): Row? {
        val header = varint(r, 0, r.size) ?: return null
        var at = varintLength(r, 0)
        if (java.lang.Long.compareUnsigned(header, at.toLong()) < 0 ||
            java.lang.Long.compareUnsigned(header, r.size.toLong()) > 0) return null
        val end = header.toInt()
        var body = end
        var columns = 0
        var rootType = 0L
        var rootAt = 0
        var sqlType = 0L
        var sqlAt = 0
        var sqlEnd = 0
        var statistics = false
        while (at < end) {
            val t = varint(r, at, end) ?: return null
            at += varintLength(r, at)
            val n = when {
                t == 10L || t == 11L -> return null
                java.lang.Long.compareUnsigned(t, 12L) < 0 -> SERIAL_SIZES[t.toInt()].toLong()
                else -> (t - 12) ushr 1
            }
            // a body past the record never adds back up to it, so that's the end of it
            if (n > r.size - body) return null
            val start = body
            body += n.toInt()
            if (columns == 3) { rootType = t; rootAt = start }
            if (columns == 4) { sqlType = t; sqlAt = start; sqlEnd = body }
            // text or blob, any of the columns
            if (!statistics && java.lang.Long.compareUnsigned(t, 12L) >= 0) statistics = namesStatistics(r, start, body)
            columns++
        }
        if (body != r.size) return null
        return Row(columns, rootType, rootAt, sqlType, sqlAt, sqlEnd, statistics)
    }

    /**
     * s16.2: r[from until to] holds sqlite_stat (ASCII case folded) and one or more digits as a whole word,
     * no letter, digit or _ right before it or right after the digits. that's every name sqlite parses as
     * one of its statistics tables (unquoted, or between quote marks) plus a bit more, never part of a
     * longer name like sqlite_stat_note or app_sqlite_stats
     */
    private fun namesStatistics(r: ByteArray, from: Int, to: Int): Boolean {
        val mark = STATISTICS_MARK.size
        outer@ for (i in from..to - mark) {
            for (j in 0 until mark) if (fold(r[i + j]) != STATISTICS_MARK[j].toInt()) continue@outer
            var k = i + mark
            while (k < to && r[k] in '0'.code.toByte()..'9'.code.toByte()) k++
            if (k > i + mark && (i == from || !word(r[i - 1])) && (k == to || !word(r[k]))) return true
        }
        return false
    }

    /** A-Z folded to a-z, like sqlite compares names. everything else as is (unsigned) */
    private fun fold(b: Byte): Int = (b.toInt() and 0xFF).let { if (it in 'A'.code..'Z'.code) it + 32 else it }

    /** ASCII letter, digit or _. sqlite's own identifier bytes are more ($, >= 0x80), so this only matches more */
    private fun word(b: Byte): Boolean = fold(b).let { it in 'a'.code..'z'.code || it in '0'.code..'9'.code || it == '_'.code }

    /**
     * the row's rootpage, or null unless it's a row sqlite writes: five columns, rootpage an integer
     * (serial type 1-6 big endian two's complement, 8 = 0, 9 = 1), sql NULL or text. a statistics row
     * also needs text sql starting CREATE TABLE, a view or virtual table by that name would run SQL from
     * the file when sqlite reads it. the caller says statistics or schemaRecord off row.statistics
     */
    private fun rootpage(r: ByteArray, row: Row): Long? {
        if (row.columns != 5) return null
        val t = row.rootType
        if (t !in 1L..6L && t != 8L && t != 9L) return null
        val text = java.lang.Long.compareUnsigned(row.sqlType, 13L) >= 0 && row.sqlType and 1L == 1L
        if (row.statistics) {
            if (!text || row.sqlEnd - row.sqlAt < CREATE_TABLE.size) return null
            for (j in CREATE_TABLE.indices) if (r[row.sqlAt + j] != CREATE_TABLE[j]) return null
        }
        if (row.sqlType != 0L && !text) return null
        return when (t) {
            8L -> 0L
            9L -> 1L
            else -> {
                var v = if (r[row.rootAt] < 0) -1L else 0L
                for (k in 0 until SERIAL_SIZES[t.toInt()]) v = (v shl 8) or (r[row.rootAt + k].toLong() and 0xFF)
                v
            }
        }
    }

    /** b-tree type byte of page [p] (at 100 on page 1) is an index one, 0x02 or 0x0A. p is 1..pageCount */
    private fun indexPage(f: RandomAccessFile, g: Geometry, p: Long): Boolean {
        f.seek((p - 1) * g.size + if (p == 1L) 100 else 0)
        val type = f.read()
        return type == 0x02 || type == 0x0A
    }

    /**
     * s16.1 indexWalk: index b-trees (0x02 interior, 0x0A leaf) from [roots] walked as one, the table walk's
     * pre-order, depth rule and visited-once rule, every key's payload size at most [maxKey]: a leaf cell's
     * first varint, or the one after an interior cell's 4 byte child (a seek compares those too). a page's
     * keys all get checked when it's visited, before its children. no page or row cap, a tiles index has a
     * key per tile and each page is read once, so the file bounds it. never a key's bytes or an overflow
     * page, the payload size counts the overflow and it's what sqlite mallocs.
     * header, structure, pageType or keyBytes, null = fine
     */
    private fun indexWalk(f: RandomAccessFile, g: Geometry, roots: LongArray, maxKey: Long): String? {
        val size = g.size
        val usable = g.usable
        val page = ByteArray(size)
        val seen = PageSet()
        val stack = PageStack()
        for (i in roots.indices.reversed()) stack.push(roots[i], 0)
        while (stack.isNotEmpty()) {
            val depth = stack.topDepth()
            val p = stack.pop()
            if (p < 1 || p > g.pageCount || depth >= MAX_DEPTH || !seen.add(p)) return "structure"
            f.seek((p - 1) * size)
            f.readFully(page)
            val h = if (p == 1L) 100 else 0
            val n = be16(page, h + 3)
            when (page[h].toInt() and 0xFF) {
                0x02 -> {
                    val first = h + 12 + 2 * n
                    if (first > usable) return "structure"
                    for (i in 0 until n) {
                        val o = be16(page, h + 12 + 2 * i)
                        if (o < first || o + 4 > usable) return "structure"
                        val key = varint(page, o + 4, usable) ?: return "structure"
                        if (java.lang.Long.compareUnsigned(key, maxKey) > 0) return "keyBytes"
                    }
                    // all fine, so the children go on backwards (right most first) and the left most comes off first
                    stack.push(be32(page, h + 8), depth + 1)
                    for (i in n - 1 downTo 0) stack.push(be32(page, be16(page, h + 12 + 2 * i)), depth + 1)
                }
                0x0A -> {
                    val first = h + 8 + 2 * n
                    if (first > usable) return "structure"
                    for (i in 0 until n) {
                        val o = be16(page, h + 8 + 2 * i)
                        if (o < first || o >= usable) return "structure"
                        val key = varint(page, o, usable) ?: return "structure"
                        if (java.lang.Long.compareUnsigned(key, maxKey) > 0) return "keyBytes"
                    }
                }
                else -> return "pageType"
            }
        }
        return null
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

    /**
     * pages the index walk has seen, a bit each, grown to the highest one so far. an index can run to
     * millions of pages, a HashSet of boxed Longs would be ~50 bytes a page, this is 1 MiB for a 4 GiB
     * pack of 512 byte pages. page numbers are already 1..pageCount here
     */
    private class PageSet {
        private var words = LongArray(64)

        /** false if [p] was there already */
        fun add(p: Long): Boolean {
            val w = (p ushr 6).toInt()
            if (w >= words.size) words = words.copyOf(maxOf(w + 1, words.size * 2))
            val bit = 1L shl (p and 63).toInt()
            if (words[w] and bit != 0L) return false
            words[w] = words[w] or bit
            return true
        }
    }
}
