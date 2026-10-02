package com.tacmap.mgrs

import com.tacmap.calibration.FiduciaryReferenceParser
import com.tacmap.calibration.GeoCrs
import com.tacmap.calibration.GeoDatum
import com.tacmap.calibration.GeoEllipsoid
import com.tacmap.calibration.Wgs84Coordinate
import com.tacmap.calibration.fiducial.CalibrationPointKind
import com.tacmap.calibration.fiducial.CalibrationReference
import com.tacmap.calibration.fiducial.GridSource
import com.tacmap.calibration.fiducial.StatusMessage
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow

/** zone + band + square of the latest grid point, shorthand gets completed from it */
data class GridAnchor(val zone: Int, val band: Char, val square: String, val fromPoint: Int)

data class CoordinateParseContext(
    val datum: GeoDatum,
    val isFirstPoint: Boolean = true,
    val kind: CalibrationPointKind = CalibrationPointKind.INTERSECTION,
    val gridAnchor: GridAnchor? = null,
    /** where the shown georef puts the pending point (WGS84), null on a provisional placement */
    val predicted: Wgs84Coordinate? = null,
)

enum class CoordinateSource(val code: String) { MGRS("mgrs"), UTM("utm"), LAT_LON("latLon") }

enum class CompletedFrom(val code: String) { MAP("map"), POINT("point") }

enum class CoordinateParseError(val code: String, val messageKey: String?) {
    EMPTY("empty", null),
    UNRECOGNISED("unrecognised", "calibration_err_unrecognised"),
    TOO_COARSE("tooCoarse", "calibration_err_too_coarse"),
    UNEQUAL_DIGITS("unequalDigits", "calibration_err_odd_digits"),
    NEEDS_FULL_REFERENCE("needsFullReference", "calibration_err_needs_full"),
    INVALID_SQUARE("invalidSquare", "calibration_err_square"),
    BAND_MISMATCH("bandMismatch", "calibration_err_band"),
    POLAR_UNSUPPORTED("polarUnsupported", "calibration_err_polar"),
    OUT_OF_RANGE("outOfRange", "calibration_err_range"),
    OLD_LETTERING("oldLettering", "calibration_err_old_lettering"),
}

/** what the typed text reads as. easting/northing are the SW corner of the cell, sheet datum */
data class ParsedReference(
    val source: CoordinateSource,
    val zone: Int? = null,
    val south: Boolean? = null,
    val band: Char? = null,
    val square: String? = null,
    val easting: Double? = null,
    val northing: Double? = null,
    val digits: Int? = null,
    val cellSizeM: Double? = null,
    val effectiveKind: CalibrationPointKind? = null,
    val kindSegment: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val completedFrom: CompletedFrom? = null,
    val completedFromPoint: Int? = null,
    val canonical: String,
    /** sheet datum lat/lon with the kind offset applied (s5) */
    val resolvedLatitude: Double,
    val resolvedLongitude: Double,
    /** interpretation line, in order; the UI tacks the datum name on the end */
    val messages: List<StatusMessage>,
) {
    fun toReference(): CalibrationReference = when (source) {
        CoordinateSource.LAT_LON -> CalibrationReference.Geographic(latitude!!, longitude!!)
        else -> CalibrationReference.Grid(
            zone = zone!!,
            south = south!!,
            easting = easting!!,
            northing = northing!!,
            cellSizeM = cellSizeM ?: 1.0,
            source = if (source == CoordinateSource.MGRS) GridSource.MGRS else GridSource.UTM,
        )
    }
}

sealed class ParseOutcome {
    data class Ok(val reference: ParsedReference) : ParseOutcome()
    data class Err(val error: CoordinateParseError, val args: Map<String, String> = emptyMap()) : ParseOutcome()
}

/**
 * Calibration coordinate entry (WP4 contract s4). Pure, pinned by
 * testdata/calibration_input.json, which is generated from a python port of the
 * very same rules, so keep the two in step. Full MGRS -> UPS (refused) ->
 * shorthand -> UTM -> decimal degrees -> DM/DMS, first form whose SHAPE matches
 * owns the input and its errors (no falling through).
 *
 * MGRS E/N never go through lat/lon: column + row letters + the NGA band floor,
 * same as mil.nga MGRS.toUTM(). Search keeps its own parser, nothing here touches it.
 */
