package com.tacmap.calibration

import android.app.Application
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.tacmap.map.createPdfTileSource
import com.tacmap.map.disposePdfTileSource
import com.tacmap.map.render.TileSource
import com.tacmap.map.render.pdf.GuardKind
import com.tacmap.map.render.pdf.MercatorWorld
import com.tacmap.map.render.pdf.PdfBakeEstimate
import com.tacmap.map.render.pdf.PdfBakeOption
import com.tacmap.map.render.pdf.PdfBakePlan
import com.tacmap.map.render.pdf.PdfRenderFailure
import com.tacmap.map.render.pdf.PdfRenderGuard
import com.tacmap.map.render.pdf.PdfRenderRules
import com.tacmap.map.render.pdf.PdfTileSource
import com.tacmap.map.render.pdf.PdfZoomPolicy
import com.tacmap.map.render.pdf.TileJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Generate Offline Tiles, app scoped (created in TacticalApp) so it survives the
 * Layers sheet closing, a rotation and the Activity finishing. One bake at a time.
 *
 * Threading (S6): every shared field sits behind [lock]. Each launch owns the tile
 * source it made and is the only one that disposes it, unless it parks it for the
 * confirm, then whoever takes it off the shelf (start, dismiss, cancel, the next
 * prepare) owns it. A coroutine only touches the state while its generation is still
 * the current one, so a cancelled bake unwinding late can't stomp on a new estimate.
 */
class PdfBakeManager(private val app: Application, private val guard: PdfRenderGuard) {

    data class OptionEstimate(
        val option: PdfBakeOption,
        val bytes: Long,
        /** 2x bytes, what free space has to cover (and what noSpace says is needed) */
        val neededBytes: Long,
        val minutes: Int,
        val enoughSpace: Boolean,
    )

    data class Proposal(
        val pdfName: String,
        val options: List<OptionEstimate>,
        /** what the confirm opens on (J2): the default if it fits, else the biggest that does, else the default */
        val initial: OptionEstimate,
        /** which PDF this is for, so the confirm can tell whether it's the one on screen (OD-F9) */
        val renderGuardToken: String = "",
    )

    sealed class State {
        data object Idle : State()
        data object Estimating : State()
        data class Confirming(val proposal: Proposal) : State()
        /** [token] = the guard token of the PDF being baked, so Layers only shows it under that PDF (C6) */
        data class Running(val done: Int, val total: Int, val token: String = "") : State()
        data class Failed(val error: PdfBakeError, val neededBytes: Long = 0) : State()
    }

    /** a finished bake for this PDF (by guard token), so the view model can update its source */
    data class Published(val renderGuardToken: String, val bake: PersistedPdfBake)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * the library side of a publish (M3): every live MapViewModel registers, the one that came
     * to the front last records. nobody registered = a finished bake can't be recorded and
     * fails closed
     */
    internal val recorders = PdfBakeRecorders()
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _published = MutableSharedFlow<Published>(extraBufferCapacity = 4)
    val published: SharedFlow<Published> = _published.asSharedFlow()

    /** one shot "Offline tiles ready" */
    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    /** the estimate's source + PDF waiting on the confirm. whoever takes it disposes it */
    private class Parked(val source: PdfTileSource, val pdf: PdfMapSource, val proposal: Proposal)

    private val lock = Any()
    // all guarded by lock
    private var generation = 0L
    /** the last run launched. stop() cancels it but keeps it, the next run joins it first (A5) */
    private var job: Job? = null
    private var parked: Parked? = null
    /** file of the PDF being estimated/baked, so deleting it can cancel us */
    private var pdfFile: File? = null

    /** who holds the K1 bake slot right now, by run generation. separate lock, it does file IO */
    private val guardLock = Any()
    private var guardOwner = 0L

    /** arm the K1 bake slot for run [gen] (estimate or bake, same token, F3) */
    private fun armBakeSlot(gen: Long, token: String) = synchronized(guardLock) {
        guard.arm(GuardKind.BAKE, token)
        guardOwner = gen
    }

