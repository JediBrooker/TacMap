package com.tacmap.map.render.pdf

import com.tacmap.map.render.TileIndex
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * How live and bake jobs get formed (contract E), pinned by the fixture's
 * jobFormation cases. Pure.
 */
object PdfJobFormation {
    const val MAX_TILES = 6
    const val MAX_SIDE = 3
    const val BAKE_BLOCK_COLS = 3
    const val BAKE_BLOCK_ROWS = 2

    /**
     * Heavy live job: start at [seed] and grow round robin (right, down, left, up)
     * while a whole pass still adds something. Every added tile has to be a pending
     * VISIBLE tile at the seed's z. No world wrap.
     */
    fun grow(seed: TileIndex, isPendingVisible: (TileIndex) -> Boolean): TileJob {
        val z = seed.z
        val n = 1L shl z
        var x0 = seed.x
        var y0 = seed.y
        var cols = 1
        var rows = 1
        fun ok(xs: IntRange, ys: IntRange, nc: Int, nr: Int): Boolean {
            if (nc > MAX_SIDE || nr > MAX_SIDE || nc * nr > MAX_TILES) return false
            for (y in ys) for (x in xs) if (!isPendingVisible(TileIndex(z, x, y))) return false
            return true
        }
        var grew = true
        while (grew) {
            grew = false
            if (x0 + cols < n && ok(x0 + cols..x0 + cols, y0 until y0 + rows, cols + 1, rows)) {
                cols++; grew = true
            }
            if (y0 + rows < n && ok(x0 until x0 + cols, y0 + rows..y0 + rows, cols, rows + 1)) {
                rows++; grew = true
            }
            if (x0 > 0 && ok(x0 - 1..x0 - 1, y0 until y0 + rows, cols + 1, rows)) {
                x0--; cols++; grew = true
            }
            if (y0 > 0 && ok(x0 until x0 + cols, y0 - 1..y0 - 1, cols, rows + 1)) {
                y0--; rows++; grew = true
            }
        }
        return TileJob(z, x0, y0, cols, rows)
    }

    /** bake blocks: aligned 3x2 when heavy, clamped at the world edge. light is 1x1 */
    fun bakeBlock(t: TileIndex, heavy: Boolean): TileJob {
        if (!heavy) return TileJob.single(t.z, t.x, t.y)
        val n = 1L shl t.z
        val x0 = Math.floorDiv(t.x, BAKE_BLOCK_COLS) * BAKE_BLOCK_COLS
        val y0 = Math.floorDiv(t.y, BAKE_BLOCK_ROWS) * BAKE_BLOCK_ROWS
        return TileJob(t.z, x0, y0, min(BAKE_BLOCK_COLS.toLong(), n - x0).toInt(), min(BAKE_BLOCK_ROWS.toLong(), n - y0).toInt())
    }
}

/** one zoom choice in the Generate Offline Tiles confirm */
data class PdfBakeOption(val maxZoom: Int, val tiles: Int, val kept: Boolean)

class PdfBakeOptions(
    val detailZoom: Int,
    val candidates: List<PdfBakeOption>,
) {
    val options: List<PdfBakeOption> get() = candidates.filter { it.kept }

    /** D-1 when it survives, else the biggest survivor */
    val default: PdfBakeOption?
        get() = options.firstOrNull { it.maxZoom == detailZoom + PdfBakePlan.DEFAULT_OFFSET } ?: options.maxByOrNull { it.maxZoom }

    val tooLarge: Boolean get() = options.isEmpty()
}

object PdfBakePlan {
    const val MAX_TILES = 6000
    const val FREE_SPACE_FACTOR = 2.0
    const val BYTES_SAFETY = 1.2
    const val SAMPLE_TILES = 3
    const val COMMIT_EVERY = 64
    const val RENDERER_VERSION = 1
    const val KEY_PREFIX = "tacmap-bake-v1|"
    /**
     * lossless effort when [WEBP_LOSSLESS], the lossy quality otherwise (J3). 100 since r2:
     * 11% smaller whole bake than 85 for 1.3x encode and 1.1x bake wall time (OD-F3)
     */
    const val WEBP_QUALITY = 100
    /** J3: lossy q85 misses the 35 dB gate on red hairlines, lossless is exact */
    const val WEBP_LOSSLESS = true
    /** MBTiles metadata format value */
    const val MBTILES_FORMAT = "webp"
    /** MBTiles metadata name, always this, never the sheet's own name (F2) */
    const val MBTILES_NAME = "TacMap offline tiles"
    const val MIN_ZOOM = 0
    /** J3 gate both platforms test the bake against, baked vs live */
    const val PSNR_GATE_DB = 35.0
    /** maxZoom candidates are D plus these, clamped to 0..22 */
    val CANDIDATE_OFFSETS = listOf(-2, -1, 0)
    /** the default is D plus this when it survives */
    const val DEFAULT_OFFSET = -1