object CoordinateInputParser {
    const val MAX_INPUT_UTF16_UNITS = 64

    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private const val ROWS = "ABCDEFGHJKLMNPQRSTUV"
    private val COLUMN_SETS = listOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private val NO_GZD = setOf(32 to 'X', 34 to 'X', 36 to 'X')

    // NGA MGRS.utmNorthing floor per band (100 km below the WGS84 northing of the band's
    // south edge at lon 0). Same numbers as calibration_input.json rules.bandBottomNorthing
    private val BAND_BOTTOM_NORTHING = mapOf(
        'C' to 1_100_000.0, 'D' to 2_000_000.0, 'E' to 2_800_000.0, 'F' to 3_700_000.0,
        'G' to 4_600_000.0, 'H' to 5_500_000.0, 'J' to 6_400_000.0, 'K' to 7_300_000.0,
        'L' to 8_200_000.0, 'M' to 9_100_000.0, 'N' to 0.0, 'P' to 800_000.0,
        'Q' to 1_700_000.0, 'R' to 2_600_000.0, 'S' to 3_500_000.0, 'T' to 4_400_000.0,
        'U' to 5_300_000.0, 'V' to 6_200_000.0, 'W' to 7_100_000.0, 'X' to 7_900_000.0,
    )

    // sheets on these carry the old AL lettering, we refuse MGRS on them rather than guess
    private val AL_ELLIPSOIDS = setOf(GeoEllipsoid.CLARKE_1866, GeoEllipsoid.CLARKE_1880_IGN, GeoEllipsoid.BESSEL_1841)

    private val FULL = Regex("([0-9]{1,2}) ?([A-Z]) ?([A-Z]) ?([A-Z])(?: ?([0-9]+))?(?: ([0-9]+))?")
    private val UPS = Regex("([ABYZ]) ?([A-Z]) ?([A-Z])(?: ?([0-9]+))?(?: ([0-9]+))?")
    private val SHORT_SQ = Regex("([A-Z]) ?([A-Z])(?: ?([0-9]+))?(?: ([0-9]+))?")
    private val SHORT_DIG = Regex("([0-9]+)(?: ([0-9]+))?")
    private val UTM_RE = Regex("([0-9]{1,2}) ?([A-Z]) ([0-9]+)(?: ?ME)? ([0-9]+)(?: ?MN)?")
    private val UTM_WORDS = Regex("([0-9]{1,2}) (NORTH|SOUTH) ([0-9]+)(?: ?ME)? ([0-9]+)(?: ?MN)?")

    private class Fail(val error: CoordinateParseError, val args: Map<String, String> = emptyMap()) : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    fun parse(raw: String, ctx: CoordinateParseContext): ParseOutcome = try {
        ParseOutcome.Ok(parseOrThrow(raw, ctx))
    } catch (f: Fail) {
        ParseOutcome.Err(f.error, f.args)
    }

    fun isOldLetteringDatum(datum: GeoDatum): Boolean = datum.ellipsoid in AL_ELLIPSOIDS

    /** contract s4.1: unicode spaces, minus, primes, collapse, trim */
    fun normalise(raw: String): String {
        val spaced = FiduciaryReferenceParser.unicodeSpacesToAscii(raw)
            .replace('−', '-')
            .replace('′', '\'').replace('’', '\'')
            .replace('″', '"').replace('”', '"').replace("''", "\"")
        return spaced.replace(Regex(" +"), " ").trim(' ')
    }

