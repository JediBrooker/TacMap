package com.tacmap.calibration

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.floor

/** page 0 as both engines see it: pdfium's frame + PDFBox's raw boxes and geo dicts */
internal data class PdfFirstPage(
    val geometry: PdfPageGeometry,
    /** null when PDFBox couldn't read it (pdfium still renders) */
    val metadata: GeoPdfPageData?,
    /** PDFBox and pdfium disagree about the page, so raw-space georefs can't be trusted */
    val framesDisagree: Boolean = false,
) {
    fun georeference(): GeoPdfGeorefResult {
        val meta = metadata ?: return GeoPdfGeorefResult.NoGeoreference
        val result = GeoPdfGeoreferencer.build(meta)
        if (framesDisagree && result is GeoPdfGeorefResult.Georeferenced) {
            return GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED)
        }
        return result
    }
}

internal object PdfDocumentInspector {
    private const val TAG = "PdfDocumentInspector"

    /** throws when pdfium can't open page 0 (invalid, password), same as preflight */
    fun inspect(context: Context, file: File): PdfFirstPage {
        val (w, h) = PdfPageRenderer.firstPageRendererSize(context, Uri.fromFile(file))
        val metadata = GeoPdfParser.readFirstPage(context, file)
        return firstPage(w, h, metadata).also {
            if (it.framesDisagree) Log.w(TAG, "PDFBox page boxes don't match what pdfium renders")
        }
    }

    internal fun firstPage(rendererWidth: Int, rendererHeight: Int, metadata: GeoPdfPageData?): PdfFirstPage {
        val fallback = PdfPageGeometry.rendererOnly(rendererWidth, rendererHeight)
        if (metadata == null) return PdfFirstPage(fallback, null)
        val media = PdfBox.of(metadata.mediaBox)
        val geometry = media?.let {
            PdfPageGeometry(
                mediaBox = it,
                cropBox = PdfBox.of(metadata.cropBox),
                rotation = PdfPageGeometry.normaliseRotation(metadata.rotate),
                rendererWidth = rendererWidth,
                rendererHeight = rendererHeight,
            )
        }?.takeIf { it.isValid() }
        if (geometry == null || !agrees(geometry)) return PdfFirstPage(fallback, metadata, framesDisagree = true)
        return PdfFirstPage(geometry, metadata)
    }

    // pdfium truncates the displayed size; if PDFBox's boxes don't floor to the
    // same ints we're not looking at the same page frame
    private fun agrees(g: PdfPageGeometry): Boolean {
        val box = g.visibleBox
        val w = if (g.rotation % 180 == 0) box.width else box.height
        val h = if (g.rotation % 180 == 0) box.height else box.width
        return abs(floor(w) - g.rendererWidth) <= 1.0 && abs(floor(h) - g.rendererHeight) <= 1.0
    }
}
