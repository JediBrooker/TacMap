package com.tacmap.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportHardeningTest {

    @Test fun streamingKmlParsesPointAndLineWithoutDom() {
        val xml = """<kml><Document><name>Ops</name>
            <Placemark><name>RV</name><Point><coordinates>151.2,-33.8,12</coordinates></Point></Placemark>
            <Placemark><LineString><coordinates>151.2,-33.8 151.3,-33.9</coordinates></LineString></Placemark>
        </Document></kml>""".trimIndent()
        val parsed = KmlImporter.parseStream(ByteArrayInputStream(xml.toByteArray()), emptyList(), "fallback")
        assertTrue(parsed.waypoints.size == 1)
        assertTrue(parsed.drawings.size == 1)
        assertTrue(parsed.newLayers.single().name == "Ops")
    }

    @Test fun externalKmlAndKmzWidthsConvertPortableDpToRendererPixels() {
        val xml = """<kml><Placemark><LineString><coordinates>151.2,-33.8 151.3,-33.9</coordinates></LineString></Placemark></kml>"""

        val kml = KmlImporter.parseStream(
            ByteArrayInputStream(xml.toByteArray()),
            emptyList(),
            "fallback",
            density = 2.5f,
        )
        assertEquals(7.5f, kml.drawings.single().strokeWidth, 0.001f)

        val zipped = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("doc.kml"))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val kmz = KmlImporter.parseStream(
            ByteArrayInputStream(zipped),
            emptyList(),
            "fallback",
            density = 3f,
        )
        assertEquals(9f, kmz.drawings.single().strokeWidth, 0.001f)
    }

    @Test fun kmlParserDefaultDensityPreservesLegacyCallSiteWidth() {
        val xml = """<kml><Placemark><LineString><coordinates>151.2,-33.8 151.3,-33.9</coordinates></LineString></Placemark></kml>"""

        val parsed = KmlImporter.parse(xml, emptyList(), "fallback")

        assertEquals(8f, parsed.drawings.single().strokeWidth, 0.001f)
    }

    @Test fun kmlRejectsDoctypeAndExternalEntities() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE kml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <kml><Placemark><name>&xxe;</name><Point><coordinates>0,0</coordinates></Point></Placemark></kml>
        """.trimIndent()
        val failure = runCatching { KmlImporter.parse(xml, emptyList(), "fallback") }.exceptionOrNull()
        assertTrue(failure is KmlImporter.ImportException)
    }

    @Test fun kmlRejectsOutOfRangeCoordinates() {
        val xml = """<kml><Placemark><Point><coordinates>181,91</coordinates></Point></Placemark></kml>"""
        val failure = runCatching { KmlImporter.parse(xml, emptyList(), "fallback") }.exceptionOrNull()
        assertTrue(failure is KmlImporter.ImportException)
    }

    @Test fun kmzRejectsExcessiveInflation() {
        val zipped = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("doc.kml"))
                zip.write(ByteArray(8 * 1024 * 1024 + 1) { 'A'.code.toByte() })
                zip.closeEntry()
            }
        }.toByteArray()
        val failure = runCatching {
            KmlImporter.parseStream(ByteArrayInputStream(zipped), emptyList(), "fallback")
        }.exceptionOrNull()
        assertTrue(failure is KmlImporter.ImportException)
    }

    @Test fun geoJsonRejectsExcessiveNestingBeforeParsing() {
        val nested = "[".repeat(65) + "0" + "]".repeat(65)
        val failure = runCatching { GeoJsonImporter.parse(nested, emptyList(), "fallback") }.exceptionOrNull()
        assertTrue(failure is GeoJsonImporter.ImportException)
    }

    @Test fun geoJsonStreamRejectsMoreThanEightMiB() {
        val input = ByteArrayInputStream(ByteArray(8 * 1024 * 1024 + 1) { ' '.code.toByte() })
        val failure = runCatching { GeoJsonImporter.parseStream(input, emptyList(), "fallback") }.exceptionOrNull()
        assertTrue(failure is GeoJsonImporter.ImportException)
    }

    @Test fun genericGeoJsonParserNeverMintsMissingSyncIdentity() {
        val json = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[151.2,-33.8]},"properties":{"name":"No ID"}}]}"""
        val parsed = GeoJsonImporter.parse(json, emptyList(), "fallback")
        assertTrue(parsed.waypoints.single().id.isEmpty())
        assertTrue(parsed.identityOrder.single().sourceId == null)
    }

    @Test fun kmlParserLeavesMissingIdentityForExternalResolver() {
        val xml = """<kml><Placemark><Point><coordinates>151.2,-33.8</coordinates></Point></Placemark></kml>"""
        val parsed = KmlImporter.parse(xml, emptyList(), "fallback")
        assertTrue(parsed.waypoints.single().id.isEmpty())
        assertTrue(parsed.identityOrder.single().sourceId == null)
    }
}
