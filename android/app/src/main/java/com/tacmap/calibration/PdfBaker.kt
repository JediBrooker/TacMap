package com.tacmap.calibration

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.tacmap.map.render.TileIndex
import com.tacmap.map.render.TileSource
import com.tacmap.map.render.pdf.PdfBakeJobs
import com.tacmap.map.render.pdf.PdfBakePlan
import com.tacmap.map.render.pdf.PdfFootprint
import com.tacmap.map.render.pdf.PdfTileSource
import com.tacmap.util.DataKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.atan
import kotlin.math.sinh

/** why a bake stopped (contract J). interrupted only ever comes from the launch notice */
enum class PdfBakeError { NOT_CALIBRATED, TOO_LARGE, NO_SPACE, WRITE_FAILED, RENDER_FAILED, SOURCE_CHANGED, INTERRUPTED }

class PdfBakeException(val error: PdfBakeError, cause: Throwable? = null) : Exception(error.name, cause)

/** what recording a finished bake in the library got (R6 shapes) */
sealed class PdfBakeAttach {
    data object Attached : PdfBakeAttach()
    /** the entry moved on: recalibrated, reimported, deleted */
    data object SourceChanged : PdfBakeAttach()
    data class WriteFailed(val cause: Throwable?) : PdfBakeAttach()
}

/**
 * Where a published bake gets recorded: the PDF's library entry, one sealed write on the
 * main thread (M3). MapViewModel plugs itself in, with no screen alive the library gets it
 * directly (LibraryBakeRecorder). One that can't write says writeFailed and the file goes again.
 */
interface PdfBakeRecorder {
    fun attach(entryId: String?, contentKey: String?, renderGuardToken: String, bake: PersistedPdfBake): PdfBakeAttach

    /** a cancel landed while we attached: take it back off. true = it's off */
    fun detach(entryId: String?, bake: PersistedPdfBake): Boolean
}

/** J2 error mapping for the write side, pure so the JVM tests can pin it */
internal object PdfBakeErrors {
    /**
     * A full disk anywhere in the cause chain: SQLiteFullException / SQLITE_FULL, or an
     * IOException / ErrnoException carrying ENOSPC. Matched by class name + message so it
     * works without the android classes (they're stubs on the JVM)
     */
    fun isNoSpace(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < 8) {
            if (e.javaClass.name == "android.database.sqlite.SQLiteFullException") return true
            val msg = e.message.orEmpty()
            if ("ENOSPC" in msg || "SQLITE_FULL" in msg || "No space left" in msg || "database or disk is full" in msg) return true
            e = e.cause
        }
        return false
    }

    /** writing, committing, moving or fsyncing went wrong: noSpace for a full disk, else writeFailed */
    fun classifyWrite(t: Throwable?): PdfBakeError = if (isNoSpace(t)) PdfBakeError.NO_SPACE else PdfBakeError.WRITE_FAILED
}

/**
 * Generate Offline Tiles, the same tile renderer as the live map at the lowest
 * band (replaces PdfTiler). Writes a .partial in pdf_bake_work (outside the reconcile
 * roots, reconcile deletes any .partial it sees in offline_tiles), commits every 64
 * tiles, then publishes into offline_tiles and attaches it to the PDF's library entry.
 * The PDF stays, the active map never changes (D5-05, D5-06).
 */
internal object PdfBaker {
    private const val TAG = "PdfBaker"
    const val WORK_DIR = "pdf_bake_work"
    const val PUBLISH_DIR = "offline_tiles"

    fun workDir(context: Context) = File(context.filesDir, WORK_DIR)

    /** nothing can be running at process start, so whatever is in there is dead */
    fun cleanWorkDirectory(context: Context) {
        workDir(context).listFiles()?.forEach { f ->
            if (f.isFile) runCatching { f.delete() }
        }
    }