    /**
     * Candidates {D-2, D-1, D} clamped to 0..22, tiles = sum of coverage counts over 0..m.
     * Each level gets counted with the limit that's left ([countAt] returns early past it),
     * and once the running total passes 6000 at level z counting stops: every candidate
     * m >= z is dropped (its tiles is just the lower bound we got to), the ones below keep
     * their exact count (J2, S2). [ensureActive] runs between levels.
     */
    fun options(
        detailZoom: Int,
        ensureActive: () -> Unit = {},
        countAt: (z: Int, limit: Int) -> Int,
    ): PdfBakeOptions {
        val cands = sortedSetOf<Int>()
        for (off in CANDIDATE_OFFSETS) cands += (detailZoom + off).coerceIn(0, 22)
        val cumulative = HashMap<Int, Long>()
        var running = 0L
        var passedAt = Int.MAX_VALUE
        val top = cands.last()
        for (z in 0..top) {
            ensureActive()
            val limit = (MAX_TILES - running).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            running += countAt(z, limit)
            cumulative[z] = running
            if (running > MAX_TILES) {
                passedAt = z
                break
            }
        }
        val out = cands.map { m ->
            val tiles = cumulative[m] ?: running
            PdfBakeOption(m, tiles.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), m < passedAt && tiles <= MAX_TILES)
        }
        return PdfBakeOptions(detailZoom, out)
    }

    /** bakeKey = sha256("tacmap-bake-v1|" + canonical georef json + "|" + tilePx + "|" + rendererVersion) */
    fun bakeKey(canonicalGeorefJson: String, tilePx: Int): String {
        val text = KEY_PREFIX + canonicalGeorefJson + "|" + tilePx + "|" + RENDERER_VERSION
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

}

/**
 * Bake job formation per level (contract E2), pinned by the fixture's bakeJobFormation.
 * Raster levels (z <= baseMaxZoom) are always 1x1. Vector levels read heavy once when the
 * level starts and stick with it, a flip mid level shows up at the next one. Tiles walk
 * row major and a job forms at the first tile no earlier job of the level covers.
 */
object PdfBakeJobs {
    /** a bake job and how many of the level's crop tiles it writes */
    data class Job(val job: TileJob, val wanted: Int)

    class LevelPlan internal constructor(
        val z: Int,
        /** z <= baseMaxZoom: sampled off the base raster, no pdfium */
        val raster: Boolean,
        /** the heavy flag this level runs with, always false on raster levels */
        val heavy: Boolean,
        private val xs: IntArray,
        private val ys: IntArray,
    ) {
        /** formed lazily in dispatch order, same answer however late heavy flips */
        fun jobs(): Sequence<Job> = sequence {
            val wanted = LongSet(xs.size * 2)
            for (i in xs.indices) wanted.add(key(xs[i], ys[i]))
            val covered = LongSet(xs.size * 2)
            for (i in xs.indices) {
                if (covered.contains(key(xs[i], ys[i]))) continue
                val job = PdfJobFormation.bakeBlock(TileIndex(z, xs[i], ys[i]), heavy)
                var n = 0
                for (y in job.y0 until job.y0 + job.rows) for (x in job.x0 until job.x0 + job.cols) {
                    val k = key(x, y)
                    if (wanted.contains(k) && covered.add(k)) n++
                }
                yield(Job(job, n))
            }
        }

        private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    }

    /**
     * [xs]/[ys] = the level's crop tiles, row major. [baseMaxZoom] < 0 = no base raster,
     * every level is vector. [heavyNow] gets asked at most once, right here.
     */
    fun level(z: Int, xs: IntArray, ys: IntArray, baseMaxZoom: Int, heavyNow: () -> Boolean): LevelPlan {
        val raster = z <= baseMaxZoom
        return LevelPlan(z, raster, !raster && heavyNow(), xs, ys)
    }

    fun level(l: PdfFootprint.Level, baseMaxZoom: Int, heavyNow: () -> Boolean): LevelPlan =
        level(l.z, l.xs, l.ys, baseMaxZoom, heavyNow)
}

