package com.tacmap.map.render.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.tacmap.calibration.PdfPageGeometry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * One open PDF page, shared by everything drawing that file (live tiles, the import
 * probe, the bake), refcounted in [PdfRenderSessions]. The PdfRenderer and page only
 * ever get touched on the pdfium thread ([PdfRenderExecutor]). The page stays open
 * between jobs: reopening a big GeoPDF per tile was most of the old cost.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class PdfRenderSession(val file: File, val pageIndex: Int, private val cacheDir: File) {
    val key: String = keyFor(file, pageIndex)

    // ---- pdfium thread only ----
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var page: PdfRenderer.Page? = null
    private var renderedOnce = false
    /** some PdfRenderer builds throw on a second render of one page, after that reopen every call */
    private var reopenPerCall = false
    var pageWidth = 0
        private set
    var pageHeight = 0
        private set

    @Volatile var lastUsedMs: Long = SystemClock.uptimeMillis()
        private set

    /** sticky open failure, nothing renders until a retry makes a fresh session */
    @Volatile var openFailure: PdfRenderFailure? = null
        private set

    /**
     * A bake keeps the page open through memory trims. Owned by the bake's run id, so a
     * cancelled bake unwinding late can't clear the flag of the one that came after (A5)
     */
    private val bakeOwner = java.util.concurrent.atomic.AtomicLong(0)
    val bakeRunning: Boolean get() = bakeOwner.get() != 0L

    fun claimBake(owner: Long) = bakeOwner.set(owner)

    /** only clears it if [owner] still holds it */
    fun releaseBake(owner: Long) {
        bakeOwner.compareAndSet(owner, 0L)
    }

    /**
     * The heavy EWMA for this document (E1). Live tiles, the bake and the bake estimate
     * all draw through this one session so they share it, a freshly opened document
     * starts light. Replaces the old process wide timer
     */
    val timer = PdfRenderScheduling.CallTimer()

    /**
     * The bytes the session's calibration belongs to (OD-F4). Set by whoever acquires it
     * for a restored map, checked every time the page gets opened (first draw, after a trim,
     * reopen per call) and by each new source (AND-R2-2): a missing or swapped file is
     * cannotOpen, the old calibration never lands on different bytes. The memo in
     * PdfStoredFile makes the repeat checks a stat
     */
    @Volatile var expectedContentKey: String? = null

    /** pdfium thread: [block] with the open page, opening it if it was closed */
    fun <T> withPage(block: (PdfRenderer.Page) -> T): T {
        openFailure?.let { throw PdfRenderException(it) }
        lastUsedMs = SystemClock.uptimeMillis()
        val p = page ?: open()
        try {
            val r = block(p)
            renderedOnce = true
            return r
        } catch (e: IllegalStateException) {
            if (!renderedOnce || reopenPerCall) throw PdfRenderException(PdfRenderFailure.RENDER_ERROR, e)
            // worked before on this page, so it's the "already rendered" quirk. reopen per call from now on
            Log.w(TAG, "page threw after an earlier render, switching to reopen per call")
            reopenPerCall = true
            closePage()
            return block(open())
        } finally {
            if (reopenPerCall) closePage()
        }
    }

    private fun open(): PdfRenderer.Page {
        try {
            expectedContentKey?.let { want ->
                if (!com.tacmap.calibration.PdfStoredFile.matches(file, want)) {
                    Log.w(TAG, "stored PDF missing or not the bytes it was calibrated on")
                    fail(PdfRenderFailure.CANNOT_OPEN)
                }
            }
            val d = pfd ?: ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { pfd = it }
            val r = renderer ?: PdfRenderer(d).also { renderer = it }
            if (pageIndex < 0 || pageIndex >= r.pageCount) fail(PdfRenderFailure.PAGE_MISSING)
            val p = r.openPage(pageIndex)
            page = p
            pageWidth = p.width
            pageHeight = p.height
            return p
        } catch (e: PdfRenderException) {
            closeAll()
            throw e
        } catch (e: SecurityException) {
            fail(PdfRenderFailure.PASSWORD_PROTECTED, e)
        } catch (e: FileNotFoundException) {
            fail(PdfRenderFailure.CANNOT_OPEN, e)
        } catch (e: IOException) {
            fail(PdfRenderFailure.CANNOT_OPEN, e)
        } catch (e: IllegalArgumentException) {
            fail(PdfRenderFailure.CANNOT_OPEN, e)
        } catch (e: IllegalStateException) {
            fail(PdfRenderFailure.CANNOT_OPEN, e)
        }
    }

    private fun fail(f: PdfRenderFailure, cause: Throwable? = null): Nothing {
        openFailure = f
        closeAll()
        throw PdfRenderException(f, cause)
    }

    /** pdfium thread. the page is the big native allocation, the renderer + fd are cheap */
    fun closePage() {
        runCatching { page?.close() }
        page = null
    }

    fun closeAll() {
        closePage()
        runCatching { renderer?.close() }
        renderer = null
        runCatching { pfd?.close() }
        pfd = null
    }

    /** pdfium thread: the page frame for the open page, validated (D3-05) */
    fun frame(geometry: PdfPageGeometry, page: PdfRenderer.Page): PdfPageFrame {
        val f = PdfPageFrame(geometry, page.width, page.height, PdfDisplaySizeProbe.mode(cacheDir))
        f.validate()?.let { fail(it) }
        return f
    }

    // ---- base raster, single flight ----

    private val baseLock = Any()
    /** the live slot: live sources + the import probe. readyBase only ever looks here */
    private var base: Deferred<PdfBaseRaster>? = null
    private var baseKey: String? = null
    /**
     * The bake + estimate slot (AND-R2-1). Its own attempt the live side never adopts, so a
     * bake's failure isn't counted in the live G2 run, a live wait never sits at bake band,
     * and a bake's success doesn't make a FAILED live lifecycle look ready
     */
    private var bakeBase: Deferred<PdfBaseRaster>? = null
    private var bakeBaseKey: String? = null
    private var bakeUsers = 0

    /** how many base renders each slot kicked off, for the tests */
    internal val liveBaseStarts = java.util.concurrent.atomic.AtomicInteger()
    internal val bakeBaseStarts = java.util.concurrent.atomic.AtomicInteger()
    /** bake attempts that ended up handing out the live raster instead of their own (R3-1) */
    internal val bakeLiveReuses = java.util.concurrent.atomic.AtomicInteger()

    /** what the live side already has, if it's for this plan */
    fun readyBase(plan: PdfBaseRasterPlan): PdfBaseRaster? = synchronized(baseLock) { completedOk(base, baseKey, plan) }

    /** tests: how many distinct finished base rasters the two slots hold right now (R3-1 wants <= 1) */
    internal fun heldBaseRasters(): Int = synchronized(baseLock) {
        listOfNotNull(base, bakeBase)
            .filter { it.isCompleted && !it.isCancelled && it.getCompletionExceptionOrNull() == null }
            .map { it.getCompleted() }
            .distinct() // no equals on PdfBaseRaster, so this is by identity
            .size
    }

    private fun completedOk(d: Deferred<PdfBaseRaster>?, key: String?, plan: PdfBaseRasterPlan): PdfBaseRaster? =
        if (key == plan.key && d != null && d.isCompleted && !d.isCancelled) runCatching { d.getCompleted() }.getOrNull() else null

    /** a bake / estimate source came or went. the last one out drops the bake slot's raster */
    fun bakeUser(joined: Boolean) = synchronized(baseLock) {
        bakeUsers = (bakeUsers + if (joined) 1 else -1).coerceAtLeast(0)
        if (bakeUsers == 0) {
            bakeBase = null
            bakeBaseKey = null
        }
    }

    /**
     * The bake's base raster for [plan]: a finished live one if there is one (reading it
     * changes nothing on the live side), else the bake slot's own single flight attempt.
     * Never an in flight live one, that could sit at a foreground band while we're in the
     * background
     */
    fun bakeBaseRaster(
        plan: PdfBaseRasterPlan,
        geometry: PdfPageGeometry,
        clip: List<com.tacmap.calibration.PagePoint>,
    ): Deferred<PdfBaseRaster> = synchronized(baseLock) {
        completedOk(base, baseKey, plan)?.let {
            // the live one is there now, don't keep two of these big things around
            bakeBase = null
            bakeBaseKey = null
            return CompletableDeferred(it)
        }
        val existing = bakeBase
        if (existing != null && bakeBaseKey == plan.key && !(existing.isCompleted && existing.getCompletionExceptionOrNull() != null)) {
            return existing
        }
        val d = renderBase(plan, geometry, clip, PdfRenderExecutor.Band.BAKE, background = true, {}, {}, forBake = true)
        bakeBase = d
        bakeBaseKey = plan.key
        bakeBaseStarts.incrementAndGet()
        d
    }

    /**
     * The live base raster for [plan], rendered once whoever asks first. The deferred
     * belongs to the session, a caller giving up doesn't throw the render away. Bakes go
     * through [bakeBaseRaster]
     */
    fun baseRaster(
        plan: PdfBaseRasterPlan,
        geometry: PdfPageGeometry,
        /** the clip polygon, page space. the raster is clipped + paper filled to it (G3) */
        clip: List<com.tacmap.calibration.PagePoint>,
        band: PdfRenderExecutor.Band,
        background: Boolean = false,
        beforeRender: () -> Unit = {},
        afterRender: (ok: Boolean) -> Unit = {},
    ): Deferred<PdfBaseRaster> = synchronized(baseLock) {
        val existing = base
        if (existing != null && baseKey == plan.key && !(existing.isCompleted && existing.getCompletionExceptionOrNull() != null)) {
            return existing
        }
        val d = renderBase(plan, geometry, clip, band, background, beforeRender, afterRender, forBake = false)
        base = d
        baseKey = plan.key
        liveBaseStarts.incrementAndGet()
        d
    }

    /**
     * One base render on the pdfium thread. The bake flavour (R3-1) never holds a second
     * full raster next to a finished live one: it looks again once it actually gets the
     * thread (the live one may have landed while it sat queued) and once more when its own
     * raster is built. The live flavour is the same as ever, it just drops a finished bake
     * raster for the same plan once it lands
     */
    private fun renderBase(
        plan: PdfBaseRasterPlan,
        geometry: PdfPageGeometry,
        clip: List<com.tacmap.calibration.PagePoint>,
        band: PdfRenderExecutor.Band,
        background: Boolean,
        beforeRender: () -> Unit,
        afterRender: (ok: Boolean) -> Unit,
        forBake: Boolean,
    ): Deferred<PdfBaseRaster> {
        val d = CompletableDeferred<PdfBaseRaster>()
        PdfRenderSessions.scope.launch {
            try {
                var reuse: PdfBaseRaster? = null
                val full = PdfRenderExecutor.run(band, background) {
                    // queued bake, the live one finished meanwhile: take it, no render at all
                    reuse = if (forBake) synchronized(baseLock) { completedOk(base, baseKey, plan) } else null
                    if (reuse != null) return@run null
                    withPage { page ->
                        val frame = frame(geometry, page)
                        beforeRender()
                        var ok = false
                        try {
                            PdfTileRenderer.renderBase(page, frame, plan, clip).also { ok = true }
                        } finally {
                            afterRender(ok)
                        }
                    }
                }
                reuse?.let {
                    finishBake(d, plan, own = null, seen = it)
                    return@launch
                }
                val raster = withContext(Dispatchers.Default) {
                    val f = full!!
                    if (PdfTileRenderer.isBlank(f)) {
                        f.recycle()
                        throw PdfRenderException(PdfRenderFailure.BLANK)
                    }
                    PdfBaseRaster(plan, PdfTileRenderer.buildMips(f, plan))
                }
                if (forBake) {
                    finishBake(d, plan, own = raster, seen = null)
                } else {
                    d.complete(raster)
                    dropBakeCopy(d, plan)
                }
            } catch (t: Throwable) {
                d.completeExceptionally(t)
            }
        }
        return d
    }

    /**
     * Bake side landing. Under the lock so it can't interleave with [dropBakeCopy]: whichever
     * of the two lands second sees the other one done. Live raster there (or already picked
     * up on the pdfium thread as [seen]) = hand that out, free the slot and recycle ours,
     * nobody else has seen it yet
     */
    private fun finishBake(d: CompletableDeferred<PdfBaseRaster>, plan: PdfBaseRasterPlan, own: PdfBaseRaster?, seen: PdfBaseRaster?) {
        val live = synchronized(baseLock) {
            val live = seen ?: completedOk(base, baseKey, plan)
            if (live != null && bakeBase === d) {
                bakeBase = null
                bakeBaseKey = null
            }
            d.complete(live ?: own!!)
            live
        }
        if (live != null) {
            own?.levels?.forEach { it.recycle() }
            bakeLiveReuses.incrementAndGet()
        }
    }

    /** live side landed OK: a finished bake copy of the same plan is just a second 24 MB, drop it */
    private fun dropBakeCopy(d: Deferred<PdfBaseRaster>, plan: PdfBaseRasterPlan) = synchronized(baseLock) {
        if (base !== d) return@synchronized
        val b = bakeBase ?: return@synchronized
        if (bakeBaseKey == plan.key && b.isCompleted && !b.isCancelled && b.getCompletionExceptionOrNull() == null) {
            bakeBase = null
            bakeBaseKey = null
        }
    }

    /** the import probe already drew it, hand it over so the first paint is instant */
    fun adoptBase(raster: PdfBaseRaster) = synchronized(baseLock) {
        val d = CompletableDeferred(raster)
        base = d
        baseKey = raster.plan.key
        dropBakeCopy(d, raster.plan)
    }

    // ---- refcount, owned by PdfRenderSessions ----
    internal var refs = 0
    internal var closeJob: Job? = null

    companion object {
        private const val TAG = "PdfRenderSession"
        fun keyFor(file: File, pageIndex: Int): String =
            (runCatching { file.canonicalPath }.getOrDefault(file.path)) + "#" + pageIndex
    }
}