    private fun parseOrThrow(raw: String, ctx: CoordinateParseContext): ParsedReference {
        val s = normalise(raw)
        if (s.isEmpty()) throw Fail(CoordinateParseError.EMPTY)
        if (s.length > MAX_INPUT_UTF16_UNITS) throw Fail(CoordinateParseError.UNRECOGNISED)
        val u = s.uppercase(Locale.ROOT)
        val al = isOldLetteringDatum(ctx.datum)
        FULL.matchEntire(u)?.let { return parseFull(it, ctx, al) }
        if (UPS.matches(u)) throw Fail(CoordinateParseError.POLAR_UNSUPPORTED)
        SHORT_SQ.matchEntire(u)?.let { m ->
            return parseShort(m.groupValues[1][0], m.groupValues[2][0], m.opt(3), m.opt(4), ctx, al)
        }
        SHORT_DIG.matchEntire(u)?.let { m -> return parseShort(null, null, m.opt(1), m.opt(2), ctx, al) }
        parseUtm(u, ctx)?.let { return it }
        parseDecimal(u)?.let { return it }
        parseDms(u)?.let { return it }
        throw Fail(CoordinateParseError.UNRECOGNISED)
    }

    private fun MatchResult.opt(i: Int): String? = groups[i]?.value

    // ---------------------------------------------------------------- MGRS

    internal fun columnLetters(zone: Int): String = COLUMN_SETS[(zone - 1) % 3]

    internal fun rowLetters(zone: Int): String =
        if (zone % 2 == 0) ROWS.substring(5) + ROWS.substring(0, 5) else ROWS

    /** null outside the MGRS bands (-80..84) */
    internal fun bandIndex(lat: Double): Int? {
        if (!lat.isFinite() || lat < -80.0 || lat > 84.0) return null
        return minOf(19, floor((lat + 80.0) / 8.0).toInt())
    }

    internal fun bandOf(lat: Double): Char? = bandIndex(lat)?.let { BANDS[it] }

