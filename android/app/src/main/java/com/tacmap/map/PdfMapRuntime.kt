package com.tacmap.map

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.canonicalJson
import com.tacmap.map.render.TileMemoryBudget
import com.tacmap.map.render.pdf.PdfBakePlan
import com.tacmap.map.render.pdf.PdfBakeReader
import com.tacmap.map.render.pdf.PdfFootprint
import com.tacmap.map.render.pdf.PdfGuardHooks
import com.tacmap.map.render.pdf.PdfRenderException
import com.tacmap.map.render.pdf.PdfRenderExecutor
import com.tacmap.map.render.pdf.PdfRenderFailure
import com.tacmap.map.render.pdf.PdfRenderGuard
import com.tacmap.map.render.pdf.PdfRenderRules
import com.tacmap.map.render.pdf.PdfRenderSessions
import com.tacmap.map.render.pdf.PdfRenderStatus
import com.tacmap.map.render.pdf.PdfTileSource
import com.tacmap.map.render.pdf.PdfZoomPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * The live PDF render state, owned by MapViewModel so a rotation keeps the open
 * page, base raster and tile source. Hands the map a [PdfTileSource] for the active
 * PDF (keyed by file, georef digest and tilePx, so a calibration refit swaps only
 * the cheap source) and publishes the render status for the header.
 */
class PdfMapRuntime internal constructor(
    private val app: Application,
    private val scope: CoroutineScope,
    internal val guard: PdfRenderGuard,
    /** makes a freshly minted guard token durable before anything arms on it (worker thread) */
    private val persistRenderMeta: (PdfMapSource) -> Boolean,
) {
    private class Entry(val key: String, val source: PdfTileSource?, val failure: PdfRenderFailure?)

    // volatile: Remove's detachBake reads it off main now (R3-4)
    @Volatile private var current: Entry? = null
    private var generation = 0
    private var statusJob: Job? = null

    private val _status = MutableStateFlow<PdfRenderStatus>(PdfRenderStatus.None)
    val status: StateFlow<PdfRenderStatus> = _status.asStateFlow()

    private val _showPreparingLabel = MutableStateFlow(false)
    /** "Drawing map…" only once preparing has lasted 300 ms, no flash on a warm open */
    val showPreparingLabel: StateFlow<Boolean> = _showPreparingLabel.asStateFlow()

    init {
        scope.launch { PdfRenderRules.preparingLabel(status) { _showPreparingLabel.value = it } }
    }

    /** the tile source for [pdf] at this screen density. main thread */
    internal fun tileSource(pdf: PdfMapSource, density: Float): PdfTileSource? {
        val georef = pdf.placement ?: return release().let { null }
        val file = pdf.uri.path?.let(::File) ?: return release().let { null }
        val tilePx = PdfZoomPolicy.tilePx(density.toDouble())
        val canonical = georef.canonicalJson()
        val key = "pdf:${file.path}:${sha256(canonical).take(16)}:$tilePx:g$generation"
        current?.let { if (it.key == key) return it.source }

        val previous = current
        val entry = try {
            val source = createPdfTileSource(app, pdf, tilePx, key, guard, persistRenderMeta)
            attachBake(source, pdf, canonical)
            Entry(key, source, null)
        } catch (e: PdfRenderException) {
            Log.w(TAG, "pdf can't be drawn: ${e.failure.code}")
            Entry(key, null, e.failure)
        }
        current = entry
        previous?.source?.let(::disposeSource)
        watch(entry)
        return entry.source
    }

    private fun attachBake(source: PdfTileSource, pdf: PdfMapSource, canonicalGeoref: String) {
        val bake = pdf.render.bake ?: return
        if (bake.tilePx != source.tilePx) return
        val expected = PdfBakePlan.bakeKey(canonicalGeoref, source.tilePx)
        if (bake.bakeKey != expected) return
        val file = File(File(app.filesDir, "offline_tiles"), bake.fileName)
        val pdfFile = pdf.uri.path?.let(::File)
        val contentKey = pdf.render.contentKey
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // OD-F4: the bake only draws for the bytes it was made from, a missing or swapped
            // PDF stays cannotOpen with nothing on top
            if (contentKey != null && (pdfFile == null || !com.tacmap.calibration.PdfStoredFile.matches(pdfFile, contentKey))) return@launch
            val reader = PdfBakeReader.open(file, expected, source.tilePx) ?: return@launch
            if (current?.source === source) source.attachBake(reader) else reader.close()
        }
    }

    private fun watch(entry: Entry) {
        statusJob?.cancel()
        val source = entry.source
        if (source == null) {
            _status.value = entry.failure?.let { PdfRenderStatus.Failed(it) } ?: PdfRenderStatus.None
            return
        }
        statusJob = scope.launch { source.status.collect { _status.value = it } }
    }

    private fun disposeSource(source: PdfTileSource) = disposePdfTileSource(source)

    /** the PDF went away (online map, another map): let go of everything */
    fun release() {
        statusJob?.cancel()
        statusJob = null
        current?.source?.let(::disposeSource)
        current = null
        _status.value = PdfRenderStatus.None
    }

    /** Try Again: a fresh session + source, a new cache key so the failed EMPTY tiles go */
    fun retry() {
        generation++
        current?.source?.let(::disposeSource)
        current = null
        _status.value = PdfRenderStatus.Preparing
    }

    /** a baked tile set landed for the active PDF, start reading it */
    internal fun onBakePublished(pdf: PdfMapSource) {
        val source = current?.source ?: return
        val georef = pdf.placement ?: return
        attachBake(source, pdf, georef.canonicalJson())
    }

    /** the bake was removed, stop reading it before the file goes */
    internal fun detachBake() {
        current?.source?.attachBake(null)
    }

    internal val activeSource: PdfTileSource? get() = current?.source

    fun setForeground(foreground: Boolean) {
        PdfRenderExecutor.foreground = foreground
        if (!foreground) guard.disarmBackground()
    }

    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) current?.source?.clearOrphans()
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) PdfRenderSessions.trimPages()
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "PdfMapRuntime"
    }
}