    /** complete it, but only if [gen] still holds it: a late unwind never clears a newer run's marker (A5) */
    private fun completeBakeSlot(gen: Long, token: String) = synchronized(guardLock) {
        if (guardOwner != gen) return@synchronized
        guard.complete(GuardKind.BAKE, token)
        guardOwner = 0L
    }

    /** set [s] only if [gen] is still the current run. true when it landed */
    private fun setStateIf(gen: Long, s: State): Boolean = synchronized(lock) {
        if (generation != gen) return false
        _state.value = s
        if (s !is State.Running && s !is State.Estimating && s !is State.Confirming) pdfFile = null
        true
    }

    /** Layers > Generate Offline Tiles…: estimate, then wait in Confirming */
    fun prepare(pdfSource: PdfMapSource, density: Float) {
        val toDispose: Parked?
        val next: Job
        synchronized(lock) {
            val st = _state.value
            if (st is State.Running || st == State.Estimating) return
            if (pdfSource.calibration == null || pdfSource.placement?.origin == GeorefOrigin.PROVISIONAL) {
                _state.value = State.Failed(PdfBakeError.NOT_CALIBRATED)
                return
            }
            val gen = ++generation
            toDispose = parked
            parked = null
            val previous = job
            _state.value = State.Estimating
            pdfFile = pdfSource.uri.path?.let(::File)
            next = scope.launch(start = CoroutineStart.LAZY) {
                // the last run (a cancelled bake, a dismissed estimate) has to be fully gone
                // before this one makes a source on the same session
                previous?.cancelAndJoin()
                estimate(gen, pdfSource, density)
            }
            job = next
        }
        toDispose?.let { disposePdfTileSource(it.source) }
        next.start()
    }

