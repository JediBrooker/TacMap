package com.tacmap.calibration

import java.util.Locale
import kotlin.math.abs

enum class GcsStatus(val code: String) {
    OK("ok"),
    FALLBACK_LOCAL_TM("fallbackLocalTM"),
    UNKNOWN_DATUM("unknownDatum"),
    /** present but broken in a way that moves every point (junk PRIMEM), reject */
    MALFORMED("malformed"),
}

/**
 * What a GeoPDF /GCS (WKT or EPSG) resolves to. crs is null for the local TM
 * fallback (it's centred on the GPTS, which the caller has) and datum is null
 * for UNKNOWN_DATUM. Rules are testdata/pdf_georef.json gcs.rules.
 */
data class GcsResolution(
    val status: GcsStatus,
    val crs: GeoCrs?,
    val datum: GeoDatum?,
    val datumAssumed: Boolean = false,
    /** unsupportedProjection / unreadableGcs / unsupportedEpsg for the fallback */
    val reason: String? = null,
    /** PRIMEM offset in degrees, GPTS longitudes and lon0 are relative to it */
    val primeMeridian: Double = 0.0,
)

/** tiny WKT1 tree, NAME[arg, arg, ...] or NAME(...) */
internal sealed class WktValue {
    data class Node(val keyword: String, val args: List<WktValue>) : WktValue() {
        fun children(keyword: String): List<Node> =
            args.filterIsInstance<Node>().filter { it.keyword.equals(keyword, ignoreCase = true) }

        fun child(keyword: String): Node? = children(keyword).firstOrNull()
        fun text(index: Int = 0): String? = (args.getOrNull(index) as? Text)?.value
        fun number(index: Int): Double? = (args.getOrNull(index) as? Number)?.value
        fun numbers(): List<Double> = args.filterIsInstance<Number>().map { it.value }
    }

    data class Text(val value: String) : WktValue()
    data class Number(val value: Double) : WktValue()
    data class Bare(val value: String) : WktValue()
}

internal object WktParser {
    private const val MAX_DEPTH = 32
    private const val MAX_LENGTH = 64 * 1024

    fun parse(text: String): WktValue.Node? {
        if (text.length > MAX_LENGTH) return null
        val p = Cursor(text)
        val node = runCatching { p.node(0) }.getOrNull() ?: return null
        p.skipSpace()
        return node.takeIf { p.atEnd() }
    }

    private class Cursor(val s: String) {
        var i = 0

        fun atEnd() = i >= s.length

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun node(depth: Int): WktValue.Node {
            require(depth < MAX_DEPTH)
            skipSpace()
            val keyword = ident()
            skipSpace()
            require(i < s.length && (s[i] == '[' || s[i] == '('))
            val close = if (s[i] == '[') ']' else ')'
            i++
            val args = ArrayList<WktValue>()
            skipSpace()
            if (i < s.length && s[i] == close) {
                i++
                return WktValue.Node(keyword, args)
            }
            while (true) {
                skipSpace()
                args += value(depth + 1)
                skipSpace()
                require(i < s.length)
                when (s[i]) {
                    ',' -> i++
                    close -> { i++; return WktValue.Node(keyword, args) }
                    else -> throw IllegalArgumentException("bad wkt")
                }
            }
        }

        private fun value(depth: Int): WktValue {
            require(i < s.length)
            val c = s[i]
            return when {
                c == '"' -> WktValue.Text(quoted())
                c == '-' || c == '+' || c == '.' || c.isDigit() -> WktValue.Number(number())
                c.isLetter() || c == '_' -> {
                    val start = i
                    val word = ident()
                    skipSpace()
                    if (i < s.length && (s[i] == '[' || s[i] == '(')) {
                        i = start
                        node(depth)
                    } else {
                        WktValue.Bare(word)
                    }
                }
                else -> throw IllegalArgumentException("bad wkt")
            }
        }

        private fun ident(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            require(i > start)
            return s.substring(start, i)
        }