    /** MGRS grid zone, Norway + Svalbard included, it's what's printed on the sheet */
    internal fun mgrsZone(lat: Double, lonIn: Double): Int {
        val lon = ((lonIn + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        var z = floor((lon + 180.0) / 6.0).toInt() + 1
        if (lat >= 56.0 && lat < 64.0 && lon >= 3.0 && lon < 12.0) z = 32
        if (lat in 72.0..84.0 && lon >= 0.0) {
            z = when {
                lon < 9.0 -> 31
                lon < 21.0 -> 33
                lon < 33.0 -> 35
                lon < 42.0 -> 37
                else -> z
            }
        }
        return minOf(z, 60)
    }

    /** NGA MGRS.parse().toUTM(): never via lat/lon */
    internal fun mgrsEastingNorthing(zone: Int, band: Char, col: Char, row: Char, eIn: Double, nIn: Double): Pair<Double, Double> {
        val e = (columnLetters(zone).indexOf(col) + 1) * 100_000.0 + eIn
        val n100 = rowLetters(zone).indexOf(row) * 100_000.0
        val floorN = BAND_BOTTOM_NORTHING.getValue(band)
        var n2m = 0.0
        while (n2m + n100 + nIn < floorN) n2m += 2_000_000.0
        return e to (n2m + n100 + nIn)
    }

    /** 100 km square letters of a UTM E/N in [zone], null off the column range */
    fun squareOf(zone: Int, easting: Double, northing: Double): String? {
        val ci = floor(easting / 100_000.0).toInt() - 1
        if (ci !in 0 until 8) return null
        val ri = floor(northing / 100_000.0).toInt().mod(20)
        return "${columnLetters(zone)[ci]}${rowLetters(zone)[ri]}"
    }

    private fun inverse(datum: GeoDatum, zone: Int, south: Boolean, e: Double, n: Double) =
        GeoCrs.utm(zone, south).inverse(e, n, datum.ellipsoid)

    private fun forward(datum: GeoDatum, zone: Int, south: Boolean, lat: Double, lon: Double) =
        GeoCrs.utm(zone, south).forward(lat, lon, datum.ellipsoid)

    private data class Digits(val eIn: Double, val nIn: Double, val total: Int)

    private fun digitRule(g1In: String?, g2: String?, kind: CalibrationPointKind): Digits {
        val g1 = g1In ?: ""
        if (g2 != null && g1.length != g2.length) throw Fail(CoordinateParseError.UNEQUAL_DIGITS)
        val digits = g1 + (g2 ?: "")
        val total = digits.length
        if (total % 2 != 0) throw Fail(CoordinateParseError.UNEQUAL_DIGITS)
        if (total == 0 || total == 2) throw Fail(CoordinateParseError.TOO_COARSE)
        if (total > 10) throw Fail(CoordinateParseError.UNRECOGNISED)
        if (kind == CalibrationPointKind.FEATURE && total == 4) throw Fail(CoordinateParseError.TOO_COARSE)
        val half = total / 2
        val unit = 10.0.pow(5 - half)
        return Digits(digits.substring(0, half).toLong() * unit, digits.substring(half).toLong() * unit, total)
    }

    private fun gridOk(
        zone: Int, band: Char, col: Char, row: Char, d: Digits, ctx: CoordinateParseContext,
        completed: CompletedFrom? = null, completedPoint: Int? = null,
    ): ParsedReference {
        val (e, n) = mgrsEastingNorthing(zone, band, col, row, d.eIn, d.nIn)
        val south = band < 'N'
        val latSw = inverse(ctx.datum, zone, south, e, n)?.latitude
            ?: throw Fail(CoordinateParseError.POLAR_UNSUPPORTED)
        val bi = bandIndex(latSw) ?: throw Fail(CoordinateParseError.POLAR_UNSUPPORTED)
        if (abs(bi - BANDS.indexOf(band)) > 1) throw Fail(CoordinateParseError.BAND_MISMATCH, mapOf("band" to band.toString()))
        val cell = 10.0.pow(5 - d.total / 2)
        val kind = if (d.total < 10) ctx.kind else CalibrationPointKind.INTERSECTION
        val off = if (kind == CalibrationPointKind.FEATURE) cell / 2.0 else 0.0
        val resolved = inverse(ctx.datum, zone, south, e + off, n + off)
            ?: throw Fail(CoordinateParseError.POLAR_UNSUPPORTED)
        val canonical = String.format(
            Locale.US, "%d%s %s%s %05d %05d", zone, band, col, row,
            (e % 100_000.0).toLong(), (n % 100_000.0).toLong(),
        )
        val messages = ArrayList<StatusMessage>(3)
        messages += StatusMessage("calibration_reads_as", mapOf("text" to canonical))
        messages += if (kind == CalibrationPointKind.FEATURE) {
            StatusMessage("calibration_cell_feature", mapOf("sizeM" to cell, "halfM" to cell / 2.0))
        } else {
            StatusMessage("calibration_cell_intersection", mapOf("sizeM" to cell))
        }
        when (completed) {
            CompletedFrom.MAP -> messages += StatusMessage("calibration_completed_from_map")
            CompletedFrom.POINT -> messages += StatusMessage("calibration_completed_from", mapOf("number" to completedPoint!!))
            null -> Unit
        }
        val half = d.total / 2
        return ParsedReference(
            source = CoordinateSource.MGRS,
            zone = zone, south = south, band = band, square = "$col$row",
            easting = e, northing = n, digits = d.total, cellSizeM = cell,
            effectiveKind = kind, kindSegment = half in 2..4,
            completedFrom = completed, completedFromPoint = completedPoint,
            canonical = canonical,
            resolvedLatitude = resolved.latitude, resolvedLongitude = resolved.longitude,
            messages = messages,
        )
    }

    private fun parseFull(m: MatchResult, ctx: CoordinateParseContext, al: Boolean): ParsedReference {
        val zone = m.groupValues[1].toInt()
        val band = m.groupValues[2][0]
        val col = m.groupValues[3][0]
        val row = m.groupValues[4][0]
        if (zone !in 1..60 || band !in BANDS || (zone to band) in NO_GZD) throw Fail(CoordinateParseError.UNRECOGNISED)
        if (al) throw Fail(CoordinateParseError.OLD_LETTERING, mapOf("datumId" to ctx.datum.id))
        if (col !in columnLetters(zone) || row !in ROWS) {
            throw Fail(CoordinateParseError.INVALID_SQUARE, mapOf("square" to "$col$row", "zone" to zone.toString()))
        }
        val d = digitRule(m.opt(5), m.opt(6), ctx.kind)
        return gridOk(zone, band, col, row, d, ctx)
    }

    private fun parseShort(colIn: Char?, rowIn: Char?, g1: String?, g2: String?, ctx: CoordinateParseContext, al: Boolean): ParsedReference {
        if (al) throw Fail(CoordinateParseError.OLD_LETTERING, mapOf("datumId" to ctx.datum.id))
        val predicted = ctx.predicted
        val anchor = ctx.gridAnchor
        if (predicted == null && anchor == null) throw Fail(CoordinateParseError.NEEDS_FULL_REFERENCE)
        if (predicted != null) {
            val p = ctx.datum.fromWGS84(predicted.latitude, predicted.longitude)
                ?: throw Fail(CoordinateParseError.POLAR_UNSUPPORTED)
            val zone = mgrsZone(p.latitude, p.longitude)
            val south = p.latitude < 0.0
            val band = bandOf(p.latitude) ?: throw Fail(CoordinateParseError.POLAR_UNSUPPORTED)
            if (colIn != null && (colIn !in columnLetters(zone) || rowIn!! !in ROWS)) {
                throw Fail(CoordinateParseError.INVALID_SQUARE, mapOf("square" to "$colIn$rowIn", "zone" to zone.toString()))
            }
            val d = digitRule(g1, g2, ctx.kind)
            if (colIn != null) return gridOk(zone, band, colIn, rowIn!!, d, ctx, CompletedFrom.MAP)
            // figures only: the predicted 100 km square and its 8 neighbours, nearest wins
            val plane = forward(ctx.datum, zone, south, p.latitude, p.longitude)
                ?: throw Fail(CoordinateParseError.POLAR_UNSUPPORTED)
            val cell = 10.0.pow(5 - d.total / 2)
            val kind = if (d.total < 10) ctx.kind else CalibrationPointKind.INTERSECTION
            val off = if (kind == CalibrationPointKind.FEATURE) cell / 2.0 else 0.0
            val e0 = floor(plane.x / 100_000.0) * 100_000.0
            val n0 = floor(plane.y / 100_000.0) * 100_000.0
            var best: Triple<Double, Double, Double>? = null
            for (dn in -1..1) for (de in -1..1) {
                val e = e0 + de * 100_000.0 + d.eIn
                val n = n0 + dn * 100_000.0 + d.nIn
                if (!(e >= 100_000.0 && e < 900_000.0 && n >= 0.0 && n <= 10_000_000.0)) continue
                val dist = hypot(e + off - plane.x, n + off - plane.y)
                if (best == null || dist < best.first) best = Triple(dist, e, n)
            }
            val pick = best ?: throw Fail(CoordinateParseError.UNRECOGNISED)
            val sq = squareOf(zone, pick.second, pick.third) ?: throw Fail(CoordinateParseError.UNRECOGNISED)
            val bandC = inverse(ctx.datum, zone, south, pick.second, pick.third)?.latitude?.let(::bandOf)
                ?: throw Fail(CoordinateParseError.POLAR_UNSUPPORTED)
            return gridOk(zone, bandC, sq[0], sq[1], d, ctx, CompletedFrom.MAP)
        }
        val a = anchor!!
        val col: Char
        val row: Char
        if (colIn == null) {
            col = a.square[0]
            row = a.square[1]
        } else {
            if (colIn !in columnLetters(a.zone) || rowIn!! !in ROWS) {
                throw Fail(CoordinateParseError.INVALID_SQUARE, mapOf("square" to "$colIn$rowIn", "zone" to a.zone.toString()))
            }
            col = colIn
            row = rowIn
        }
        val d = digitRule(g1, g2, ctx.kind)
        return gridOk(a.zone, a.band, col, row, d, ctx, CompletedFrom.POINT, a.fromPoint)
    }

    // ---------------------------------------------------------------- UTM

    private fun utmOk(zone: Int, south: Boolean, e: Long, n: Long, ctx: CoordinateParseContext): ParsedReference {
        val ll = inverse(ctx.datum, zone, south, e.toDouble(), n.toDouble())
            ?: throw Fail(CoordinateParseError.POLAR_UNSUPPORTED)
        if (ll.latitude < -80.0 || ll.latitude > 84.0) throw Fail(CoordinateParseError.POLAR_UNSUPPORTED)
        val band = bandOf(ll.latitude) ?: throw Fail(CoordinateParseError.POLAR_UNSUPPORTED)
        val canonical = String.format(Locale.US, "UTM %d%s %d %d", zone, band, e, n)
        return ParsedReference(
            source = CoordinateSource.UTM,
            zone = zone, south = south, band = band,
            easting = e.toDouble(), northing = n.toDouble(),
            canonical = canonical,
            resolvedLatitude = ll.latitude, resolvedLongitude = ll.longitude,
            messages = listOf(StatusMessage("calibration_reads_as", mapOf("text" to canonical))),
        )
    }

    private fun parseUtm(u: String, ctx: CoordinateParseContext): ParsedReference? {
        var words: String? = null
        val m = UTM_RE.matchEntire(u) ?: UTM_WORDS.matchEntire(u)?.also { words = it.groupValues[2] } ?: return null
        val zone = m.groupValues[1].toInt()
        // whole metres only, a 20 digit run is junk not a number
        val e = m.groupValues[3].toLongOrNull()
        val n = m.groupValues[4].toLongOrNull()
        if (zone !in 1..60 || e == null || n == null || e !in 100_000L..900_000L || n !in 0L..10_000_000L) {
            throw Fail(CoordinateParseError.UNRECOGNISED)
        }
        words?.let { return utmOk(zone, it == "SOUTH", e, n, ctx) }
        val letter = m.groupValues[2][0]
        if (letter !in BANDS) throw Fail(CoordinateParseError.UNRECOGNISED)
        val southBand = letter < 'N'
        val lat = inverse(ctx.datum, zone, southBand, e.toDouble(), n.toDouble())?.latitude
        val lo = -80.0 + 8.0 * BANDS.indexOf(letter)
        val hi = lo + if (letter == 'X') 12.0 else 8.0
        if (lat != null && lat >= lo - 0.5 && lat <= hi + 0.5) return utmOk(zone, southBand, e, n, ctx)
        // our own header readout puts the hemisphere there, so 56S ... is southern
        if (letter == 'N' || letter == 'S') return utmOk(zone, letter == 'S', e, n, ctx)
        throw Fail(CoordinateParseError.BAND_MISMATCH, mapOf("band" to letter.toString()))
    }

    // ---------------------------------------------------------------- lat/lon

    private fun latLonOk(lat: Double, lon: Double): ParsedReference {
        if (abs(lat) > 90.0 || abs(lon) > 180.0) throw Fail(CoordinateParseError.OUT_OF_RANGE)
        val canonical = String.format(
            Locale.US, "%.5f° %s, %.5f° %s",
            abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W",
        )
        return ParsedReference(
            source = CoordinateSource.LAT_LON,
            latitude = lat, longitude = lon,
            canonical = canonical,
            resolvedLatitude = lat, resolvedLongitude = lon,
            messages = listOf(StatusMessage("calibration_reads_as", mapOf("text" to canonical))),
        )
    }

    private data class Value(val sign: String, val value: Double, val letter: Char?)

    private fun assign(vals: List<Value>): Pair<Double, Double> {
        val letters = vals.map { it.letter }
        if (letters.any { it != null } && !letters.all { it != null }) throw Fail(CoordinateParseError.UNRECOGNISED)
        if (letters.all { it != null }) {
            // -33.8 S: sign and letter on one value, which one wins? neither
            if (vals.any { it.sign.isNotEmpty() }) throw Fail(CoordinateParseError.UNRECOGNISED)
            val kinds = letters.map { if (it == 'N' || it == 'S') "lat" else "lon" }
            if (kinds.sorted() != listOf("lat", "lon")) throw Fail(CoordinateParseError.UNRECOGNISED)
            var lat = 0.0
            var lon = 0.0
            vals.zip(kinds).forEach { (v, k) ->
                val signed = if (v.letter == 'S' || v.letter == 'W') -v.value else v.value
                if (k == "lat") lat = signed else lon = signed
            }
            return lat to lon
        }
        val (a, b) = vals
        return (if (a.sign == "-") -a.value else a.value) to (if (b.sign == "-") -b.value else b.value)
    }

    private fun num(s: String): Double = s.replace(',', '.').toDouble()

    private val DECIMAL_MODES: List<Pair<String, String>> = listOf(
        "[0-9]+(?:\\.[0-9]+)?" to "(?: ?[,;] ?| )",
        "[0-9]+(?:[.,][0-9]+)?" to " ?; ?",
        "[0-9]+,[0-9]+" to " ",
    )

    private val DECIMAL_PATTERNS: List<Pair<String, Regex>> = DECIMAL_MODES.flatMap { (numRe, sep) ->
        val plain = "([+-]?)($numRe) ?°?"
        val suffix = "([+-]?)($numRe) ?°? ?([NSEWO])"
        val prefix = "([NSEWO]) ?([+-]?)($numRe) ?°?"
        listOf(
            "none" to Regex(plain + sep + plain),
            "suffix" to Regex(suffix + sep + suffix),
            "prefix" to Regex(prefix + sep + prefix),
        )
    }

    private fun parseDecimal(u: String): ParsedReference? {
        for ((style, re) in DECIMAL_PATTERNS) {
            val m = re.matchEntire(u) ?: continue
            val g = m.groupValues
            val vals = when (style) {
                "none" -> listOf(Value(g[1], num(g[2]), null), Value(g[3], num(g[4]), null))
                "suffix" -> listOf(Value(g[1], num(g[2]), g[3][0]), Value(g[4], num(g[5]), g[6][0]))
                else -> listOf(Value(g[2], num(g[3]), g[1][0]), Value(g[5], num(g[6]), g[4][0]))
            }
            val (lat, lon) = assign(vals)
            return latLonOk(lat, lon)
        }
        return null
    }

    // sym: d° m' [s"], spc: d m [s]. groups per value: sym = d, m, s; spc = sd, sm, ss
    private const val DMS_SYM = "([0-9]+) ?° ?([0-9]+(?:\\.[0-9]+)?) ?'(?: ?([0-9]+(?:\\.[0-9]+)?) ?\")?"
    private const val DMS_SPC = "([0-9]+) ([0-9]+(?:\\.[0-9]+)?)(?: ([0-9]+(?:\\.[0-9]+)?))?"
    private const val DMS_SEP = "(?: ?[,;] ?| )"

    private val DMS_PATTERNS: List<Pair<String, Regex>> = listOf(
        "none" to "([+-]?)$DMS_SYM",
        "suffix" to "([+-]?)(?:$DMS_SYM|$DMS_SPC) ?([NSEWO])",
        "prefix" to "([NSEWO]) ?([+-]?)(?:$DMS_SYM|$DMS_SPC)",
    ).map { (style, v) -> style to Regex(v + DMS_SEP + v) }

    private fun parseDms(u: String): ParsedReference? {
        for ((style, re) in DMS_PATTERNS) {
            val m = re.matchEntire(u) ?: continue
            val per = if (style == "none") 4 else 8
            val vals = (0..1).map { i ->
                val base = 1 + i * per
                fun g(k: Int): String? = m.groups[base + k]?.value
                val sign: String
                val letter: Char?
                val d: String?
                val mi: String?
                val se: String?
                when (style) {
                    "none" -> { sign = g(0) ?: ""; letter = null; d = g(1); mi = g(2); se = g(3) }
                    "suffix" -> {
                        sign = g(0) ?: ""; letter = g(7)?.get(0)
                        d = g(1) ?: g(4); mi = g(2) ?: g(5); se = g(3) ?: g(6)
                    }
                    else -> {
                        letter = g(0)?.get(0); sign = g(1) ?: ""
                        d = g(2) ?: g(5); mi = g(3) ?: g(6); se = g(4) ?: g(7)
                    }
                }
                // 52.5' 30" makes no sense
                if (se != null && mi!!.contains('.')) throw Fail(CoordinateParseError.UNRECOGNISED)
                val dv = d!!.toDouble()
                val mv = mi!!.toDouble()
                val sv = se?.toDouble() ?: 0.0
                if (mv >= 60.0 || sv >= 60.0) throw Fail(CoordinateParseError.OUT_OF_RANGE)
                Value(sign, dv + mv / 60.0 + sv / 3600.0, letter)
            }
            val (lat, lon) = assign(vals)
            return latLonOk(lat, lon)
        }
        return null
    }
}