/** refcounted registry, so the import probe, the map and a bake share one open page */
internal object PdfRenderSessions {
    private const val IDLE_CLOSE_MS = 30_000L
    private const val PAGE_IDLE_MS = 90_000L

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = HashMap<String, PdfRenderSession>()
    @Volatile private var cacheDir: File? = null
    private var idleWatch: Job? = null

    fun init(context: Context) {
        cacheDir = context.cacheDir
    }

    @Synchronized
    fun acquire(file: File, pageIndex: Int, expectedContentKey: String? = null): PdfRenderSession {
        val key = PdfRenderSession.keyFor(file, pageIndex)
        val s = sessions[key]?.takeIf { it.openFailure == null }
            ?: PdfRenderSession(file, pageIndex, cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")).also {
                sessions[key]?.let(::dispose)
                sessions[key] = it
            }
        // a restored map pins the bytes, an import probe / test session doesn't (OD-F4)
        if (expectedContentKey != null) s.expectedContentKey = expectedContentKey
        s.refs++
        s.closeJob?.cancel()
        s.closeJob = null
        ensureIdleWatch()
        return s
    }

    /** drop a ref. the last one keeps it warm for 30 s (the import probe hands over to the map that way) */
    @Synchronized
    fun release(session: PdfRenderSession) {
        session.refs = (session.refs - 1).coerceAtLeast(0)
        if (session.refs > 0) return
        session.closeJob?.cancel()
        session.closeJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            synchronized(this@PdfRenderSessions) {
                if (session.refs == 0 && sessions[session.key] === session) {
                    sessions.remove(session.key)
                    dispose(session)
                }
            }
        }
    }

    private fun dispose(session: PdfRenderSession) {
        scope.launch {
            runCatching { PdfRenderExecutor.run(PdfRenderExecutor.Band.BAKE, background = true) { session.closeAll() } }
        }
    }

    /** UI_HIDDEN or worse: close idle pages (the base raster stays), unless a bake needs it */
    fun trimPages() {
        val list = synchronized(this) { sessions.values.toList() }
        for (s in list) {
            if (s.bakeRunning) continue
            scope.launch { runCatching { PdfRenderExecutor.run(PdfRenderExecutor.Band.FALLBACK, background = true) { s.closePage() } } }
        }
    }

    // pages sitting idle for 90 s get closed, they reopen lazily
    private fun ensureIdleWatch() {
        if (idleWatch?.isActive == true) return
        idleWatch = scope.launch {
            while (true) {
                delay(PAGE_IDLE_MS / 3)
                val now = SystemClock.uptimeMillis()
                val idle = synchronized(this@PdfRenderSessions) {
                    sessions.values.filter { !it.bakeRunning && now - it.lastUsedMs > PAGE_IDLE_MS }
                }
                for (s in idle) runCatching {
                    PdfRenderExecutor.run(PdfRenderExecutor.Band.BAKE, background = true) { s.closePage() }
                }
            }
        }
    }
}