        // WKT1 escapes a quote by doubling it
        private fun quoted(): String {
            i++
            val out = StringBuilder()
            while (true) {
                require(i < s.length)
                val c = s[i++]
                if (c == '"') {
                    if (i < s.length && s[i] == '"') {
                        out.append('"'); i++
                    } else {
                        return out.toString()
                    }
                } else {
                    out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = i
            if (s[i] == '-' || s[i] == '+') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                    ((s[i] == '-' || s[i] == '+') && (s[i - 1] == 'e' || s[i - 1] == 'E')))
            ) i++
            return s.substring(start, i).toDouble()
        }
    }
}

object GcsParser {
    /** /GCS dict: EPSG wins when it's one we know, else the WKT, else unreadable */
    fun resolve(wkt: String?, epsg: Int?): GcsResolution {
        if (epsg != null) {
            val fromCode = fromEpsg(epsg)
            if (fromCode.status == GcsStatus.OK || wkt == null) return fromCode
        }
        if (wkt != null) return fromWkt(wkt)
        return unreadable()
    }

    fun fromEpsg(code: Int): GcsResolution {
        fun utm(zone: Int, south: Boolean, datum: GeoDatum) =
            GcsResolution(GcsStatus.OK, GeoCrs.utm(zone, south), datum)
        return when (code) {
            in 32601..32660 -> utm(code - 32600, false, GeoDatums.WGS84)
            in 32701..32760 -> utm(code - 32700, true, GeoDatums.WGS84)
            in 26901..26923 -> utm(code - 26900, false, GeoDatums.NAD83)
            in 26701..26722 -> utm(code - 26700, false, GeoDatums.NAD27)
            in 28348..28358 -> utm(code - 28300, true, GeoDatums.GDA94)
            in 7846..7859 -> utm(code - 7800, true, GeoDatums.GDA2020)
            4326 -> GcsResolution(GcsStatus.OK, GeoCrs.Geographic, GeoDatums.WGS84)
            4269 -> GcsResolution(GcsStatus.OK, GeoCrs.Geographic, GeoDatums.NAD83)
            4283 -> GcsResolution(GcsStatus.OK, GeoCrs.Geographic, GeoDatums.GDA94)
            7844 -> GcsResolution(GcsStatus.OK, GeoCrs.Geographic, GeoDatums.GDA2020)
            else -> GcsResolution(
                GcsStatus.FALLBACK_LOCAL_TM, null, GeoDatums.WGS84,
                datumAssumed = true, reason = "unsupportedEpsg",
            )
        }
    }

    fun fromWkt(wkt: String): GcsResolution {
        val root = WktParser.parse(wkt) ?: return unreadable()
        return when (root.keyword.uppercase(Locale.US)) {
            "GEOGCS" -> {
                val geog = readGeogcs(root) ?: return malformed()
                when (val d = geog.datum) {
                    DatumRead.Unreadable -> unreadable(geog.primeMeridian)
                    DatumRead.Unknown -> GcsResolution(GcsStatus.UNKNOWN_DATUM, GeoCrs.Geographic, null, primeMeridian = geog.primeMeridian)
                    is DatumRead.Resolved -> GcsResolution(
                        GcsStatus.OK, GeoCrs.Geographic, d.datum, d.assumed, primeMeridian = geog.primeMeridian,
                    )
                }
            }
            "PROJCS" -> {
                val geogNode = root.child("GEOGCS") ?: return unreadable()
                val geog = readGeogcs(geogNode) ?: return malformed()
                // the projection still wants an ellipsoid when the datum is a dud, so fall back to the wkt spheroid
                val ellipsoid = (geog.datum as? DatumRead.Resolved)?.datum?.ellipsoid ?: geog.spheroid
                val projection = projectedCrs(root, geog.primeMeridian, ellipsoid)
                val pm = geog.primeMeridian
                when (val d = geog.datum) {
                    DatumRead.Unreadable -> unreadable(pm)
                    DatumRead.Unknown -> GcsResolution(GcsStatus.UNKNOWN_DATUM, (projection as? ProjectionRead.Ok)?.crs, null, primeMeridian = pm)
                    is DatumRead.Resolved -> when (projection) {
                        is ProjectionRead.Ok -> GcsResolution(GcsStatus.OK, projection.crs, d.datum, d.assumed, primeMeridian = pm)
                        ProjectionRead.Unsupported -> GcsResolution(
                            GcsStatus.FALLBACK_LOCAL_TM, null, d.datum, d.assumed, reason = "unsupportedProjection", primeMeridian = pm,
                        )
                        ProjectionRead.Unreadable -> GcsResolution(
                            GcsStatus.FALLBACK_LOCAL_TM, null, d.datum, d.assumed, reason = "unreadableGcs", primeMeridian = pm,
                        )
                    }
                }
            }
            else -> unreadable()
        }
    }