    /**
     * The bake encoder (J3). Lossless WebP: lossy q85 is 4:2:0 and red hairlines on paper
     * only made 32-34 dB against the live render, lossless is the live pixels exactly.
     * [PdfBakePlan.WEBP_QUALITY] is the lossless effort knob there, not a quality. Pre API 30
     * the old WEBP enum at 100 is libwebp's lossless mode.
     *
     * Size (OD-F3): skia's lossless path always runs libwebp at method 0, the quality is the
     * only thing we get to turn, so it can't match a desktop m4 encode (that needs a native
     * encoder, a follow up). It's at 100, the most LZ77 effort m0 has. And we drop
     * the VP8X + ICCP chunks skia wraps every tile in (474 bytes of sRGB profile, sRGB is what
     * a webp means without one anyway), see [stripToSimpleLossless]. Fully transparent pixels
     * already come out 0,0,0,0: the bitmap is premultiplied so there's no colour left under
     * alpha 0 for libwebp to keep
     */
    @Suppress("DEPRECATION")
    fun encode(bmp: Bitmap): ByteArray {
        val r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val format = when {
            r && PdfBakePlan.WEBP_LOSSLESS -> Bitmap.CompressFormat.WEBP_LOSSLESS
            r -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.WEBP
        }
        val quality = if (!r && PdfBakePlan.WEBP_LOSSLESS) 100 else PdfBakePlan.WEBP_QUALITY
        val bytes = ByteArrayOutputStream(64 * 1024).use { out ->
            check(bmp.compress(format, quality, out)) { "webp encode failed" }
            out.toByteArray()
        }
        return if (PdfBakePlan.WEBP_LOSSLESS) stripToSimpleLossless(bytes) else bytes
    }

    /**
     * RIFF/WEBP with just VP8X + ICCP + VP8L in it -> the simple lossless layout, RIFF/WEBP
     * with the VP8L chunk alone. Same bitstream, same pixels (VP8L carries its own alpha).
     * Anything else (animation, EXIF, a lossy VP8 chunk, odd sizes) comes back untouched
     */
    internal fun stripToSimpleLossless(webp: ByteArray): ByteArray {
        fun u32(at: Int): Long = (webp[at].toLong() and 0xff) or ((webp[at + 1].toLong() and 0xff) shl 8) or
            ((webp[at + 2].toLong() and 0xff) shl 16) or ((webp[at + 3].toLong() and 0xff) shl 24)
        fun tag(at: Int) = String(webp, at, 4, Charsets.US_ASCII)
        if (webp.size < 20 || tag(0) != "RIFF" || tag(8) != "WEBP") return webp
        if (u32(4) + 8 != webp.size.toLong()) return webp
        var at = 12
        var vp8l: IntRange? = null
        while (at + 8 <= webp.size) {
            val t = tag(at)
            val len = u32(at + 4)
            val end = at + 8 + len + (len and 1)
            if (end > webp.size) return webp
            when (t) {
                "VP8X", "ICCP" -> Unit
                "VP8L" -> if (vp8l == null) vp8l = at until (at + 8 + len.toInt()) else return webp
                else -> return webp
            }
            at = end.toInt()
        }
        val chunk = vp8l ?: return webp
        if (chunk.first == 12) return webp
        val body = webp.copyOfRange(chunk.first, chunk.last + 1)
        val padded = body.size + (body.size and 1)
        val out = ByteArray(12 + padded)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        val riff = (4 + padded).toLong()
        for (k in 0 until 4) out[4 + k] = ((riff shr (8 * k)) and 0xff).toByte()
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(out, 8)
        body.copyInto(out, 12)
        return out
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun bake(
        context: Context,
        source: PdfTileSource,
        pdf: PdfMapSource,
        maxZoom: Int,
        bakeKey: String,
        /** the library side of the publish, null = nobody to record it, fails closed */
        recorder: PdfBakeRecorder?,
        /** called once the bake is attached to its entry for good, even if a cancel lands right after (F1) */
        onPublished: (PersistedPdfBake) -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit,
    ): PersistedPdfBake {
        val outer = coroutineContext.job
        val footprint = source.footprint
        // capped, the options already said this fits under MAX_TILES. a level past it bails early
        val levels = ArrayList<PdfFootprint.Level>()
        var total = 0
        for (z in 0..maxZoom) {
            coroutineContext.ensureActive()
            val l = footprint.level(z, PdfBakePlan.MAX_TILES - total)
            if (l.truncated || total + l.count > PdfBakePlan.MAX_TILES) throw PdfBakeException(PdfBakeError.TOO_LARGE)
            levels += l
            total += l.count
        }
        val work = workDir(context).apply { mkdirs() }
        val uuid = UUID.randomUUID().toString()
        val partial = File(work, "$uuid.mbtiles.partial")
        // one writer thread for the whole bake: an android sqlite transaction belongs to the
        // thread that began it, hopping between IO threads deadlocks on the connection
        val writerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "PdfBakeWriter") }
        val writerThread = writerExecutor.asCoroutineDispatcher()
        var createError: Throwable? = null
        var made: MBTilesWriter? = null
        val writer = try {
            withContext(writerThread) { MBTilesWriter.create(partial.path) { createError = it }.also { made = it } }
        } catch (t: Throwable) {
            // cancelled while create ran (Delete Map right as the bake starts): withContext
            // throws on the way back but the .partial is on disk with an open connection. close
            // it and bin the file, otherwise it sits in pdf_bake_work till the next launch
            withContext(NonCancellable + writerThread) {
                made?.abort()
                deleteMBTilesArtifacts(partial)
            }
            writerThread.close()
            throw t
        } ?: run {
            writerThread.close()
            throw PdfBakeException(PdfBakeErrors.classifyWrite(createError), createError)
        }
        val encodeLane = Dispatchers.Default.limitedParallelism(2)
        var done = 0
        var inBatch = 0
        var published = false

