package com.tacmap.map

import com.tacmap.mgrs.MgrsFormatter
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.utm.UTM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MgrsCoordinateResolverTest {
    private val referenceLatitude = -34.0522
    private val referenceLongitude = 150.9550

    @Test
    fun shorthandAndFullMgrsResolveToSameSquareCentreAtEverySupportedPrecision() {
        val current = splitCurrentMgrs(referenceLatitude, referenceLongitude)
        val sizes = mapOf(4 to "1 km", 6 to "100 m", 8 to "10 m", 10 to "1 m")

        sizes.forEach { (totalDigits, expectedSize) ->
            val perAxis = totalDigits / 2
            val easting = current.easting.take(perAxis)
            val northing = current.northing.take(perAxis)
            val shorthand = resolveMgrsCoordinate(
                "$easting $northing",
                referenceLatitude,
                referenceLongitude,
            )
            val full = resolveMgrsCoordinate(
                current.prefix + easting + northing,
                referenceLatitude = null,
                referenceLongitude = null,
            )

            assertNotNull("$totalDigits-figure shorthand", shorthand)
            assertNotNull("$totalDigits-figure full MGRS", full)
            assertEquals(expectedSize, full!!.squareSizeLabel)
            assertEquals(full.latitude, shorthand!!.latitude, 1e-12)
            assertEquals(full.longitude, shorthand.longitude, 1e-12)

            val southwest = MgrsFormatter.parse(current.prefix + easting + northing)!!
            assertTrue("latitude should be centred north of SW corner", full.latitude > southwest.first)
            assertTrue("longitude should be centred east of SW corner", full.longitude > southwest.second)

            val southwestUtm = MGRS.parse(current.prefix + easting + northing).toUTM()
            val halfMetres = when (totalDigits) {
                4 -> 500.0
                6 -> 50.0
                8 -> 5.0
                else -> 0.5
            }
            val exact = UTM.create(
                southwestUtm.zone,
                southwestUtm.hemisphere,
                southwestUtm.easting + halfMetres,
                southwestUtm.northing + halfMetres,
            ).toPoint()
            assertEquals(exact.latitude, full.latitude, 1e-12)
            assertEquals(exact.longitude, full.longitude, 1e-12)
        }
    }

    @Test
    fun legitimateZeroZeroGraphicCanAnchorNumericShorthand() {
        val current = splitCurrentMgrs(0.0, 0.0)
        val shorthand = current.easting.take(2) + current.northing.take(2)

        assertNotNull(resolveMgrsCoordinate(shorthand, 0.0, 0.0))
    }

    @Test
    fun rejectsUnsupportedPrecisionMalformedPairsAndShorthandWithoutReference() {
        assertNull(resolveMgrsCoordinate("123456789012", referenceLatitude, referenceLongitude))
        assertNull(resolveMgrsCoordinate("12 345", referenceLatitude, referenceLongitude))
        assertNull(resolveMgrsCoordinate("12-34", referenceLatitude, referenceLongitude))
        assertNull(resolveMgrsCoordinate("1234", null, null))

        val current = splitCurrentMgrs(referenceLatitude, referenceLongitude)
        assertNull(resolveMgrsCoordinate(current.prefix + "123456789012", null, null))
    }

    @Test
    fun normalizesAntimeridianAndAcceptsFiniteCentreAcrossNominalUtmBandEdge() {
        val antimeridian = resolveMgrsCoordinate("60EXU6744", null, null)
        val northEdge = resolveMgrsCoordinate("31XEP0028", null, null)

        assertNotNull(antimeridian)
        assertTrue(antimeridian!!.longitude in -180.0..180.0)
        assertTrue(antimeridian.longitude < -179.0)
        assertNotNull(northEdge)
        assertTrue(northEdge!!.latitude > 84.0)
        assertTrue(northEdge.latitude <= 90.0)
    }

    private fun splitCurrentMgrs(latitude: Double, longitude: Double): CurrentMgrs {
        val compact = MgrsFormatter.format(latitude, longitude, spaced = false)
        val match = Regex("""^(\d{1,2}[A-Z]{3})(\d{5})(\d{5})$""").matchEntire(compact)
            ?: error("Unexpected test MGRS: $compact")
        return CurrentMgrs(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }

    private data class CurrentMgrs(val prefix: String, val easting: String, val northing: String)
}