    private fun unreadable(primeMeridian: Double = 0.0) = GcsResolution(
        GcsStatus.FALLBACK_LOCAL_TM, null, GeoDatums.WGS84, datumAssumed = true, reason = "unreadableGcs",
        primeMeridian = primeMeridian,
    )

    private fun malformed() = GcsResolution(GcsStatus.MALFORMED, null, null)

    private sealed class DatumRead {
        data class Resolved(val datum: GeoDatum, val assumed: Boolean) : DatumRead()
        data object Unknown : DatumRead()
        data object Unreadable : DatumRead()
    }

    private class Geogcs(val datum: DatumRead, val spheroid: GeoEllipsoid?, val primeMeridian: Double)

    /**
     * GEOGCS -> datum + PRIMEM, rules in the fixture's gcs.rules. Null when PRIMEM
     * is there but junk: it shifts every point, so that's malformed, never a quiet 0.
     */
    private fun readGeogcs(node: WktValue.Node): Geogcs? {
        var pm = 0.0
        node.child("PRIMEM")?.let { primem ->
            val v = primem.number(1)
            if (v == null || !v.isFinite() || v !in -180.0..180.0) return null
            pm = v
        }
        val datumNode = node.child("DATUM")
        val name = datumNode?.text()
        val sph = datumNode?.child("SPHEROID") ?: datumNode?.child("ELLIPSOID")
        if (datumNode == null || name == null || sph == null) return Geogcs(DatumRead.Unreadable, null, pm)
        val a = sph.number(1)
        val invF = sph.number(2)
        val spheroid = if (a != null && invF != null && invF > 0.0) GeoEllipsoid(a, invF).takeIf { it.isValid() } else null
        // 1. a table name always wins, TOWGS84 or not
        GeoDatums.forWktName(name)?.let { return Geogcs(DatumRead.Resolved(it, false), spheroid, pm) }
        if (spheroid == null) return Geogcs(DatumRead.Unreadable, null, pm)
        // 2. TOWGS84 with no rotation/scale is a plain 3 param shift, any other length is ignored
        datumNode.child("TOWGS84")?.numbers()?.let { t ->
            val plain = t.size == 3 || (t.size == 7 && t.drop(3).all { it == 0.0 })
            if (plain) {
                GeoDatum.custom(spheroid.a, spheroid.invF, t[0], t[1], t[2])
                    ?.let { return Geogcs(DatumRead.Resolved(it, false), spheroid, pm) }
            }
        }
        // 3. WGS84/GRS80 sized, call it zero shift but say so
        if (isModernEllipsoid(spheroid.a, spheroid.invF)) {
            GeoDatum.custom(spheroid.a, spheroid.invF)?.let { return Geogcs(DatumRead.Resolved(it, true), spheroid, pm) }
        }
        return Geogcs(DatumRead.Unknown, spheroid, pm)
    }

    /** within 1 m / 1e-6 invF of WGS84 or GRS80, the only ones a zero shift is safe for */
    internal fun isModernEllipsoid(a: Double, invF: Double): Boolean =
        listOf(GeoEllipsoid.WGS84, GeoEllipsoid.GRS80).any { abs(a - it.a) <= 1.0 && abs(invF - it.invF) <= 1e-6 }