/**
 * The bake estimate (contract J2), one pure function, pinned by the fixture's
 * bakeEstimate. Only the samples and the free space read come from outside.
 */
object PdfBakeEstimate {
    const val FALLBACK_JOB_MS = 100.0
    const val FALLBACK_ENCODE_MS = 5.0
    /** J3: shared fallback, ios jp2 on USGS at 768 px measured 248349 mean (android webp 142855), rounded up */
    const val FALLBACK_TILE_BYTES = 250_000L

    enum class SampleResult { IMAGE, EMPTY, FAILED }

    /** one sample job: [jobMs] dispatch to result, [bytes]/[encodeMs] from the bake encoder */
    data class Sample(val result: SampleResult, val jobMs: Double, val bytes: Long = 0, val encodeMs: Double = 0.0)

    enum class From { EWMA, SAMPLES, FALLBACK }

    data class OptionEstimate(
        val maxZoom: Int,
        val tiles: Int,
        val jobsPerLevel: List<Int>,
        val jobs: Int,
        val bytes: Long,
        val neededBytes: Long,
        val enoughSpace: Boolean,
        val estimatedMs: Double,
        val minutes: Int,
    )

    data class Result(
        val heavy: Boolean,
        val jobMs: Double,
        val jobMsFrom: From,
        val encodeMs: Double,
        val encodeMsFrom: From,
        val meanTileBytes: Double,
        val meanTileBytesFrom: From,
        val options: List<OptionEstimate>,
        /** maxZoom of the option selected when the confirm opens */
        val initialSelection: Int,
        val generateEnabled: Boolean,
    ) {
        val initial: OptionEstimate get() = options.first { it.maxZoom == initialSelection }
    }

    /** a picked sample tile and its squared distance (tile units) to the crop centre */
    data class Pick(val x: Int, val y: Int, val d2: Double)

    /**
     * Which tiles get sampled at [level]'s z: the INSIDE ones (every crop tile when there
     * are none), ranked by d2 to [centre] (cx, cy in tiles at z) with ties by y then x,
     * first 3. With no centre (toWGS84 failed) the first 3 of the pool, row major.
     */
    fun samplePicks(level: PdfFootprint.Level, centre: DoubleArray?): List<Pick> = sampleSelection(level, centre).picks

    /** the picks plus where they came from, [insidePool] false = no INSIDE tile so every crop tile */
    class SampleSelection(val insidePool: Boolean, val poolCount: Int, val picks: List<Pick>)

    fun sampleSelection(level: PdfFootprint.Level, centre: DoubleArray?): SampleSelection {
        val inside = (0 until level.count).filter { level.coverage[it] == TileCoverage.INSIDE }
        val pool = inside.ifEmpty { (0 until level.count).toList() }
        fun d2(i: Int): Double {
            if (centre == null) return 0.0
            val dx = level.xs[i] + 0.5 - centre[0]
            val dy = level.ys[i] + 0.5 - centre[1]
            return dx * dx + dy * dy
        }
        val ranked = if (centre == null) pool
        else pool.sortedWith(compareBy<Int>({ d2(it) }, { level.ys[it] }, { level.xs[it] }))
        val picks = ranked.take(PdfBakePlan.SAMPLE_TILES).map { Pick(level.xs[it], level.ys[it], d2(it)) }
        return SampleSelection(inside.isNotEmpty(), pool.size, picks)
    }

    /** the crop centre in tiles at [z], from toWGS84(clipMean) in z0 world units, null if that failed */
    fun centreTile(worldX0: Double?, worldY0: Double?, z: Int): DoubleArray? {
        if (worldX0 == null || worldY0 == null || !worldX0.isFinite() || !worldY0.isFinite()) return null
        val n = Math.scalb(1.0, z)
        return doubleArrayOf(worldX0 * n / 256.0, worldY0 * n / 256.0)
    }

