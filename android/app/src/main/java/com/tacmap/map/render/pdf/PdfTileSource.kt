package com.tacmap.map.render.pdf

import android.graphics.Bitmap
import com.tacmap.calibration.MBTilesStore
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfPageGeometry
import com.tacmap.map.render.TileIndex
import com.tacmap.map.render.TileSource
import com.tacmap.map.render.decodeBoundedTile
import com.tacmap.map.render.pdf.PdfRenderRules.reasonOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import kotlin.math.min

/** render switches the instrumented tests flip */
internal object PdfTileRenderFlags {
    /** live tiles as Config.HARDWARE (API 28+). tests that read pixels turn it off */
    @Volatile var hardwareTiles: Boolean = true
}

/** crash guard hooks for one PDF (contract I), all no-ops when there's no token */
internal class PdfGuardHooks(
    private val guard: PdfRenderGuard,
    private val token: String,
    /** the token has to be in the sealed session before anything arms on it */
    private val ensureTokenDurable: () -> Boolean,
) {
    @Volatile private var durable = false

    /** arm [kind] if this token never got it through, returns whether to complete afterwards */
    fun armIfNeeded(kind: GuardKind): Boolean {
        if (guard.isVerified(kind, token)) return false
        if (!durable) durable = runCatching(ensureTokenDurable).getOrDefault(false)
        if (!durable) return false
        return guard.arm(kind, token, foreground = PdfRenderExecutor.foreground)
    }

    fun complete(kind: GuardKind) = guard.complete(kind, token)
}

/** a published bake, read on IO. only used while its key + tilePx match (contract F) */
internal class PdfBakeReader private constructor(private val store: MBTilesStore, val minZoom: Int, val maxZoom: Int) : Closeable {
    fun tile(t: TileIndex): Bitmap? {
        if (t.z !in minZoom..maxZoom) return null
        val bytes = store.tileData(t.z, t.x, t.y) ?: return null
        return decodeBoundedTile(bytes)
    }

    override fun close() = store.close()

    companion object {
        fun open(file: File, bakeKey: String, tilePx: Int): PdfBakeReader? {
            val store = MBTilesStore.open(file.path) ?: return null
            val ok = store.rawMetadata("tacmap_bake_key") == bakeKey &&
                store.rawMetadata("tacmap_tile_px") == tilePx.toString()
            if (!ok) {
                store.close()
                return null
            }
            return PdfBakeReader(store, store.metadata.minZoom, store.metadata.maxZoom)
        }
    }
}