        /** anything the writer throws or flags: full disk -> noSpace, the rest writeFailed */
        suspend fun <T> write(block: () -> T): T {
            val out = try {
                withContext(writerThread) { block() }
            } catch (c: CancellationException) {
                throw c
            } catch (e: PdfBakeException) {
                throw e
            } catch (t: Throwable) {
                throw PdfBakeException(PdfBakeErrors.classifyWrite(t), t)
            }
            if (writer.hadError) throw PdfBakeException(PdfBakeErrors.classifyWrite(writer.firstError), writer.firstError)
            return out
        }
        try {
            val wb = footprint.worldBounds
            write {
                writer.writeMetadata(
                    // never the sheet's own name, that stays in the sealed session (F2)
                    name = PdfBakePlan.MBTILES_NAME,
                    format = PdfBakePlan.MBTILES_FORMAT,
                    minZoom = PdfBakePlan.MIN_ZOOM,
                    maxZoom = maxZoom,
                    bounds = Wgs84Bounds(
                        Wgs84Coordinate(latOf(wb[3]), lonOf(wb[0])),
                        Wgs84Coordinate(latOf(wb[1]), lonOf(wb[2])),
                    ),
                    extras = mapOf(
                        "tacmap_bake_key" to bakeKey,
                        "tacmap_tile_px" to source.tilePx.toString(),
                        "tacmap_renderer" to PdfBakePlan.RENDERER_VERSION.toString(),
                    ),
                )
                writer.beginBatch()
            }
            onProgress(0, total)
            for (level in levels) {
                coroutineContext.ensureActive()
                // raster levels 1x1, vector levels read heavy once right here and keep it (E2)
                val plan = PdfBakeJobs.level(level, source.baseMaxZoom) { source.heavy }
                val wanted = HashSet<TileIndex>(level.count * 2)
                for (i in 0 until level.count) wanted += TileIndex(level.z, level.xs[i], level.ys[i])
                val written = HashSet<TileIndex>(level.count * 2)
                for (j in plan.jobs()) {
                    coroutineContext.ensureActive()
                    pauseWhileHot(context)
                    val rendered = try {
                        source.renderForBake(j.job)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Throwable) {
                        Log.w(TAG, "bake job failed (${e.javaClass.simpleName})")
                        throw PdfBakeException(PdfBakeError.RENDER_FAILED, e)
                    }
                    // only this job's crop tiles get written, each once
                    val mine = rendered.keys.filter { it in wanted && written.add(it) }.toSet()
                    val keep = rendered.filter { (k, bmp) -> k in mine && bmp !== TileSource.EMPTY }
                    for ((k, bmp) in rendered) if (k !in keep && bmp !== TileSource.EMPTY) bmp.recycle()
                    val encoded = try {
                        coroutineScope {
                            keep.map { (k, bmp) -> async(encodeLane) { k to encode(bmp).also { bmp.recycle() } } }.awaitAll()
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Throwable) {
                        Log.w(TAG, "bake encode failed (${e.javaClass.simpleName})")
                        throw PdfBakeException(PdfBakeError.RENDER_FAILED, e)
                    }
                    write {
                        for ((k, bytes) in encoded) {
                            writer.putTile(k.z, k.x, k.y, bytes)
                            inBatch++
                            if (inBatch >= PdfBakePlan.COMMIT_EVERY) {
                                writer.commitBatch()
                                writer.beginBatch()
                                inBatch = 0
                            }
                        }
                    }
                    done += mine.size
                    onProgress(minOf(done, total), total)
                }
            }
            write {
                writer.commitBatch()
                writer.close()
            }
            // journal_mode + synchronous are OFF while writing, so nothing is on disk for sure
            // yet. fsync before the move, or a power cut can leave the session pointing at a
            // torn file that sqlite then quietly deletes (S3)
            withContext(Dispatchers.IO) {
                try {
                    java.io.RandomAccessFile(partial, "rw").use { it.fd.sync() }
                } catch (t: Throwable) {
                    throw PdfBakeException(PdfBakeErrors.classifyWrite(t), t)
                }
            }
            // the pause relocked the mission key (Home, power button, a call): park the finished
            // file right here till the app's own unlock. attaching behind the lock can only fail
            // and bin minutes of baking (WP2 E, the bake keeps going in the background). a cancel
            // meanwhile cleans the .partial up like any other
            DataKey.awaitUnlocked()
            // the publish runs to its end whatever happens (C4, F1): a cancel can only win before
            // the session is touched, after that it gets undone or it stands
            val bake = withContext(NonCancellable + Dispatchers.IO) {
                publish(context, partial, uuid, pdf, bakeKey, maxZoom, source.tilePx, outer, recorder) {
                    published = true
                    onPublished(it)
                }
            }
            return bake
        } finally {
            try {
                if (!published) {
                    // delete on the writer thread too, before hopping back. a cancelled bake gets a
                    // CancellationException on the way back out of withContext (prompt cancellation),
                    // so anything after it in here never ran and the .partial sat there till next launch
                    withContext(NonCancellable + writerThread) {
                        writer.abort()
                        deleteMBTilesArtifacts(partial)
                    }
                }
            } finally {
                writerThread.close()
            }
        }
    }

