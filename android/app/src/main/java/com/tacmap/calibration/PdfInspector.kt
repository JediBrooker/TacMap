package com.tacmap.calibration

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.floor

/** one page as the inspector read it. georef only for the scanned pages (first 50) */
internal data class InspectedPage(
    val index: Int,
    val mediaBox: List<Double>,
    val cropBox: List<Double>?,
    val rotate: Int,
    val georef: GeoPdfGeorefResult? = null,
    /** pdfium's frame, filled in for the pages a decision needs */
    val geometry: PdfPageGeometry? = null,
) {
    val visibleBox: PdfBox? get() {
        val media = PdfBox.of(mediaBox) ?: return null
        return cropBox?.let(PdfBox::of)?.let { media.intersect(it) } ?: media
    }

    val state: PageGeorefState get() = when (val g = georef) {
        is GeoPdfGeorefResult.Georeferenced -> PageGeorefState.Valid
        is GeoPdfGeorefResult.Rejected -> PageGeorefState.Rejected(g.reason)
        else -> PageGeorefState.None
    }
}

internal data class PdfInspection(val pageCount: Int, val pages: List<InspectedPage>) {
    /** what ImportDecision gets: the scanned pages only */
    val scanned: List<PageGeorefState> get() = pages.take(ImportLimits.GEOREF_SCAN_PAGES).map { it.state }

    fun page(i: Int): InspectedPage? = pages.getOrNull(i)

    fun replacing(page: InspectedPage): PdfInspection = copy(pages = pages.map { if (it.index == page.index) page else it })
}

internal sealed class InspectionResult {
    data class Ok(val inspection: PdfInspection) : InspectionResult()
    data class Failed(val failure: ImportFailure) : InspectionResult()
}

/**
 * Contract s9.5: the untrusted PDF is parsed ONCE (PDFBox, bounded heap + scratch),
 * every check and every scanned page's georef off that one load, then one pdfium
 * open for the page frames. This is the only place that catches
 * OutOfMemoryError / StackOverflowError, and they become tooComplex, never success
 * (D5-15). The caller runs it on its own thread under the 30 s watchdog.
 */
internal object PdfInspector {
    private const val HEAP_BYTES = 16L * 1024 * 1024
    private const val SCRATCH_BYTES = 64L * 1024 * 1024

    fun inspect(
        context: Context,
        file: File,
        cancelled: () -> Boolean = { false },
        onPage: (page: Int, pages: Int) -> Unit = { _, _ -> },
    ): InspectionResult = guarded { inspectUnguarded(context, file, cancelled, onPage) }

    /** the one place an OOM / StackOverflow gets caught (D5-15), split out so the JVM tests can throw into it */
    internal fun guarded(block: () -> InspectionResult): InspectionResult = try {
        block()
    } catch (_: OutOfMemoryError) {
        InspectionResult.Failed(ImportFailure(ImportError.TOO_COMPLEX))
    } catch (_: StackOverflowError) {
        InspectionResult.Failed(ImportFailure(ImportError.TOO_COMPLEX))
    } catch (e: IOException) {
        // PDFBox reads lazily, so a broken object or the scratch cap can surface mid page loop
        InspectionResult.Failed(ImportFailure(ioFailure(e)))
    } catch (_: RuntimeException) {
        // PDFBox throws all sorts (ClassCast, IllegalArgument...) on junk object graphs
        InspectionResult.Failed(ImportFailure(ImportError.INVALID_PDF))
    }

    /** the 64 MiB scratch cap running out is "too complex", anything else is just a bad file */
    internal fun ioFailure(e: IOException): ImportError {
        var t: Throwable? = e
        while (t != null) {
            if (t.message?.contains(SCRATCH_EXHAUSTED, ignoreCase = true) == true) return ImportError.TOO_COMPLEX
            t = t.cause
        }
        return ImportError.INVALID_PDF
    }

    /** ScratchFile's message when the setupMixed cap is hit (pdfbox-android 2.0.27) */
    private const val SCRATCH_EXHAUSTED = "scratch file memory exceeded"