/**
 * An imported PDF as a Web Mercator raster tile source (WP2). Tiles are drawn on
 * demand from the page through georef.toPage with the adaptive warp, at display
 * density. Load order (contract F): failed -> nothing, off sheet -> EMPTY, orphan
 * cache, baked tile, base raster sample (z <= baseMaxZoom), live vector job.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PdfTileSource(
    val session: PdfRenderSession,
    val georef: PdfGeoreference,
    val geometry: PdfPageGeometry,
    val footprint: PdfFootprint,
    val policy: PdfZoomPolicy,
    val tilePx: Int,
    private val budgetPx: Int,
    private val guard: PdfGuardHooks?,
    override val cacheKey: String,
    /** the bake's own source: everything at the bake band, and it keeps going in the background */
    private val forBake: Boolean = false,
    val auditSourceId: String? = null,
) : TileSource {
    val auditInstanceId: String? = if (com.tacmap.BuildConfig.DEBUG && com.tacmap.app.DebugLaunchHooks.deviceAudit) java.util.UUID.randomUUID().toString() else null

    override val minZoom: Int = 0
    override val maxZoom: Int = policy.detailZoom
    override val tileSizePx: Int = tilePx

    /** fixed for the source's life, no mid session halving (R1) */
    val basePlan: PdfBaseRasterPlan = policy.baseRasterPlan(footprint, budgetPx)
    val baseMaxZoom: Int = policy.baseMaxZoom(basePlan, tilePx)

    private val _status = MutableStateFlow<PdfRenderStatus>(PdfRenderStatus.Preparing)
    val status: StateFlow<PdfRenderStatus> = _status.asStateFlow()

    @Volatile private var bake: PdfBakeReader? = null
    /** G2 accounting for the live source. the bake's own source never feeds it */
    private val failures = PdfRenderRules.FailureTracker()
    /** R1: started once, counted once, only re-tried for a wanted z <= baseMaxZoom */
    private val base = PdfRenderRules.BaseRasterLifecycle(baseMaxZoom, failures, alreadyReady = session.readyBase(basePlan) != null)
    /** the live attempt in flight (or the last one), loadTile waits on it, never starts one */
    @Volatile private var liveBase: Deferred<PdfBaseRaster>? = null
    private val rasterLane = Dispatchers.Default.limitedParallelism(2)
    @Volatile private var disposed = false

    private val _replan = MutableStateFlow(0)
    override val replanTicks: StateFlow<Int> = _replan.asStateFlow()

    private var completedAuditPlan: PdfWarpPlan? = null

    private val queue = PdfJobQueue(::renderOnPdfThread, ::onJobFinished, { it === TileSource.EMPTY }, session.timer)

    init {
        PdfRenderExecutor.register(queue)
        if (forBake) session.bakeUser(joined = true)
        if (base.status == PdfRenderRules.BaseRasterLifecycle.Status.READY) _status.value = PdfRenderStatus.Ready
        // AND-R2-2: every new source checks the bytes again, a healthy session reused by Try
        // Again doesn't get to skip it. off main, the single flight memo means one hash total
        session.expectedContentKey?.let { want ->
            PdfRenderSessions.scope.launch(Dispatchers.IO) {
                if (!disposed && !com.tacmap.calibration.PdfStoredFile.matches(session.file, want)) fail(PdfRenderFailure.CANNOT_OPEN)
            }
        }
    }

    val failed: Boolean get() = _status.value is PdfRenderStatus.Failed

    /** the document's heavy flag (E1), shared with every other source on this session */
    val heavy: Boolean get() = session.timer.heavy

    /** the document's EWMA, null while it has no sample yet */
    val ewmaMs: Double? get() = session.timer.ewmaMs.takeIf { !it.isNaN() }

    fun dispose() {
        if (!disposed && forBake) session.bakeUser(joined = false)
        disposed = true
        PdfRenderExecutor.unregister(queue)
        queue.clear()
        bake?.let { b -> runCatching { b.close() } }
        bake = null
    }

    fun attachBake(reader: PdfBakeReader?) {
        val old = bake
        bake = reader
        if (old != null && old !== reader) runCatching { old.close() }
    }

    val bakeMaxZoom: Int? get() = bake?.maxZoom

    override fun hasContent(tile: TileIndex): Boolean =
        footprint.classify(tile.z, tile.x, tile.y) != TileCoverage.OUTSIDE

    override fun fallbackZoom(tileZoom: Int): Int? {
        bake?.let { return min(tileZoom - 1, it.maxZoom) }
        if (session.readyBase(basePlan) != null) return min(tileZoom - 1, baseMaxZoom)
        return null
    }

    /**
     * Only called for frames the view really asks for, TileMapView skips it while the
     * layer is hidden or underzoomed (E3). [centreX]/[centreY] is the camera's viewport
     * centre, not the middle of the requested tiles
     */
    override fun onWanted(ordered: List<TileIndex>, tileZoom: Int, centreX: Double, centreY: Double) {
        queue.wanted(tileZoom, centreX, centreY)
        // a failed source starts nothing (G1), not even the base
        if (forBake || disposed || failed) return
        if (base.wanted(tileZoom)) startLiveBase(if (tileZoom <= baseMaxZoom) PdfRenderExecutor.Band.VISIBLE else PdfRenderExecutor.Band.FALLBACK)
    }

    @Volatile private var armed = false

    /** the live slot only, the bake never comes through here (AND-R2-1) */
    private fun liveBaseDeferred(band: PdfRenderExecutor.Band): Deferred<PdfBaseRaster> =
        session.baseRaster(
            plan = basePlan,
            geometry = geometry,
            clip = footprint.clip,
            band = band,
            beforeRender = { armed = guard?.armIfNeeded(GuardKind.BASE) == true },
            afterRender = { if (armed) guard?.complete(GuardKind.BASE); armed = false },
        )

    /**
     * One live attempt (R1). Counted once when it lands, however many tiles wait on it.
     * Success: ready, jobOk, re-plan. Blank: sticky. Anything else counts toward the run
     */
    private fun startLiveBase(band: PdfRenderExecutor.Band) {
        val d = liveBaseDeferred(band)
        liveBase = d
        d.invokeOnCompletion { cause ->
            if (disposed) return@invokeOnCompletion
            val result = when {
                cause == null -> PdfRenderRules.BaseRasterLifecycle.Result.Ok
                reasonOf(cause) == PdfRenderFailure.BLANK -> PdfRenderRules.BaseRasterLifecycle.Result.Blank
                else -> PdfRenderRules.BaseRasterLifecycle.Result.Failed(reasonOf(cause))
            }
            val replan = base.done(result)
            failures.failed?.let { stick(it) }
            if (cause == null) markReady()
            if (replan) _replan.value++
        }
    }

    /** the live path's base raster: ready, or the attempt in flight. null = not this time */
    private suspend fun liveBaseRaster(): PdfBaseRaster? {
        session.readyBase(basePlan)?.let { return it }
        val d = liveBase ?: return null
        return try {
            d.await()
        } catch (c: CancellationException) {
            // the deferred failing with a cancellation isn't us being cancelled
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Throwable) {
            // already counted where the attempt landed
            null
        }
    }

    /**
     * bake path: a finished live raster or the bake's own attempt, never the live one in
     * flight. throws on failure, no G2, and nothing on the live lifecycle moves (AND-R2-1)
     */
    suspend fun awaitBaseForBake(): PdfBaseRaster = session.bakeBaseRaster(basePlan, geometry, footprint.clip).await()

    private fun markReady() {
        if (_status.value == PdfRenderStatus.Preparing) _status.value = PdfRenderStatus.Ready
    }

    /** sticky failure from outside the job accounting (document level), Try Again is the way out */
    fun fail(reason: PdfRenderFailure) = stick(failures.documentFailure(reason))

    private fun stick(reason: PdfRenderFailure?) {
        if (reason == null || _status.value is PdfRenderStatus.Failed) return
        _status.value = PdfRenderStatus.Failed(reason)
        queue.clear()
    }

    /**
     * Failed: nothing new, but null ("couldn't load") rather than EMPTY so the tile stays
     * missing and whatever fallback is cached keeps drawing under it (G1). A null doesn't
     * publish anything to the view, so it can't spin a reload loop, the view only asks
     * again when its wanted set changes.
     */
    override suspend fun loadTile(tile: TileIndex): Bitmap? {
        if (failed || disposed) return null
        if (!hasContent(tile)) return TileSource.EMPTY
        queue.takeOrphan(tile)?.let { return delivered(it) }
        bake?.let { reader ->
            val baked = withContext(Dispatchers.IO) { runCatching { reader.tile(tile) }.getOrNull() }
            if (baked != null) return delivered(baked)
        }
        if (tile.z <= baseMaxZoom) {
            val base = liveBaseRaster() ?: return null
            // the raster sample path counts too, nothing may escape loadTile from here (G2)
            val out = try {
                withContext(rasterLane) { forDisplay(renderRasterTile(tile, base)) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                stick(failures.jobFailed(PdfRenderRules.JobPath.RASTER_SAMPLE, reasonOf(t)))
                return null
            }
            failures.jobOk()
            return delivered(out, converted = true)
        }
        val bmp = queue.request(tile) ?: return null
        return delivered(bmp)
    }

    /** null once the source failed: a job still running when it went sticky gets dropped (G1) */
    private suspend fun delivered(bmp: Bitmap, converted: Boolean = false): Bitmap? {
        if (failed || disposed) return null
        if (bmp === TileSource.EMPTY) return bmp
        markReady()
        return if (converted) bmp else withContext(rasterLane) { forDisplay(bmp) }
    }

    /**
     * Live tiles go to the GPU here on the raster lane (Config.HARDWARE), so the
     * RenderThread never uploads a 1.8 MB tile mid pinch. The software copy is left to
     * the GC rather than recycled: a tile can in theory reach two waiters and the
     * second copy would be off a recycled bitmap. Bake tiles never come through here,
     * the encoder needs software pixels.
     */
    private fun forDisplay(bmp: Bitmap): Bitmap {
        if (forBake || !PdfTileRenderFlags.hardwareTiles || bmp === TileSource.EMPTY) return bmp
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return bmp
        if (bmp.config == Bitmap.Config.HARDWARE || bmp.isRecycled) return bmp
        return runCatching { bmp.copy(Bitmap.Config.HARDWARE, false) }.getOrNull() ?: bmp
    }

    private fun renderRasterTile(tile: TileIndex, base: PdfBaseRaster): Bitmap {
        val job = TileJob.single(tile.z, tile.x, tile.y)
        val plan = PdfTileWarp.plan(job, tilePx, footprint, georef)
        val out = PdfTileRenderer.renderJob(job, tilePx, plan, footprint, PdfRenderSourceKind.Raster(base))
        if (com.tacmap.BuildConfig.DEBUG && com.tacmap.app.DebugLaunchHooks.deviceAudit) {
            PdfDeviceAudit.completed(auditSourceId, plan, policy.detailZoom, baseMaxZoom, "raster-live", auditInstanceId)
        }
        return out.getValue(tile)
    }

    /** pdfium thread: a live or bake vector job */
    private fun renderOnPdfThread(job: TileJob, band: PdfRenderExecutor.Band): Map<TileIndex, Bitmap> {
        if (com.tacmap.BuildConfig.DEBUG && com.tacmap.app.DebugLaunchHooks.deviceAudit) completedAuditPlan = null
        val plan = PdfTileWarp.plan(job, tilePx, footprint, georef)
        if (plan.cells.isEmpty()) return job.tiles().associate { TileIndex(it[0], it[1], it[2]) to TileSource.EMPTY }
        val armedVector = band != PdfRenderExecutor.Band.BAKE && guard?.armIfNeeded(GuardKind.VECTOR) == true
        try {
            return session.withPage { page ->
                val frame = session.frame(geometry, page)
                // page open and guard armed, the EWMA sample starts here (R3)
                PdfJobTiming.markDrawStart()
                PdfTileRenderer.renderJob(
                    job, tilePx, plan, footprint,
                    PdfRenderSourceKind.Vector(page, frame, staged = PdfTileRenderer.usesStaging(plan.cells.size)),
                ).also {
                    if (com.tacmap.BuildConfig.DEBUG && com.tacmap.app.DebugLaunchHooks.deviceAudit) completedAuditPlan = plan
                }
            }
        } finally {
            if (armedVector) guard?.complete(GuardKind.VECTOR)
        }
    }

    /** pdfium thread, live jobs only: bake + estimate jobs report to the bake instead (G2) */
    private fun onJobFinished(job: TileJob, band: PdfRenderExecutor.Band, error: Throwable?) {
        if (com.tacmap.BuildConfig.DEBUG && com.tacmap.app.DebugLaunchHooks.deviceAudit) {
            val plan = completedAuditPlan
            completedAuditPlan = null
            if (error == null && plan != null) PdfDeviceAudit.completed(auditSourceId, plan, policy.detailZoom, baseMaxZoom, "vector-${band.name}", auditInstanceId)
        }
        if (forBake || band == PdfRenderExecutor.Band.BAKE) return
        when (error) {
            null -> failures.jobOk()
            is CancellationException -> Unit
            else -> stick(failures.jobFailed(PdfRenderRules.JobPath.VECTOR, reasonOf(error)))
        }
    }

    /**
     * Bake path: the same renderer, bake band. Base raster zooms sample the raster on the
     * caller's thread, everything above goes through the pdfium thread at the lowest band.
     * Throws whatever went wrong, the bake maps it to its own error
     */
    suspend fun renderForBake(job: TileJob): Map<TileIndex, Bitmap> = renderForBakeTimed(job).tiles

    /**
     * Same, plus the E1 draw time (J2 jobMs): the raster sample alone on raster levels, the
     * page draw through the warp on vector ones. The base raster fetch is never in it (R3)
     */
    suspend fun renderForBakeTimed(job: TileJob): PdfJobQueue.Timed<Bitmap> {
        (status.value as? PdfRenderStatus.Failed)?.let { throw PdfRenderException(it.reason) }
        return if (job.z <= baseMaxZoom) {
            val raster = awaitBaseForBake()
            withContext(rasterLane) {
                val t0 = android.os.SystemClock.elapsedRealtimeNanos()
                val plan = PdfTileWarp.plan(job, tilePx, footprint, georef)
                val out = PdfTileRenderer.renderJob(job, tilePx, plan, footprint, PdfRenderSourceKind.Raster(raster))
                val drawMs = (android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                if (com.tacmap.BuildConfig.DEBUG && com.tacmap.app.DebugLaunchHooks.deviceAudit) {
                    PdfDeviceAudit.completed(auditSourceId, plan, policy.detailZoom, baseMaxZoom, "raster-bake", auditInstanceId)
                }
                PdfJobQueue.Timed(out, drawMs)
            }
        } else {
            queue.bakeTimed(job)
        }
    }

    fun clearOrphans() = queue.clearOrphans()
}