/**
 * A tile source for [pdf] on a shared session. The live map and the bake each make
 * their own (own queue, own band) but end up on the same open page + base raster.
 * Throws [PdfRenderException] (pageGeometry) when the crop can't be drawn at all.
 */
internal fun createPdfTileSource(
    app: Application,
    pdf: PdfMapSource,
    tilePx: Int,
    cacheKey: String,
    guard: PdfRenderGuard?,
    persistRenderMeta: (PdfMapSource) -> Boolean,
    forBake: Boolean = false,
): PdfTileSource {
    val georef = pdf.placement ?: throw PdfRenderException(PdfRenderFailure.PAGE_GEOMETRY)
    val file = pdf.uri.path?.let(::File) ?: throw PdfRenderException(PdfRenderFailure.CANNOT_OPEN)
    val footprint = PdfFootprint.build(georef, pdf.geometry.visibleBox)
    val policy = PdfZoomPolicy.of(georef, footprint)
    val session = PdfRenderSessions.acquire(file, georef.page, pdf.render.contentKey)
    val (ram, low) = TileMemoryBudget.physicalMemory(app)
    val hooks = guard?.let {
        PdfGuardHooks(it, pdf.render.renderGuardToken) { !pdf.render.tokenMinted || persistRenderMeta(pdf) }
    }
    return PdfTileSource(
        session = session,
        georef = georef,
        geometry = pdf.geometry,
        footprint = footprint,
        policy = policy,
        tilePx = tilePx,
        budgetPx = TileMemoryBudget.baseBudgetPx(ram, low),
        guard = hooks,
        cacheKey = cacheKey,
        forBake = forBake,
    )
}

internal fun disposePdfTileSource(source: PdfTileSource) {
    source.dispose()
    PdfRenderSessions.release(source.session)
}