    /**
     * Move it in under the managed files lock and fsync the dir so the rename sticks. From
     * the moment it's in offline_tiles until its record is written it's in flight, so neither
     * the reconcile nor the bake sweep (both under that lock, both skip in flight files) can
     * reap a file nothing points at yet. Then record it on the PDF's library entry, on main
     * like every other library write. If the entry moved on (another file, a new georef) the
     * file goes again and the bake reports sourceChanged.
     */
    private suspend fun publish(
        context: Context,
        partial: File,
        uuid: String,
        pdf: PdfMapSource,
        bakeKey: String,
        maxZoom: Int,
        tilePx: Int,
        /** the bake's own job, we're inside NonCancellable so ask it directly */
        outer: kotlinx.coroutines.Job,
        recorder: PdfBakeRecorder?,
        onAttached: (PersistedPdfBake) -> Unit,
    ): PersistedPdfBake {
        val dir = File(context.filesDir, PUBLISH_DIR)
        val out = File(dir, "tacmap-bake-$uuid.mbtiles")
        val busy = listOf(out) + ImportedMapLibraryStore.SQLITE_SIDECARS.map { File(dir, out.name + it) }
        busy.forEach(InFlightImportFiles::register)
        try {
            ActiveMapSelectionStore.withManagedFilesLock {
                deleteMBTilesSidecars(partial)
                dir.mkdirs()
                try {
                    Files.move(partial.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    try {
                        Files.move(partial.toPath(), out.toPath())
                    } catch (e: Exception) {
                        throw PdfBakeException(PdfBakeErrors.classifyWrite(e), e)
                    }
                } catch (e: Exception) {
                    throw PdfBakeException(PdfBakeErrors.classifyWrite(e), e)
                }
                try {
                    fsyncDirectory(dir)
                } catch (e: Exception) {
                    deleteMBTilesArtifacts(out)
                    throw PdfBakeException(PdfBakeErrors.classifyWrite(e), e)
                }
            }
            val bake = PersistedPdfBake(out.name, bakeKey, PdfBakePlan.MIN_ZOOM, maxZoom, tilePx, out.length())
            val token = pdf.render.renderGuardToken
            val contentKey = pdf.contentKey ?: pdf.render.contentKey
            // last call for a cancel, nothing's recorded yet
            if (!outer.isActive) {
                ActiveMapSelectionStore.withManagedFilesLock { deleteMBTilesArtifacts(out) }
                throw CancellationException("bake cancelled before publish")
            }
            var attached: PdfBakeAttach? = null
            while (attached == null) {
                // checked on main right before the write, so a pause (main too) can't land in
                // between. null = another pause got in after the unlock we waited for. the file's
                // in flight, nothing reaps it, so wait for the next one
                attached = withContext(Dispatchers.Main) {
                    if (DataKey.isRelocked) null
                    else recorder?.attach(pdf.entryId, contentKey, token, bake) ?: PdfBakeAttach.WriteFailed(null)
                }
                if (attached == null && !awaitUnlockOrCancel(outer)) {
                    ActiveMapSelectionStore.withManagedFilesLock { deleteMBTilesArtifacts(out) }
                    throw CancellationException("bake cancelled while the key was locked")
                }
            }
            when (attached) {
                PdfBakeAttach.Attached -> Unit
                PdfBakeAttach.SourceChanged -> {
                    ActiveMapSelectionStore.withManagedFilesLock { deleteMBTilesArtifacts(out) }
                    throw PdfBakeException(PdfBakeError.SOURCE_CHANGED)
                }
                is PdfBakeAttach.WriteFailed -> {
                    ActiveMapSelectionStore.withManagedFilesLock { deleteMBTilesArtifacts(out) }
                    val error = if (PdfBakeErrors.isNoSpace(attached.cause) || diskNearlyFull(context)) PdfBakeError.NO_SPACE
                    else PdfBakeError.WRITE_FAILED
                    throw PdfBakeException(error, attached.cause)
                }
            }
            // cancelled while we attached: take it back off so nothing says it's there (F1)
            if (!outer.isActive) {
                val detached = withContext(Dispatchers.Main) { recorder?.detach(pdf.entryId, bake) == true }
                if (detached) {
                    ActiveMapSelectionStore.withManagedFilesLock { deleteMBTilesArtifacts(out) }
                    throw CancellationException("bake cancelled during publish")
                }
                // couldn't undo it, so it stands and gets reported like any finished bake
            }
            onAttached(bake)
            return bake
        } finally {
            busy.forEach(InFlightImportFiles::release)
        }
    }

    /**
     * The unlock wait from inside the NonCancellable publish, so it looks at the bake's own job
     * every second as well. false = cancelled first
     */
    private suspend fun awaitUnlockOrCancel(outer: kotlinx.coroutines.Job): Boolean {
        while (outer.isActive) {
            if (withTimeoutOrNull(1_000) { DataKey.awaitUnlocked() } != null) return true
        }
        return false
    }

    /**
     * The library write only says false, never why. When it fails with the volume this
     * close to full, call it noSpace (R6), the library file is tiny so anything else really
     * is a write problem
     */
    private fun diskNearlyFull(context: Context): Boolean =
        runCatching { android.os.StatFs(context.filesDir.path).availableBytes < NEARLY_FULL_BYTES }.getOrDefault(false)

    private const val NEARLY_FULL_BYTES = 1L shl 20

    /**
     * fsync a directory so a rename into it is durable. java can't open a dir on android
     * (libcore throws EISDIR), so straight to Os. some filesystems refuse fsync on a dir
     * with EINVAL, nothing to be done there, everything else goes up
     */
    private fun fsyncDirectory(dir: File) {
        val fd = android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
        try {
            android.system.Os.fsync(fd)
        } catch (e: android.system.ErrnoException) {
            if (e.errno != android.system.OsConstants.EINVAL) throw e
        } finally {
            runCatching { android.system.Os.close(fd) }
        }
    }

    /** critical/severe thermal: sit tight until it cools (API 29+) */
    private suspend fun pauseWhileHot(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        var waited = 0L
        while (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            coroutineContext.ensureActive()
            delay(2_000)
            waited += 2_000
            if (waited % 60_000 == 0L) Log.i(TAG, "bake paused for heat, ${waited / 1000}s")
        }
    }

    private fun lonOf(worldX: Double): Double = worldX / 256.0 * 360.0 - 180.0
    private fun latOf(worldY: Double): Double = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * worldY / 256.0))))
}
