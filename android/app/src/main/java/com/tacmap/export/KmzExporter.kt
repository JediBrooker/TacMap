package com.tacmap.export

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingLayer
import com.tacmap.waypoints.Waypoint
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A rendered symbol: PNG bytes plus its anchor as KML hotSpot fractions (y from the bottom). */
class KmzSymbolImage(
    val png: ByteArray,
    val hotSpotX: Double = 0.5,
    val hotSpotY: Double = 0.5,
)

/**
 * KMZ packing for [KmlExporter]: `doc.kml` first, then one PNG per distinct
 * symbol image, so Google Earth, ATAK and GIS tools show the actual APP-6,
 * task, marker and custom symbols instead of generic pins. Rendering stays
 * with the caller so this remains a plain JVM function.
 */
object KmzExporter {
    const val FILE_NAME = "TacMap-MissionObjects.kmz"
    const val KML_FILE_NAME = "TacMap-MissionObjects.kml"

    /** 1980-01-01, the earliest ZIP date, so the same mission gives the same entries. */
    private const val FIXED_ENTRY_TIME = 315_532_800_000L

    fun export(
        waypoints: List<Waypoint>,
        drawings: List<DrawingFeature>,
        layers: List<DrawingLayer>,
        density: Float = 1f,
        iconFor: (Waypoint) -> KmzSymbolImage?,
    ): ByteArray {
        val icons = mutableMapOf<String, KmlIcon>()
        val files = linkedMapOf<String, ByteArray>()
        for (waypoint in waypoints) {
            val image = iconFor(waypoint) ?: continue
            val path = "files/icons/${iconName(image.png)}.png"
            icons[waypoint.id] = KmlIcon(path, image.hotSpotX, image.hotSpotY)
            files.putIfAbsent(path, image.png)
        }
        val kml = KmlExporter.export(waypoints, drawings, layers, icons, density)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putEntry("doc.kml", kml.toByteArray(Charsets.UTF_8))
            files.forEach { (path, png) -> zip.putEntry(path, png) }
        }
        return bytes.toByteArray()
    }

    /** Stable short name from the image bytes, so identical symbols share one file. */
    fun iconName(png: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(png).take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun ZipOutputStream.putEntry(path: String, contents: ByteArray) {
        putNextEntry(ZipEntry(path).apply { time = FIXED_ENTRY_TIME })
        write(contents)
        closeEntry()
    }
}