    private sealed class ProjectionRead {
        data class Ok(val crs: GeoCrs) : ProjectionRead()
        data object Unsupported : ProjectionRead()
        /** a projection we'd do, but its params are missing or junk */
        data object Unreadable : ProjectionRead()
    }

    private val SUPPORTED = setOf(
        "transverse_mercator", "gauss_kruger", "lambert_conformal_conic_2sp", "lambert_conformal_conic_1sp",
        "lambert_conformal_conic", "mercator_1sp", "mercator_2sp", "mercator",
    )

    private fun normalised(s: String) = s.trim().lowercase(Locale.US).replace(' ', '_')

    private fun projectedCrs(root: WktValue.Node, pm: Double, ellipsoid: GeoEllipsoid?): ProjectionRead {
        val name = root.child("PROJECTION")?.text()?.let(::normalised) ?: return ProjectionRead.Unreadable
        val params = HashMap<String, Double>()
        for (p in root.children("PARAMETER")) {
            val key = p.text() ?: return ProjectionRead.Unreadable
            val v = p.number(1)?.takeIf { it.isFinite() } ?: return ProjectionRead.Unreadable
            params[normalised(key)] = v
        }
        // linear UNIT on the PROJCS itself, not the GEOGCS angular one
        val unitNode = root.child("UNIT")
        val unit = if (unitNode == null) 1.0 else unitNode.number(1) ?: 1.0
        if (!unit.isFinite() || unit <= 0.0) return ProjectionRead.Unreadable
        fun p(vararg keys: String): Double? = keys.firstNotNullOfOrNull { params[it] }
        // PROJ's aliases, central_meridian wins wherever it's written
        val cm = p("central_meridian", "longitude_of_origin", "longitude_of_center")
            ?: return if (name in SUPPORTED) ProjectionRead.Unreadable else ProjectionRead.Unsupported
        val fe = (p("false_easting") ?: 0.0) * unit
        val fn = (p("false_northing") ?: 0.0) * unit
        val lat0 = p("latitude_of_origin", "latitude_of_center") ?: 0.0
        // not wrapped, same as iOS; every projector wraps lon - lon0 itself
        val lon0 = cm + pm
        val k0 = p("scale_factor") ?: 1.0
        val sp1 = p("standard_parallel_1")
        val sp2 = p("standard_parallel_2")
        val crs = when (name) {
            "transverse_mercator", "gauss_kruger" -> GeoCrs.TransverseMercator(lat0, lon0, k0, fe, fn)
            "lambert_conformal_conic_2sp" -> {
                if (sp1 == null || sp2 == null) return ProjectionRead.Unreadable
                GeoCrs.LambertConformalConic2SP(sp1, sp2, lat0, lon0, fe, fn)
            }
            "lambert_conformal_conic_1sp" -> GeoCrs.LambertConformalConic1SP(lat0, lon0, k0, fe, fn)
            // esri spelling: two parallels means 2SP, otherwise 1SP + Scale_Factor
            "lambert_conformal_conic" -> if (sp1 != null && sp2 != null) {
                GeoCrs.LambertConformalConic2SP(sp1, sp2, lat0, lon0, fe, fn)
            } else {
                GeoCrs.LambertConformalConic1SP(p("latitude_of_origin") ?: sp1 ?: 0.0, lon0, k0, fe, fn)
            }
            "mercator_1sp" -> GeoCrs.Mercator1SP(lon0, k0, fe, fn)
            // 2SP (and esri Mercator) give lat_ts, fold it into k0 on the datum ellipsoid
            "mercator_2sp", "mercator" -> if (sp1 != null) {
                if (ellipsoid == null || abs(sp1) >= 90.0) return ProjectionRead.Unreadable
                GeoCrs.Mercator1SP(lon0, GeoCrs.mercatorScaleAt(sp1, ellipsoid), fe, fn)
            } else {
                GeoCrs.Mercator1SP(lon0, k0, fe, fn)
            }
            else -> return ProjectionRead.Unsupported
        }
        return ProjectionRead.Ok(crs)
    }
}