    /**
     * [levels] gives the crop tiles per z (row major), [options] the kept candidates with
     * their tiles, [ewmaMs] the document's EWMA after the samples ran (null = no sample
     * yet), [freeBytes] null when it couldn't be read.
     */
    fun estimate(
        levels: (Int) -> PdfFootprint.Level,
        baseMaxZoom: Int,
        options: List<PdfBakeOption>,
        defaultMaxZoom: Int,
        samples: List<Sample>,
        ewmaMs: Double?,
        freeBytes: Long?,
    ): Result {
        val counted = samples.filter { it.result == SampleResult.IMAGE }
        val n = counted.size
        val (jobMs, jobFrom) = when {
            ewmaMs != null -> ewmaMs to From.EWMA
            n > 0 -> counted.sumOf { it.jobMs } / n to From.SAMPLES
            else -> FALLBACK_JOB_MS to From.FALLBACK
        }
        val (encodeMs, encodeFrom) = if (n > 0) counted.sumOf { it.encodeMs } / n to From.SAMPLES
        else FALLBACK_ENCODE_MS to From.FALLBACK
        val (meanBytes, bytesFrom) = if (n > 0) counted.sumOf { it.bytes }.toDouble() / n to From.SAMPLES
        else FALLBACK_TILE_BYTES.toDouble() to From.FALLBACK
        val heavy = ewmaMs != null && ewmaMs > PdfRenderScheduling.HEAVY_THRESHOLD_MS
        val perLevel = HashMap<Int, Int>()
        val out = options.map { o ->
            val per = (0..o.maxZoom).map { z ->
                perLevel.getOrPut(z) { PdfBakeJobs.level(levels(z), baseMaxZoom) { heavy }.jobs().count() }
            }
            val jobs = per.sum()
            val bytes = ceil(o.tiles.toDouble() * meanBytes * PdfBakePlan.BYTES_SAFETY).toLong()
            val needed = (PdfBakePlan.FREE_SPACE_FACTOR * bytes).toLong()
            val ms = jobs.toDouble() * jobMs + o.tiles.toDouble() * encodeMs
            OptionEstimate(
                maxZoom = o.maxZoom,
                tiles = o.tiles,
                jobsPerLevel = per,
                jobs = jobs,
                bytes = bytes,
                neededBytes = needed,
                enoughSpace = freeBytes == null || freeBytes >= needed,
                estimatedMs = ms,
                minutes = max(1, ceil(ms / 60_000.0).toInt()),
            )
        }
        val fits = out.filter { it.enoughSpace }.map { it.maxZoom }
        val initial = when {
            defaultMaxZoom in fits -> defaultMaxZoom
            fits.isNotEmpty() -> fits.max()
            else -> defaultMaxZoom
        }
        return Result(heavy, jobMs, jobFrom, encodeMs, encodeFrom, meanBytes, bytesFrom, out, initial, initial in fits)
    }
}

/**
 * The bake UI's numbers (OD-F7), one pure function per platform pinned by the fixture's
 * bakeFormat: option rows, the Layers info row and the noSpace message all come through
 * here. Separators go by the locale's language only (PAR-R2-1): de is . and , everything
 * else en's , and . so a de-CH or fr-FR region can't sneak in a ' or a space. Callers
 * pass the app UI language. Tile counts are grouped integers. Sizes are decimal MB (10^6), integer half
 * up: one decimal under 100 MB, whole MB from there, GB with one decimal from 1000 MB, at
 * least 0.1 MB for anything > 0, a plain space before the unit
 */
object PdfBakeFormat {
    fun tiles(count: Long, locale: java.util.Locale): String = grouped(count, symbols(locale).groupingSeparator)

    fun size(bytes: Long, locale: java.util.Locale): String {
        val sym = symbols(locale)
        val b = bytes.coerceAtLeast(0)
        var tenthsMb = (b + 50_000) / 100_000
        if (b > 0 && tenthsMb == 0L) tenthsMb = 1
        if (tenthsMb < 1000) return grouped(tenthsMb / 10, sym.groupingSeparator) + sym.decimalSeparator + (tenthsMb % 10) + " MB"
        val wholeMb = (b + 500_000) / 1_000_000
        if (wholeMb < 1000) return grouped(wholeMb, sym.groupingSeparator) + " MB"
        val tenthsGb = (b + 50_000_000) / 100_000_000
        return grouped(tenthsGb / 10, sym.groupingSeparator) + sym.decimalSeparator + (tenthsGb % 10) + " GB"
    }

    private class Separators(val groupingSeparator: Char, val decimalSeparator: Char)

    private fun symbols(locale: java.util.Locale) =
        if (locale.language == "de") Separators('.', ',') else Separators(',', '.')

    private fun grouped(n: Long, sep: Char): String {
        val digits = kotlin.math.abs(n).toString()
        val sb = StringBuilder()
        for ((i, ch) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(sep)
            sb.append(ch)
        }
        return (if (n < 0) "-" else "") + sb
    }
}
