package com.tacmap.calibration

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geodetic lat/lon on whatever datum the caller says, NOT necessarily WGS84. */
data class GeodeticPoint(val latitude: Double, val longitude: Double) {
    fun isValid(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/** Reference ellipsoid, a + inverse flattening exactly like EPSG writes them. */
data class GeoEllipsoid(val a: Double, val invF: Double) {
    val f: Double get() = 1.0 / invF
    val e2: Double get() = f * (2.0 - f)
    val e: Double get() = sqrt(e2)
    /** third flattening, the Kruger series runs on this */
    val n: Double get() = f / (2.0 - f)

    /** plausible earth, same gate as iOS Ellipsoid.isPlausibleEarth: 0 < f < 0.01 */
    fun isValid(): Boolean =
        a.isFinite() && a in 6_000_000.0..7_000_000.0 &&
            invF.isFinite() && invF > 100.0

    companion object {
        val WGS84 = GeoEllipsoid(6_378_137.0, 298.257223563)
        val GRS80 = GeoEllipsoid(6_378_137.0, 298.257222101)
        val CLARKE_1866 = GeoEllipsoid(6_378_206.4, 294.978698213898)
        val INTERNATIONAL_1924 = GeoEllipsoid(6_378_388.0, 297.0)
        val AIRY_1830 = GeoEllipsoid(6_377_563.396, 299.3249646)
        val BESSEL_1841 = GeoEllipsoid(6_377_397.155, 299.1528128)
        val CLARKE_1880_IGN = GeoEllipsoid(6_378_249.2, 293.466021293627)
        val KRASSOVSKY_1940 = GeoEllipsoid(6_378_245.0, 298.3)
    }
}

enum class DatumTransformKind { IDENTITY, TRANSLATION3, HELMERT7 }

/**
 * Ellipsoid + shift onto WGS84. One table for GeoPDF /GCS, LGIDict codes and the
 * fiduciary datum picker, pinned by testdata/pdf_georef.json datums.table so iOS
 * and android can't drift again (they did, GDA94 was 1.5 m apart by path).
 *
 * helmert7 is the coordinate frame convention, rotations in arcsec, scale in ppm.
 */
data class GeoDatum(
    val id: String,
    val ellipsoid: GeoEllipsoid,
    val transform: DatumTransformKind,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val dz: Double = 0.0,
    val rxArcsec: Double = 0.0,
    val ryArcsec: Double = 0.0,
    val rzArcsec: Double = 0.0,
    val scalePpm: Double = 0.0,
) {
    val isCustom: Boolean get() = id == CUSTOM_ID

    /** datum lat/lon (h = 0) -> WGS84 lat/lon, height dropped. */
    fun toWGS84(latitude: Double, longitude: Double): GeodeticPoint? {
        val input = GeodeticPoint(latitude, longitude)
        if (!input.isValid()) return null
        if (transform == DatumTransformKind.IDENTITY) return input
        val (x, y, z) = geodeticToEcef(latitude, longitude, ellipsoid)
        val shifted = when (transform) {
            DatumTransformKind.TRANSLATION3 -> Triple(x + dx, y + dy, z + dz)
            DatumTransformKind.HELMERT7 -> helmertForward(x, y, z)
            DatumTransformKind.IDENTITY -> Triple(x, y, z)
        }
        return ecefToGeodetic(shifted.first, shifted.second, shifted.third, GeoEllipsoid.WGS84)
    }

    /** WGS84 lat/lon -> this datum, the exact inverse shift (not a sign flip for helmert7). */
    fun fromWGS84(latitude: Double, longitude: Double): GeodeticPoint? {
        val input = GeodeticPoint(latitude, longitude)
        if (!input.isValid()) return null
        if (transform == DatumTransformKind.IDENTITY) return input
        val (x, y, z) = geodeticToEcef(latitude, longitude, GeoEllipsoid.WGS84)
        val back = when (transform) {
            DatumTransformKind.TRANSLATION3 -> Triple(x - dx, y - dy, z - dz)
            DatumTransformKind.HELMERT7 -> helmertInverse(x, y, z) ?: return null
            DatumTransformKind.IDENTITY -> Triple(x, y, z)
        }
        return ecefToGeodetic(back.first, back.second, back.third, ellipsoid)
    }

    // X' = T + (1 + s) R X, R = [[1, rz, -ry], [-rz, 1, rx], [ry, -rx, 1]]
    private fun rotation(): Array<DoubleArray> {
        val k = PI / 180.0 / 3600.0
        val rx = rxArcsec * k
        val ry = ryArcsec * k
        val rz = rzArcsec * k
        val s = 1.0 + scalePpm * 1e-6
        return arrayOf(
            doubleArrayOf(s, s * rz, -s * ry),
            doubleArrayOf(-s * rz, s, s * rx),
            doubleArrayOf(s * ry, -s * rx, s),
        )
    }

    private fun helmertForward(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val m = rotation()
        return Triple(
            dx + m[0][0] * x + m[0][1] * y + m[0][2] * z,
            dy + m[1][0] * x + m[1][1] * y + m[1][2] * z,
            dz + m[2][0] * x + m[2][1] * y + m[2][2] * z,
        )
    }

    // solve M X = X' - T properly, flipping all seven signs leaves ~3 mm on OSGB36
    private fun helmertInverse(x: Double, y: Double, z: Double): Triple<Double, Double, Double>? {
        val m = rotation()
        val rx = x - dx
        val ry = y - dy
        val rz = z - dz
        val det = det3(m)
        if (!det.isFinite() || abs(det) < 1e-12) return null
        fun col(i: Int, v: DoubleArray): Array<DoubleArray> =
            Array(3) { r -> DoubleArray(3) { c -> if (c == i) v[r] else m[r][c] } }
        val rhs = doubleArrayOf(rx, ry, rz)
        return Triple(det3(col(0, rhs)) / det, det3(col(1, rhs)) / det, det3(col(2, rhs)) / det)
    }

    companion object {
        const val CUSTOM_ID = "custom"

        /** zero shift unless dx/dy/dz say otherwise, on the given spheroid */
        fun custom(a: Double, invF: Double, dx: Double = 0.0, dy: Double = 0.0, dz: Double = 0.0): GeoDatum? {
            val ellipsoid = GeoEllipsoid(a, invF)
            if (!ellipsoid.isValid() || !dx.isFinite() || !dy.isFinite() || !dz.isFinite()) return null
            val kind = if (dx == 0.0 && dy == 0.0 && dz == 0.0) DatumTransformKind.IDENTITY
            else DatumTransformKind.TRANSLATION3
            return GeoDatum(CUSTOM_ID, ellipsoid, kind, dx, dy, dz)
        }

        internal fun geodeticToEcef(latitude: Double, longitude: Double, ell: GeoEllipsoid): Triple<Double, Double, Double> {
            val phi = latitude * PI / 180.0
            val lambda = longitude * PI / 180.0
            val sinPhi = sin(phi)
            val cosPhi = cos(phi)
            val nu = ell.a / sqrt(1.0 - ell.e2 * sinPhi * sinPhi)
            return Triple(nu * cosPhi * cos(lambda), nu * cosPhi * sin(lambda), nu * (1.0 - ell.e2) * sinPhi)
        }

        // fixed point on the latitude, height falls out and gets dropped. converges
        // by a factor of ~e2 per pass so 12 is way past 1e-12 deg
        internal fun ecefToGeodetic(x: Double, y: Double, z: Double, ell: GeoEllipsoid): GeodeticPoint? {
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
            val p = sqrt(x * x + y * y)
            if (p <= 0.0) return null
            val lambda = atan2(y, x)
            var phi = atan2(z, p * (1.0 - ell.e2))
            repeat(12) {
                val sinPhi = sin(phi)
                val nu = ell.a / sqrt(1.0 - ell.e2 * sinPhi * sinPhi)
                val next = atan2(z + ell.e2 * nu * sinPhi, p)
                if (abs(next - phi) < 1e-15) {
                    phi = next
                    return GeodeticPoint(phi * 180.0 / PI, lambda * 180.0 / PI).takeIf { it.isValid() }
                }
                phi = next
            }
            return GeodeticPoint(phi * 180.0 / PI, lambda * 180.0 / PI).takeIf { it.isValid() }
        }

        private fun det3(m: Array<DoubleArray>): Double =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
    }
}

/** aliases each table row answers to, kept next to the row so the lookups can't disagree */
internal data class GeoDatumAliases(
    val wktNames: List<String>,
    val lgiCodes: List<String>,
    val lgiCodePrefixes: List<String> = emptyList(),
    val epsgDatum: Int? = null,
    val epsgGeographic: List<Int> = emptyList(),
)

/**
 * The shared datum table. Row values + aliases mirror testdata/pdf_georef.json
 * datums.table one for one (DatumTableTest asserts it), so edit both or neither.
 */
object GeoDatums {
    val WGS84 = GeoDatum("WGS84", GeoEllipsoid.WGS84, DatumTransformKind.IDENTITY)
    val NAD83 = GeoDatum("NAD83", GeoEllipsoid.GRS80, DatumTransformKind.IDENTITY)
    val GDA94 = GeoDatum(
        "GDA94", GeoEllipsoid.GRS80, DatumTransformKind.HELMERT7,
        dx = 0.06155, dy = -0.01087, dz = -0.04019,
        rxArcsec = -0.0394924, ryArcsec = -0.0327221, rzArcsec = -0.0328979, scalePpm = -0.009994,
    )
    val GDA2020 = GeoDatum("GDA2020", GeoEllipsoid.GRS80, DatumTransformKind.IDENTITY)
    val ETRS89 = GeoDatum("ETRS89", GeoEllipsoid.GRS80, DatumTransformKind.IDENTITY)
    val NAD27 = GeoDatum("NAD27", GeoEllipsoid.CLARKE_1866, DatumTransformKind.TRANSLATION3, -8.0, 160.0, 176.0)
    val NAD27_CONUS_EAST = GeoDatum("NAD27_CONUS_EAST", GeoEllipsoid.CLARKE_1866, DatumTransformKind.TRANSLATION3, -9.0, 161.0, 179.0)
    val NAD27_CONUS_WEST = GeoDatum("NAD27_CONUS_WEST", GeoEllipsoid.CLARKE_1866, DatumTransformKind.TRANSLATION3, -8.0, 159.0, 175.0)
    val NAD27_ALASKA = GeoDatum("NAD27_ALASKA", GeoEllipsoid.CLARKE_1866, DatumTransformKind.TRANSLATION3, -5.0, 135.0, 172.0)
    val NAD27_CANADA = GeoDatum("NAD27_CANADA", GeoEllipsoid.CLARKE_1866, DatumTransformKind.TRANSLATION3, -10.0, 158.0, 187.0)
    val ED50 = GeoDatum("ED50", GeoEllipsoid.INTERNATIONAL_1924, DatumTransformKind.TRANSLATION3, -87.0, -98.0, -121.0)
    val OSGB36 = GeoDatum(
        "OSGB36", GeoEllipsoid.AIRY_1830, DatumTransformKind.HELMERT7,
        dx = 446.448, dy = -125.157, dz = 542.06,
        rxArcsec = -0.15, ryArcsec = -0.247, rzArcsec = -0.842, scalePpm = -20.489,
    )
    val TOKYO = GeoDatum("TOKYO", GeoEllipsoid.BESSEL_1841, DatumTransformKind.TRANSLATION3, -146.414, 507.337, 680.507)
    val CH1903 = GeoDatum("CH1903", GeoEllipsoid.BESSEL_1841, DatumTransformKind.TRANSLATION3, 674.374, 15.056, 405.346)
    val NTF = GeoDatum("NTF", GeoEllipsoid.CLARKE_1880_IGN, DatumTransformKind.TRANSLATION3, -168.0, -60.0, 320.0)
    val SK42 = GeoDatum(
        "SK42", GeoEllipsoid.KRASSOVSKY_1940, DatumTransformKind.HELMERT7,
        dx = 23.92, dy = -141.27, dz = -80.9,
        rxArcsec = 0.0, ryArcsec = -0.35, rzArcsec = -0.82, scalePpm = -0.12,
    )

    internal val rows: List<Pair<GeoDatum, GeoDatumAliases>> = listOf(
        WGS84 to GeoDatumAliases(
            wktNames = listOf("WGS_1984", "WGS 84", "WGS84", "World Geodetic System 1984", "D_WGS_1984"),
            // WD is WGS72 in the NIMA table, kept as WGS84 for backward compat (fixture flags it)
            lgiCodes = listOf("WE", "WGE", "WD"), epsgDatum = 6326, epsgGeographic = listOf(4326),
        ),
        NAD83 to GeoDatumAliases(
            wktNames = listOf("North_American_Datum_1983", "North American Datum 1983", "NAD83", "D_North_American_1983"),
            lgiCodes = listOf("NA", "NAR"), lgiCodePrefixes = listOf("NAR-"),
            epsgDatum = 6269, epsgGeographic = listOf(4269),
        ),
        GDA94 to GeoDatumAliases(
            wktNames = listOf("Geocentric_Datum_of_Australia_1994", "GDA94", "D_GDA_1994"),
            lgiCodes = listOf("GD"), epsgDatum = 6283, epsgGeographic = listOf(4283),
        ),
        GDA2020 to GeoDatumAliases(
            wktNames = listOf("Geocentric_Datum_of_Australia_2020", "GDA2020", "D_GDA2020"),
            lgiCodes = emptyList(), epsgDatum = 1168, epsgGeographic = listOf(7844),
        ),
        ETRS89 to GeoDatumAliases(
            wktNames = listOf("European_Terrestrial_Reference_System_1989", "ETRS89", "D_ETRS_1989"),
            lgiCodes = emptyList(), epsgDatum = 6258, epsgGeographic = listOf(4258),
        ),
        NAD27 to GeoDatumAliases(
            wktNames = listOf("North_American_Datum_1927", "North American Datum 1927", "NAD27", "D_North_American_1927"),
            lgiCodes = listOf("NS", "NAS", "NAS-C"), lgiCodePrefixes = listOf("NAS-"),
            epsgDatum = 6267, epsgGeographic = listOf(4267),
        ),
        NAD27_CONUS_EAST to GeoDatumAliases(emptyList(), listOf("NAS-A")),
        NAD27_CONUS_WEST to GeoDatumAliases(emptyList(), listOf("NAS-B")),
        NAD27_ALASKA to GeoDatumAliases(emptyList(), listOf("NAS-D")),
        NAD27_CANADA to GeoDatumAliases(emptyList(), listOf("NAS-E")),
        ED50 to GeoDatumAliases(
            wktNames = listOf("European_Datum_1950", "European Datum 1950", "ED50", "D_European_1950"),
            lgiCodes = listOf("EU", "EUR", "EUR-M"), lgiCodePrefixes = listOf("EUR-"),
            epsgDatum = 6230, epsgGeographic = listOf(4230),
        ),
        OSGB36 to GeoDatumAliases(
            wktNames = listOf("OSGB_1936", "OSGB 1936", "OSGB36", "D_OSGB_1936", "Ordnance Survey of Great Britain 1936"),
            lgiCodes = listOf("OB", "OG", "OS", "OGB", "OGB-M"), lgiCodePrefixes = listOf("OGB-"),
            epsgDatum = 6277, epsgGeographic = listOf(4277),
        ),
        TOKYO to GeoDatumAliases(
            wktNames = listOf("Tokyo", "D_Tokyo"),
            lgiCodes = listOf("TC", "TOY"), lgiCodePrefixes = listOf("TOY-"),
            epsgDatum = 6301, epsgGeographic = listOf(4301),
        ),
        CH1903 to GeoDatumAliases(
            wktNames = listOf("CH1903", "D_CH1903"),
            lgiCodes = listOf("CH"), epsgDatum = 6149, epsgGeographic = listOf(4149),
        ),
        NTF to GeoDatumAliases(
            wktNames = listOf("Nouvelle_Triangulation_Francaise", "NTF", "D_NTF"),
            lgiCodes = listOf("NT", "NF"), epsgDatum = 6275, epsgGeographic = listOf(4275),
        ),
        SK42 to GeoDatumAliases(
            wktNames = listOf("Pulkovo_1942", "Pulkovo 1942", "SK-42", "SK42", "D_Pulkovo_1942"),
            lgiCodes = listOf("KK", "SPK"), epsgDatum = 6284, epsgGeographic = listOf(4284),
        ),
    )

    val table: List<GeoDatum> get() = rows.map { it.first }

    fun byId(id: String): GeoDatum? = rows.firstOrNull { it.first.id == id }?.first

    /** strip a leading D_, uppercase, drop everything that isn't A-Z/0-9 */
    internal fun normalizeWktName(name: String): String {
        val trimmed = name.trim()
        val noPrefix = if (trimmed.startsWith("D_", ignoreCase = true)) trimmed.substring(2) else trimmed
        return noPrefix.uppercase(Locale.US).filter { it in 'A'..'Z' || it in '0'..'9' }
    }

    fun forWktName(name: String): GeoDatum? {
        val key = normalizeWktName(name)
        if (key.isEmpty()) return null
        return rows.firstOrNull { (_, alias) -> alias.wktNames.any { normalizeWktName(it) == key } }?.first
    }

    /** exact code first, then the prefixes, NAS-X without its own row lands on the CONUS mean */
    fun forLgiCode(code: String): GeoDatum? {
        val key = code.trim().uppercase(Locale.US)
        if (key.isEmpty()) return null
        rows.firstOrNull { (_, alias) -> key in alias.lgiCodes }?.let { return it.first }
        return rows.firstOrNull { (_, alias) -> alias.lgiCodePrefixes.any { key.startsWith(it) } }?.first
    }

    fun forEpsgGeographic(code: Int): GeoDatum? =
        rows.firstOrNull { (_, alias) -> code in alias.epsgGeographic }?.first

    internal fun aliasesFor(id: String): GeoDatumAliases? = rows.firstOrNull { it.first.id == id }?.second
}