    private fun inspectUnguarded(
        context: Context,
        file: File,
        cancelled: () -> Boolean,
        onPage: (Int, Int) -> Unit,
    ): InspectionResult {
        GeoPdfParser.ensureInit(context)
        val scratch = File(context.cacheDir, "pdfbox").apply { mkdirs() }
        val memory = MemoryUsageSetting.setupMixed(HEAP_BYTES, SCRATCH_BYTES).setTempDir(scratch)
        val doc = try {
            PDDocument.load(file, "", memory)
        } catch (_: InvalidPasswordException) {
            return InspectionResult.Failed(ImportFailure(ImportError.PASSWORD))
        } catch (e: IOException) {
            return InspectionResult.Failed(ImportFailure(ioFailure(e)))
        }
        val pages = ArrayList<InspectedPage>()
        doc.use { d ->
            val n = d.numberOfPages
            if (n <= 0) return InspectionResult.Failed(ImportFailure(ImportError.INVALID_PDF))
            ImportInspectionRules.check(true, d.isEncrypted, true, n, emptySequence())?.let {
                return InspectionResult.Failed(it)
            }
            for (i in 0 until n) {
                if (cancelled()) throw InterruptedException("import cancelled")
                onPage(i + 1, n)
                if (i < ImportLimits.GEOREF_SCAN_PAGES) {
                    val data = GeoPdfParser.pageData(d, i, allowCatalogVp = i == 0)
                        ?: return InspectionResult.Failed(ImportFailure(ImportError.INVALID_PDF))
                    pages += InspectedPage(i, data.mediaBox ?: emptyList(), data.cropBox, data.rotate, GeoPdfGeoreferencer.build(data))
                } else {
                    // E12: boxes + /Rotate inherited from the page tree like the scanned pages,
                    // the page's own dict alone missed an inherited CropBox
                    val (media, crop, rotate) = GeoPdfParser.pageBoxes(d, i)
                        ?: return InspectionResult.Failed(ImportFailure(ImportError.INVALID_PDF))
                    pages += InspectedPage(index = i, mediaBox = media, cropBox = crop, rotate = rotate)
                }
            }
        }
        val boxes = pages.asSequence().map { InspectedPageBoxes(normalise(it.mediaBox), it.cropBox?.let(::normalise)) }
        ImportInspectionRules.check(true, false, true, pages.size, boxes)?.let { return InspectionResult.Failed(it) }

        // pdfium has to open it too or there's nothing to draw. frames for page 0 and every
        // page that georeferenced: a page pdfium frames differently can't be trusted
        val need = (listOf(0) + pages.filter { it.georef is GeoPdfGeorefResult.Georeferenced }.map { it.index }).distinct()
        val framed = try {
            withRenderer(file) { r -> need.associateWith { i -> framePage(r, pages[i]) } }
        } catch (_: SecurityException) {
            return InspectionResult.Failed(ImportFailure(ImportError.PASSWORD))
        } catch (_: IOException) {
            return InspectionResult.Failed(ImportFailure(ImportError.INVALID_PDF))
        } catch (_: IllegalStateException) {
            return InspectionResult.Failed(ImportFailure(ImportError.INVALID_PDF))
        }
        val out = pages.map { p ->
            val f = framed[p.index] ?: return@map p
            if (f.second && p.georef is GeoPdfGeorefResult.Georeferenced) {
                p.copy(geometry = f.first, georef = GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED))
            } else {
                p.copy(geometry = f.first)
            }
        }
        return InspectionResult.Ok(PdfInspection(pages.size, out))
    }

    private fun normalise(b: List<Double>): List<Double> =
        if (b.size != 4) b else listOf(minOf(b[0], b[2]), minOf(b[1], b[3]), maxOf(b[0], b[2]), maxOf(b[1], b[3]))

    private fun <T> withRenderer(file: File, block: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use(block)
        }

    /** the chosen page's frame later on (page picker), same agreement rule */
    fun geometryFor(file: File, page: InspectedPage): Pair<PdfPageGeometry, Boolean>? = try {
        withRenderer(file) { framePage(it, page) }
    } catch (_: Exception) {
        null
    }

    /** (geometry, frames disagree) */
    private fun framePage(renderer: PdfRenderer, page: InspectedPage): Pair<PdfPageGeometry, Boolean> {
        val (w, h) = renderer.openPage(page.index).use { it.width to it.height }
        val fallback = PdfPageGeometry.rendererOnly(w, h)
        val media = PdfBox.of(page.mediaBox) ?: return fallback to true
        val g = PdfPageGeometry(
            mediaBox = media,
            cropBox = page.cropBox?.let(PdfBox::of),
            rotation = PdfPageGeometry.normaliseRotation(page.rotate),
            rendererWidth = w,
            rendererHeight = h,
        ).takeIf { it.isValid() } ?: return fallback to true
        val box = g.visibleBox
        val dw = if (g.rotation % 180 == 0) box.width else box.height
        val dh = if (g.rotation % 180 == 0) box.height else box.width
        val agrees = abs(floor(dw) - w) <= 1.0 && abs(floor(dh) - h) <= 1.0
        return if (agrees) g to false else fallback to true
    }
}
