package com.tacticalmaps.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import com.tacticalmaps.calibration.PdfPageInfo
import com.tacticalmaps.calibration.PdfPageRenderer
import com.tacticalmaps.calibration.Wgs84Bounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class PdfMapOverlay(
    context: Context,
    private val uri: Uri,
    private val bounds: Wgs84Bounds,
    private val pageInfo: PdfPageInfo,
    private val baseBitmap: Bitmap?
) : Overlay() {
    private val appContext = context.applicationContext
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint().apply { color = 0xE61A1A1A.toInt() }
    private var viewportBitmap: Bitmap? = null
    private var viewportRequest: ViewportRenderRequest? = null
    private var desiredRequest: ViewportRenderRequest? = null
    private var renderJob: Job? = null

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        canvas.drawRect(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat(), maskPaint)

        val projection = mapView.projection
        val northwest = projection.toPixels(
            GeoPoint(bounds.northeast.latitude, bounds.southwest.longitude),
            Point()
        )
        val southeast = projection.toPixels(
            GeoPoint(bounds.southwest.latitude, bounds.northeast.longitude),
            Point()
        )
        val target = normalizedRect(
            northwest.x.toFloat(),
            northwest.y.toFloat(),
            southeast.x.toFloat(),
            southeast.y.toFloat()
        )
        baseBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, null, target, imagePaint)
            }
        }

        val visible = RectF(
            0f,
            0f,
            mapView.width.toFloat(),
            mapView.height.toFloat()
        ).intersectionWith(target) ?: return
        val request = renderRequestFor(target, visible) ?: return
        val visiblePageRect = pageRectFor(target, visible) ?: return
        drawCachedViewport(canvas, target, visible)

        val cachedRequest = viewportRequest
        if (cachedRequest == null ||
            !cachedRequest.pageRect.containsAll(visiblePageRect) ||
            !cachedRequest.isGoodEnoughFor(request)
        ) {
            scheduleViewportRender(request, mapView)
        }
    }

    override fun onDetach(mapView: MapView) {
        renderScope.cancel()
        baseBitmap?.let { if (!it.isRecycled) it.recycle() }
        viewportBitmap?.let { if (!it.isRecycled) it.recycle() }
        super.onDetach(mapView)
    }

    private fun scheduleViewportRender(request: ViewportRenderRequest, mapView: MapView) {
        desiredRequest = request
        if (renderJob?.isActive == true) return

        renderJob = renderScope.launch {
            while (isActive) {
                val nextRequest = desiredRequest ?: break
                desiredRequest = null
                val bitmap = runCatching {
                    withContext(Dispatchers.IO) {
                        PdfPageRenderer.renderFirstPageRegion(
                            context = appContext,
                            uri = uri,
                            pageRect = nextRequest.pageRect,
                            outputWidth = nextRequest.outputWidth,
                            outputHeight = nextRequest.outputHeight
                        )
                    }
                }.getOrNull() ?: break

                if (!isActive) {
                    bitmap.recycle()
                    return@launch
                }

                viewportBitmap?.let { if (!it.isRecycled) it.recycle() }
                viewportBitmap = bitmap
                viewportRequest = nextRequest
                mapView.invalidate()
            }
        }
    }

    private fun renderRequestFor(target: RectF, visible: RectF): ViewportRenderRequest? {
        val visiblePageRect = pageRectFor(target, visible) ?: return null
        val pageRect = expandedPageRect(visiblePageRect)
        val screenRect = screenRectFor(target, pageRect)
        val scaled = constrainedOutputSize(
            width = ceil(screenRect.width() * VIEWPORT_SUPERSAMPLE).roundToInt(),
            height = ceil(screenRect.height() * VIEWPORT_SUPERSAMPLE).roundToInt()
        )
        return ViewportRenderRequest(pageRect, scaled.first, scaled.second)
    }

    private fun pageRectFor(target: RectF, screenRect: RectF): RectF? {
        val targetWidth = target.width()
        val targetHeight = target.height()
        if (targetWidth <= 1f || targetHeight <= 1f || screenRect.width() <= 1f || screenRect.height() <= 1f) {
            return null
        }

        val leftRatio = ((screenRect.left - target.left) / targetWidth).coerceIn(0f, 1f)
        val topRatio = ((screenRect.top - target.top) / targetHeight).coerceIn(0f, 1f)
        val rightRatio = ((screenRect.right - target.left) / targetWidth).coerceIn(0f, 1f)
        val bottomRatio = ((screenRect.bottom - target.top) / targetHeight).coerceIn(0f, 1f)
        if (rightRatio <= leftRatio || bottomRatio <= topRatio) return null

        return RectF(
            leftRatio * pageInfo.pageWidth,
            topRatio * pageInfo.pageHeight,
            rightRatio * pageInfo.pageWidth,
            bottomRatio * pageInfo.pageHeight
        )
    }

    private fun expandedPageRect(visiblePageRect: RectF): RectF {
        val expandX = visiblePageRect.width() * VIEWPORT_OVERSCAN
        val expandY = visiblePageRect.height() * VIEWPORT_OVERSCAN
        return RectF(
            (visiblePageRect.left - expandX).coerceAtLeast(0f),
            (visiblePageRect.top - expandY).coerceAtLeast(0f),
            (visiblePageRect.right + expandX).coerceAtMost(pageInfo.pageWidth.toFloat()),
            (visiblePageRect.bottom + expandY).coerceAtMost(pageInfo.pageHeight.toFloat())
        )
    }

    private fun screenRectFor(target: RectF, pageRect: RectF): RectF =
        RectF(
            target.left + (pageRect.left / pageInfo.pageWidth) * target.width(),
            target.top + (pageRect.top / pageInfo.pageHeight) * target.height(),
            target.left + (pageRect.right / pageInfo.pageWidth) * target.width(),
            target.top + (pageRect.bottom / pageInfo.pageHeight) * target.height()
        )

    private fun drawCachedViewport(canvas: Canvas, target: RectF, visible: RectF): Boolean {
        val bitmap = viewportBitmap ?: return false
        val request = viewportRequest ?: return false
        if (bitmap.isRecycled) return false

        val cacheScreenRect = screenRectFor(target, request.pageRect)
        val dest = cacheScreenRect.intersectionWith(visible) ?: return false
        val src = Rect(
            (((dest.left - cacheScreenRect.left) / cacheScreenRect.width()) * bitmap.width)
                .roundToInt()
                .coerceIn(0, bitmap.width - 1),
            (((dest.top - cacheScreenRect.top) / cacheScreenRect.height()) * bitmap.height)
                .roundToInt()
                .coerceIn(0, bitmap.height - 1),
            (((dest.right - cacheScreenRect.left) / cacheScreenRect.width()) * bitmap.width)
                .roundToInt()
                .coerceIn(1, bitmap.width),
            (((dest.bottom - cacheScreenRect.top) / cacheScreenRect.height()) * bitmap.height)
                .roundToInt()
                .coerceIn(1, bitmap.height)
        )
        if (src.width() <= 0 || src.height() <= 0) return false
        canvas.drawBitmap(bitmap, src, dest, imagePaint)
        return true
    }

    private fun constrainedOutputSize(width: Int, height: Int): Pair<Int, Int> {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val dimensionScale = minOf(
            1.0,
            MAX_VIEWPORT_DIMENSION_PX.toDouble() / safeWidth,
            MAX_VIEWPORT_DIMENSION_PX.toDouble() / safeHeight
        )
        val pixelScale = minOf(
            1.0,
            sqrt(MAX_VIEWPORT_PIXELS.toDouble() / (safeWidth.toDouble() * safeHeight.toDouble()))
        )
        val scale = minOf(dimensionScale, pixelScale)
        return (safeWidth * scale).roundToInt().coerceAtLeast(1) to
            (safeHeight * scale).roundToInt().coerceAtLeast(1)
    }

    private data class ViewportRenderRequest(
        val pageRect: RectF,
        val outputWidth: Int,
        val outputHeight: Int
    ) {
        private val pixelsPerPageX: Float
            get() = outputWidth / pageRect.width().coerceAtLeast(1f)
        private val pixelsPerPageY: Float
            get() = outputHeight / pageRect.height().coerceAtLeast(1f)

        fun isGoodEnoughFor(other: ViewportRenderRequest): Boolean =
            pageRect.left <= other.pageRect.left + PAGE_RECT_TOLERANCE &&
                pageRect.top <= other.pageRect.top + PAGE_RECT_TOLERANCE &&
                pageRect.right >= other.pageRect.right - PAGE_RECT_TOLERANCE &&
                pageRect.bottom >= other.pageRect.bottom - PAGE_RECT_TOLERANCE &&
                pixelsPerPageX >= other.pixelsPerPageX * MIN_QUALITY_RATIO &&
                pixelsPerPageY >= other.pixelsPerPageY * MIN_QUALITY_RATIO
    }

    private fun RectF.containsAll(other: RectF): Boolean =
        left <= other.left + PAGE_RECT_TOLERANCE &&
            top <= other.top + PAGE_RECT_TOLERANCE &&
            right >= other.right - PAGE_RECT_TOLERANCE &&
            bottom >= other.bottom - PAGE_RECT_TOLERANCE

    private fun RectF.intersectionWith(other: RectF): RectF? {
        val left = maxOf(left, other.left)
        val top = maxOf(top, other.top)
        val right = minOf(right, other.right)
        val bottom = minOf(bottom, other.bottom)
        return if (right > left && bottom > top) RectF(left, top, right, bottom) else null
    }

    private fun normalizedRect(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF(
            minOf(left, right),
            minOf(top, bottom),
            maxOf(left, right),
            maxOf(top, bottom)
        )

    companion object {
        private const val VIEWPORT_SUPERSAMPLE = 1.5f
        private const val VIEWPORT_OVERSCAN = 0.75f
        private const val MAX_VIEWPORT_DIMENSION_PX = 4096
        private const val MAX_VIEWPORT_PIXELS = 16_000_000
        private const val PAGE_RECT_TOLERANCE = 1.0f
        private const val MIN_QUALITY_RATIO = 0.85f
    }
}
