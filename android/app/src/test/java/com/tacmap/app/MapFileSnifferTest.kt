package com.tacmap.app

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapFileSnifferTest {
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test fun pdfHeaderIsPdf() {
        assertEquals(DocumentImportKind.PDF, MapFileSniffer.kindOf(ascii("%PDF-1.7\n%âãÏÓ")))
        // header alone, nothing after the magic
        assertEquals(DocumentImportKind.PDF, MapFileSniffer.kindOf(ascii("%PDF")))
    }

    @Test fun sqliteHeaderIsMbtiles() {
        assertEquals(DocumentImportKind.MBTILES, MapFileSniffer.kindOf(ascii("SQLite format 3\u0000") + ByteArray(84)))
        assertEquals(DocumentImportKind.MBTILES, MapFileSniffer.kindOf(ascii("SQLite format 3\u0000")))
    }

    @Test fun emptyAndShortHeadersAreUnknown() {
        assertNull(MapFileSniffer.kindOf(ByteArray(0)))
        assertNull(MapFileSniffer.kindOf(ascii("%PD")))
        // the sqlite magic with its trailing NUL missing is not sqlite
        assertNull(MapFileSniffer.kindOf(ascii("SQLite format 3")))
    }

    @Test fun garbageIsUnknown() {
        assertNull(MapFileSniffer.kindOf(ascii("PK\u0003\u0004 a zip, kmz maybe")))
        assertNull(MapFileSniffer.kindOf(ascii("{\"type\":\"FeatureCollection\"}")))
        assertNull(MapFileSniffer.kindOf(ascii("<?xml version=\"1.0\"?>")))
        assertNull(MapFileSniffer.kindOf(ByteArray(16) { 0xFF.toByte() }))
        // close but not the same bytes
        assertNull(MapFileSniffer.kindOf(ascii("%pdf-1.4")))
    }

    @Test fun streamIsOnlyReadForItsHeader() {
        val pdf = ascii("%PDF-1.4\n") + ByteArray(4096) { 'x'.code.toByte() }
        assertEquals(DocumentImportKind.PDF, MapFileSniffer.kindOf(ByteArrayInputStream(pdf)))
        assertNull(MapFileSniffer.kindOf(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun shortReadsFromTheProviderAreStitchedTogether() {
        // a provider that hands out one byte per read() call, the way some content streams do
        val bytes = ascii("SQLite format 3\u0000more")
        val trickle = object : InputStream() {
            var at = 0
            override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val v = read()
                if (v < 0) return -1
                b[off] = v.toByte()
                return 1
            }
        }
        assertArrayEquals(bytes.copyOf(MapFileSniffer.HEADER_BYTES), MapFileSniffer.readHeader(trickle))
        assertEquals(DocumentImportKind.MBTILES, MapFileSniffer.kindOf(ByteArrayInputStream(bytes)))
    }
}