/**
 * Once per process, on the pdfium thread: does PdfRenderer size the page by its
 * truncated int size or the real float one? Render a 100.5 x 60.5 pt black page
 * with an identity matrix into a 110 x 70 transparent bitmap and look at column 100.
 * About half alpha means FLOAT, nothing means TRUNCATED. Anything odd, FLOAT and a log.
 */
internal object PdfDisplaySizeProbe {
    private const val TAG = "PdfDisplaySizeProbe"
    @Volatile private var cached: DisplaySizeMode? = null

    fun mode(cacheDir: File): DisplaySizeMode {
        cached?.let { return it }
        val m = runCatching { probe(cacheDir) }.getOrElse {
            Log.w(TAG, "display size probe failed (${it.javaClass.simpleName}), assuming float")
            DisplaySizeMode.FLOAT
        }
        cached = m
        return m
    }

    /** for the instrumented tests */
    fun lastMode(): DisplaySizeMode? = cached

    private fun probe(cacheDir: File): DisplaySizeMode {
        val f = File(cacheDir, "tacmap_size_probe.pdf")
        f.writeBytes(probePdf())
        try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    r.openPage(0).use { page ->
                        val bmp = Bitmap.createBitmap(110, 70, Bitmap.Config.ARGB_8888)
                        try {
                            bmp.eraseColor(Color.TRANSPARENT)
                            page.render(bmp, null, Matrix(), PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val a = Color.alpha(bmp.getPixel(100, 30))
                            val inside = Color.alpha(bmp.getPixel(50, 30))
                            val mode = when {
                                inside < 200 -> null
                                a in 64..192 -> DisplaySizeMode.FLOAT
                                a == 0 -> DisplaySizeMode.TRUNCATED
                                else -> null
                            }
                            Log.i(TAG, "pdfium display size: ${mode ?: "unclear (alpha $a, inside $inside), float"}")
                            return mode ?: DisplaySizeMode.FLOAT
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        } finally {
            f.delete()
        }
    }

    /** ~300 byte pdf, MediaBox 0 0 100.5 60.5 filled black. xref offsets worked out here */
    internal fun probePdf(): ByteArray {
        val content = "0 0 0 rg 0 0 100.5 60.5 re f\n"
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100.5 60.5] /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n${content}endstream",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        for ((i, o) in objs.withIndex()) {
            offsets += sb.length
            sb.append("${i + 1} 0 obj\n").append(o).append("\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) sb.append(String.format(java.util.Locale.US, "%010d 00000 n \n", off))
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }
}