    private suspend fun estimate(gen: Long, pdfSource: PdfMapSource, density: Float) {
        var src: PdfTileSource? = null
        var handedOver = false
        val token = pdfSource.render.renderGuardToken
        try {
            // the estimate's sample renders sit under the bake slot too (F3). the token is the
            // library entry's, already sealed with it
            armBakeSlot(gen, token)
            val tilePx = PdfZoomPolicy.tilePx(density.toDouble())
            val s = createPdfTileSource(app, pdfSource, tilePx, "bake:${System.nanoTime()}", null, { true }, forBake = true)
            src = s
            val proposal = propose(s, pdfSource.displayName).copy(renderGuardToken = token)
            synchronized(lock) {
                if (generation == gen) {
                    parked = Parked(s, pdfSource, proposal)
                    handedOver = true
                    _state.value = State.Confirming(proposal)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: PdfBakeException) {
            setStateIf(gen, State.Failed(e.error))
        } catch (t: Throwable) {
            Log.w(TAG, "bake estimate failed (${t.javaClass.simpleName})")
            setStateIf(gen, State.Failed(PdfBakeError.RENDER_FAILED))
        } finally {
            completeBakeSlot(gen, token)
            if (!handedOver) src?.let(::disposePdfTileSource)
        }
    }

    /**
     * J2: capped option counting, up to 3 samples through the bake path, then the one
     * pure estimate. Throws tooLarge / renderFailed as PdfBakeException
     */
    private suspend fun propose(s: PdfTileSource, pdfName: String): Proposal {
        val ctx = coroutineContext
        val opts = PdfBakePlan.options(s.policy.detailZoom, ensureActive = { ctx.ensureActive() }) { z, limit ->
            s.footprint.count(z, limit) { ctx.isActive }
        }
        val default = opts.default ?: throw PdfBakeException(PdfBakeError.TOO_LARGE)
        val z = default.maxZoom
        // the base raster first, so no sample's jobMs has its fetch in it (R3). a page that
        // won't open or draws blank fails the estimate right here (R4), anything else is the
        // samples' problem
        try {
            s.awaitBaseForBake()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (PdfRenderRules.reasonOf(t) in DOCUMENT_FAILURES) throw PdfBakeException(PdfBakeError.RENDER_FAILED, t)
        }
        val level = s.footprint.level(z, PdfBakePlan.MAX_TILES) { ctx.isActive }
        val c = s.footprint.clipMean
        val ll = runCatching { s.georef.toWGS84(c.x, c.y) }.getOrNull()
        val centre = PdfBakeEstimate.centreTile(ll?.let { MercatorWorld.x(it.longitude) }, ll?.let { MercatorWorld.y(it.latitude) }, z)
        val samples = PdfBakeEstimate.samplePicks(level, centre).map { sample(s, TileJob.single(z, it.x, it.y)) }
        val free = runCatching { StatFs(app.filesDir.path).availableBytes }.getOrNull()
        val r = PdfBakeEstimate.estimate(
            levels = { s.footprint.level(it, PdfBakePlan.MAX_TILES) { ctx.isActive } },
            baseMaxZoom = s.baseMaxZoom,
            options = opts.options,
            defaultMaxZoom = z,
            samples = samples,
            ewmaMs = s.ewmaMs,
            freeBytes = free,
        )
        val byZoom = opts.options.associateBy { it.maxZoom }
        val list = r.options.map {
            OptionEstimate(byZoom.getValue(it.maxZoom), it.bytes, it.neededBytes, it.minutes, it.enoughSpace)
        }
        return Proposal(pdfName, list, list.first { it.option.maxZoom == r.initialSelection })
    }

    /**
     * One 1x1 BAKE band job through the bake path, then the bake encoder. A vector sample
     * feeds the document EWMA on its way through the queue (E1). EMPTY / failed samples
     * just don't count, but a page that won't open at all fails the estimate
     */
    private suspend fun sample(s: PdfTileSource, job: TileJob): PdfBakeEstimate.Sample {
        val timed = try {
            s.renderForBakeTimed(job)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val reason = PdfRenderRules.reasonOf(t)
            if (reason in DOCUMENT_FAILURES) throw PdfBakeException(PdfBakeError.RENDER_FAILED, t)
            return PdfBakeEstimate.Sample(PdfBakeEstimate.SampleResult.FAILED, 0.0)
        }
        val out = timed.tiles
        // the E1 timing, page draw through the warp (J2 r1), not dispatch to result
        val jobMs = timed.drawMs ?: 0.0
        val bmp = out.values.firstOrNull { it !== TileSource.EMPTY }
        for (other in out.values) if (other !== bmp && other !== TileSource.EMPTY) other.recycle()
        if (bmp == null) return PdfBakeEstimate.Sample(PdfBakeEstimate.SampleResult.EMPTY, jobMs)
        return try {
            val e0 = SystemClock.elapsedRealtimeNanos()
            val bytes = PdfBaker.encode(bmp)
            PdfBakeEstimate.Sample(PdfBakeEstimate.SampleResult.IMAGE, jobMs, bytes.size.toLong(), msSince(e0))
        } catch (t: Throwable) {
            PdfBakeEstimate.Sample(PdfBakeEstimate.SampleResult.FAILED, jobMs)
        } finally {
            bmp.recycle()
        }
    }

    private fun msSince(t0: Long): Double = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6

    /** Generate on the confirm. a disabled (doesn't fit) option never gets here, the button is off */
    fun start(choice: OptionEstimate) {
        val p: Parked
        val gen: Long
        val next: Job
        synchronized(lock) {
            if (_state.value !is State.Confirming || !choice.enoughSpace) return
            p = parked ?: return
            parked = null
            gen = ++generation
            val previous = job
            _state.value = State.Running(0, choice.option.tiles, p.pdf.render.renderGuardToken)
            next = scope.launch(start = CoroutineStart.LAZY) {
                // the estimate that parked this is done, but join it anyway: its cleanup has to be over
                previous?.join()
                runBake(gen, p, choice)
            }
            job = next
        }
        next.start()
    }

    private suspend fun runBake(gen: Long, p: Parked, choice: OptionEstimate) {
        val src = p.source
        val pdf = p.pdf
        val token = pdf.render.renderGuardToken
        try {
            val georef = pdf.placement ?: throw PdfBakeException(PdfBakeError.SOURCE_CHANGED)
            val georefJson = georef.canonicalJson()
            val bakeKey = PdfBakePlan.bakeKey(georefJson, src.tilePx)
            armBakeSlot(gen, token)
            src.session.claimBake(gen)
            // who records it is looked up when it publishes, not now: the screen that started it can be gone by then
            PdfBaker.bake(
                app, src, pdf, choice.option.maxZoom, bakeKey, recorders.atPublish,
                onProgress = { done, total -> setStateIf(gen, State.Running(done, total, token)) },
                // straight from inside the publish: it's attached, so the view model hears about
                // it even if a cancel lands a moment later (F1)
                onPublished = { _published.tryEmit(Published(token, it)) },
            )
            if (setStateIf(gen, State.Idle)) _finished.tryEmit(Unit)
        } catch (c: CancellationException) {
            setStateIf(gen, State.Idle)
            throw c
        } catch (e: PdfBakeException) {
            setStateIf(gen, State.Failed(e.error, if (e.error == PdfBakeError.NO_SPACE) choice.neededBytes else 0))
        } catch (t: Throwable) {
            Log.w(TAG, "bake failed (${t.javaClass.simpleName})")
            val error = PdfBakeErrors.classifyWrite(t)
            setStateIf(gen, State.Failed(error, if (error == PdfBakeError.NO_SPACE) choice.neededBytes else 0))
        } finally {
            // both keyed to this run, a newer bake's flag and marker are left alone (A5)
            src.session.releaseBake(gen)
            completeBakeSlot(gen, token)
            disposePdfTileSource(src)
        }
    }

    /** Cancel on the chip / Layers row */
    fun cancel() = stop(force = true)

    /** the PDF is being deleted: stop whatever we're doing for it */
    fun cancelFor(file: File?) {
        val mine = synchronized(lock) { pdfFile } ?: return
        val target = file ?: return
        if (runCatching { mine.canonicalPath == target.canonicalPath }.getOrDefault(false)) cancel()
    }

    /** dismiss a confirm or an error */
    fun dismiss() = stop(force = false)

    private fun stop(force: Boolean) {
        val j: Job?
        val p: Parked?
        synchronized(lock) {
            if (!force && _state.value is State.Running) return
            generation++
            // keep job: the next prepare/start joins it so it's fully unwound first (A5)
            j = job
            p = parked
            parked = null
            pdfFile = null
            _state.value = State.Idle
        }
        // the cancelled run disposes its own source on the way out, only the parked one is ours
        j?.cancel()
        p?.let { disposePdfTileSource(it.source) }
    }

    fun cleanWorkDirectory() = PdfBaker.cleanWorkDirectory(app)

    private companion object {
        const val TAG = "PdfBakeManager"
        /** a sample that can't even open the page fails the whole estimate (J2) */
        val DOCUMENT_FAILURES = setOf(
            PdfRenderFailure.CANNOT_OPEN, PdfRenderFailure.PASSWORD_PROTECTED,
            PdfRenderFailure.PAGE_MISSING, PdfRenderFailure.PAGE_GEOMETRY, PdfRenderFailure.BLANK,
        )
    }
}

/**
 * Who records a finished bake. Every live MapViewModel registers (again each time its screen
 * comes back to the front) and the latest one still registered is [current]. A bake can
 * outlive the view model that started it, so it never gets that one: it gets [atPublish],
 * which looks [current] up at the attach. Closing a second screen just hands recording back
 * to the one under it
 */
internal class PdfBakeRecorders {
    private val live = ArrayList<PdfBakeRecorder>()

    fun register(recorder: PdfBakeRecorder) {
        synchronized(live) {
            live.remove(recorder)
            live.add(recorder)
        }
    }

    fun unregister(recorder: PdfBakeRecorder) {
        synchronized(live) { live.remove(recorder) }
    }

    val current: PdfBakeRecorder? get() = synchronized(live) { live.lastOrNull() }

    /** what PdfBaker gets: whoever is [current] when the publish attaches or detaches */
    val atPublish: PdfBakeRecorder = object : PdfBakeRecorder {
        override fun attach(entryId: String?, contentKey: String?, renderGuardToken: String, bake: PersistedPdfBake): PdfBakeAttach =
            current?.attach(entryId, contentKey, renderGuardToken, bake) ?: PdfBakeAttach.WriteFailed(null)

        override fun detach(entryId: String?, bake: PersistedPdfBake): Boolean = current?.detach(entryId, bake) == true
    }
}
