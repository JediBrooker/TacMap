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
 * sqlite loads from the row can be bigger. Never a value, an overflow page or a record header.
 * Straight port of the generator's probe_header() / probe_walk() (scripts/gen_calibration_fixtures.py),
 * pinned by import_limits.json recordProbe.cases. Pure, no SQLite in here
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

    /** sqlite_master from page 1, before sqlite opens the file. null = fine, else the fixture's reason */
    fun schema(file: File): String? = walk(file, 1L, MAX_SCHEMA_ROWS, MAX_SCHEMA_BYTES, MAX_SCHEMA_BYTES)

    /** one table the metadata relation reads, from its sqlite_master rootpage. null = fine */
    fun metadataTable(file: File, root: Long): String? =
        walk(file, root, MAX_METADATA_ROWS, MAX_METADATA_RECORD_BYTES, null)

    /**
     * header, structure, pageType, rows, recordBytes or totalBytes for the first problem in walk
     * order, null if there's none. throws IOException when the file can't be read, callers refuse on it
     */
    fun walk(file: File, root: Long, maxRows: Int, maxRecord: Long, maxTotal: Long?): String? =
        RandomAccessFile(file, "r").use { walk(it, root, maxRows, maxRecord, maxTotal) }

    private fun walk(f: RandomAccessFile, root: Long, maxRows: Int, maxRecord: Long, maxTotal: Long?): String? {
        val length = f.length()
        if (length < 100) return "header"
        val head = ByteArray(100)
        f.seek(0)
        f.readFully(head)
        if (!head.copyOf(MAGIC.size).contentEquals(MAGIC)) return "header"
        val size = be16(head, 16).let { if (it == 1) 65_536 else it }
        if (size < 512 || size > 65_536 || size and (size - 1) != 0) return "header"
        val usable = size - (head[20].toInt() and 0xFF)
        if (usable < 480) return "header"
        val pageCount = length / size

        val page = ByteArray(size)
        val seen = HashSet<Long>()
        val stack = PageStack()
        var rows = 0
        var total = 0L
        stack.push(root, 0)
        while (stack.isNotEmpty()) {
            val depth = stack.topDepth()
            val p = stack.pop()
            if (p < 1 || p > pageCount || p in seen || depth >= MAX_DEPTH || seen.size >= MAX_PAGES) return "structure"
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
