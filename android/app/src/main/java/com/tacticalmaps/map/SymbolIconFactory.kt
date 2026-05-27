package com.tacticalmaps.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.caverock.androidsvg.SVG
import com.tacticalmaps.R
import com.tacticalmaps.waypoints.MilitarySymbolSpec
import com.tacticalmaps.waypoints.SymbolAffiliation
import com.tacticalmaps.waypoints.SymbolEchelon
import com.tacticalmaps.waypoints.SymbolFunction
import com.tacticalmaps.waypoints.TacticalControlMeasure
import com.tacticalmaps.waypoints.Waypoint
import com.tacticalmaps.waypoints.WaypointKind
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/**
 * Renders Android map marker drawables. Military units use SVGs generated
 * from spatialillusions/milsymbol; tactical control measures use the
 * shared AppSymbols catalogue under android assets/appsymbols.
 */
object SymbolIconFactory {
    private const val MILSYMBOL_MARKER_SCALE = 1.0f
    private val cache = mutableMapOf<String, Bitmap>()
    private var milsymbolMetrics: Map<String, MilsymbolMetric>? = null

    fun drawableFor(context: Context, waypoint: Waypoint): Drawable {
        val kind = waypoint.kind
        if (kind == WaypointKind.Generic) {
            return ContextCompat.getDrawable(context, R.drawable.ic_waypoint_marker)!!
        }

        val key = cacheKey(context, waypoint)
        val bitmap = cache.getOrPut(key) {
            when (kind) {
                WaypointKind.Generic -> error("generic handled above")
                is WaypointKind.Military -> renderMilitary(context, kind.spec)
                is WaypointKind.ControlMeasure -> renderControlMeasure(
                    context = context,
                    measure = kind.measure,
                    rotation = waypoint.rotation,
                    scaleX = waypoint.scaleX,
                    scaleY = waypoint.scaleY
                )
            }
        }
        return when (kind) {
            is WaypointKind.Military -> FixedSizeBitmapDrawable(
                context.resources,
                bitmap,
                bitmap.width,
                bitmap.height
            )
            else -> BitmapDrawable(context.resources, bitmap)
        }
    }

    fun anchorFor(context: Context, waypoint: Waypoint): Pair<Float, Float> {
        return when (val kind = waypoint.kind) {
            WaypointKind.Generic -> markerAnchorBottom
            is WaypointKind.ControlMeasure -> markerAnchorCenter
            is WaypointKind.Military -> milsymbolMetric(context, kind.spec)?.let {
                it.anchorU to it.anchorV
            } ?: markerAnchorCenter
        }
    }

    private val markerAnchorBottom = 0.5f to 1.0f
    private val markerAnchorCenter = 0.5f to 0.5f

    private fun cacheKey(context: Context, waypoint: Waypoint): String {
        val density = context.resources.displayMetrics.densityDpi
        return when (val kind = waypoint.kind) {
            WaypointKind.Generic -> "generic|$density"
            is WaypointKind.Military -> "mil|$density|$MILSYMBOL_MARKER_SCALE|${kind.spec}"
            is WaypointKind.ControlMeasure -> {
                val rot = waypoint.rotation.roundKey(1)
                val sx = waypoint.scaleX.roundKey(2)
                val sy = waypoint.scaleY.roundKey(2)
                "ctrl|$density|${kind.measure.assetName}|$rot|$sx|$sy"
            }
        }
    }

    private fun Double.roundKey(decimals: Int): String = "%.${decimals}f".format(this)

    private data class MilsymbolMetric(
        val anchorU: Float,
        val anchorV: Float,
        val width: Float,
        val height: Float
    )

    private fun renderMilitary(context: Context, spec: MilitarySymbolSpec): Bitmap {
        renderMilsymbol(context, spec)?.let { return it }
        return renderLegacyMilitary(context, spec)
    }

    private fun renderMilsymbol(context: Context, spec: MilitarySymbolSpec): Bitmap? {
        val assetName = milsymbolAssetName(spec)
        val metric = milsymbolMetric(context, spec) ?: return null
        return runCatching {
            val svg = SVG.getFromAsset(context.assets, "milsymbol/$assetName.svg")
            val density = context.resources.displayMetrics.density
            val width = ceil(metric.width * density * MILSYMBOL_MARKER_SCALE).toInt().coerceAtLeast(1)
            val height = ceil(metric.height * density * MILSYMBOL_MARKER_SCALE).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.density = Bitmap.DENSITY_NONE
            svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()))
            bitmap
        }.getOrNull()
    }

    private fun milsymbolMetric(context: Context, spec: MilitarySymbolSpec): MilsymbolMetric? =
        milsymbolMetrics(context)[milsymbolAssetName(spec)]

    private fun milsymbolMetrics(context: Context): Map<String, MilsymbolMetric> {
        milsymbolMetrics?.let { return it }
        val loaded = runCatching {
            context.assets.open("milsymbol/manifest.tsv").bufferedReader().useLines { lines ->
                lines
                    .drop(1)
                    .mapNotNull { line ->
                        val parts = line.split('\t')
                        if (parts.size < 5) return@mapNotNull null
                        parts[0] to MilsymbolMetric(
                            anchorU = parts[1].toFloat(),
                            anchorV = parts[2].toFloat(),
                            width = parts[3].toFloat(),
                            height = parts[4].toFloat()
                        )
                    }
                    .toMap()
            }
        }.getOrDefault(emptyMap())
        milsymbolMetrics = loaded
        return loaded
    }

    private fun milsymbolAssetName(spec: MilitarySymbolSpec): String =
        "${spec.affiliation.name.lowercase()}_${spec.function.name.lowercase()}_" +
            "${spec.echelon.name.lowercase()}_${if (spec.isHeadquarters) "hq" else "unit"}"

    private fun renderLegacyMilitary(context: Context, spec: MilitarySymbolSpec): Bitmap {
        val density = context.resources.displayMetrics.density
        val base = (64f * density).coerceAtLeast(64f)
        val poleReserve = if (spec.isHeadquarters) base * 0.42f else 0f
        val width = base.toInt()
        val height = ceil(base + poleReserve).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = spec.affiliation.fillColor
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = 2.0f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val echelonH = (height - poleReserve) * 0.28f
        val frameTop = echelonH + (height - poleReserve) * 0.02f
        val frameBottom = height - poleReserve - 2f * density
        val frameH = frameBottom - frameTop
        val frame = militaryFrame(width.toFloat(), frameTop, frameH, spec.affiliation)

        drawFrame(canvas, frame, spec.affiliation, fill, stroke)
        drawFunction(context, canvas, spec.function, spec.affiliation, frame, stroke, textPaint)
        drawEchelon(canvas, spec.echelon, RectF(0f, 0f, width.toFloat(), echelonH), density, textPaint)

        if (spec.isHeadquarters) {
            canvas.drawLine(frame.left, frame.bottom, frame.left, frame.bottom + poleReserve, stroke)
        }
        return bitmap
    }

    private fun militaryFrame(width: Float, frameTop: Float, frameH: Float, affiliation: SymbolAffiliation): RectF {
        return when (affiliation) {
            SymbolAffiliation.FRIEND -> {
                val frameW = min(width - 4f, frameH * 1.5f)
                RectF((width - frameW) / 2f, frameTop, (width + frameW) / 2f, frameTop + frameH)
            }
            SymbolAffiliation.HOSTILE,
            SymbolAffiliation.NEUTRAL,
            SymbolAffiliation.UNKNOWN -> {
                val side = min(width - 6f, frameH)
                RectF((width - side) / 2f, frameTop + (frameH - side) / 2f,
                    (width + side) / 2f, frameTop + (frameH + side) / 2f)
            }
        }
    }

    private fun drawFrame(
        canvas: Canvas,
        frame: RectF,
        affiliation: SymbolAffiliation,
        fill: Paint,
        stroke: Paint
    ) {
        when (affiliation) {
            SymbolAffiliation.FRIEND -> {
                canvas.drawRect(frame, fill)
                canvas.drawRect(frame, stroke)
            }
            SymbolAffiliation.HOSTILE, SymbolAffiliation.NEUTRAL -> {
                val path = Path().apply {
                    moveTo(frame.centerX(), frame.top)
                    lineTo(frame.right, frame.centerY())
                    lineTo(frame.centerX(), frame.bottom)
                    lineTo(frame.left, frame.centerY())
                    close()
                }
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
            SymbolAffiliation.UNKNOWN -> {
                val path = Path().apply {
                    addOval(RectF(frame.left, frame.top, frame.right, frame.centerY()), Path.Direction.CW)
                    addOval(RectF(frame.centerX(), frame.top, frame.right, frame.bottom), Path.Direction.CW)
                    addOval(RectF(frame.left, frame.centerY(), frame.right, frame.bottom), Path.Direction.CW)
                    addOval(RectF(frame.left, frame.top, frame.centerX(), frame.bottom), Path.Direction.CW)
                }
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
        }
    }

    private fun drawFunction(
        context: Context,
        canvas: Canvas,
        function: SymbolFunction,
        affiliation: SymbolAffiliation,
        frame: RectF,
        stroke: Paint,
        textPaint: Paint
    ) {
        if (function == SymbolFunction.UNSPECIFIED) return
        val inset = when (affiliation) {
            SymbolAffiliation.FRIEND -> 0f
            SymbolAffiliation.HOSTILE, SymbolAffiliation.NEUTRAL -> frame.width() * 0.15f
            SymbolAffiliation.UNKNOWN -> frame.width() * 0.18f
        }
        val glyphRect = RectF(frame).apply { inset(inset, inset) }
        canvas.save()
        clipToAffiliationFrame(canvas, affiliation, frame)
        if (drawNativeFunction(canvas, function, glyphRect, stroke)) {
            canvas.restore()
            return
        }
        if (drawAssetCentered(context, canvas, function.assetName, glyphRect)) {
            canvas.restore()
            return
        }

        when (function) {
            SymbolFunction.ARTILLERY -> {
                stroke.style = Paint.Style.FILL
                canvas.drawCircle(glyphRect.centerX(), glyphRect.centerY(), glyphRect.height() * 0.1f, stroke)
                stroke.style = Paint.Style.STROKE
            }
            else -> drawFallbackText(canvas, function.displayName.initials(), glyphRect, textPaint)
        }
        canvas.restore()
    }

    private fun drawNativeFunction(
        canvas: Canvas,
        function: SymbolFunction,
        rect: RectF,
        stroke: Paint
    ): Boolean {
        when (function) {
            SymbolFunction.INFANTRY -> drawInfantry(canvas, rect, stroke)
            SymbolFunction.ARMOUR -> drawArmour(canvas, rect, stroke)
            SymbolFunction.MECH_INFANTRY -> {
                drawInfantry(canvas, rect, stroke)
                drawArmour(canvas, RectF(rect).apply { inset(rect.width() * 0.18f, rect.height() * 0.26f) }, stroke)
            }
            SymbolFunction.MOTORISED_INFANTRY -> {
                drawInfantry(canvas, rect, stroke)
                canvas.drawLine(rect.centerX(), rect.top, rect.centerX(), rect.bottom, stroke)
            }
            SymbolFunction.ANTI_TANK -> drawAntiTank(canvas, rect, stroke)
            SymbolFunction.SIGNAL -> drawSignal(canvas, rect, stroke)
            SymbolFunction.MAINTENANCE -> drawMaintenance(canvas, rect, stroke)
            else -> return false
        }
        return true
    }

    private fun drawInfantry(canvas: Canvas, rect: RectF, stroke: Paint) {
        canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, stroke)
        canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, stroke)
    }

    private fun drawArmour(canvas: Canvas, rect: RectF, stroke: Paint) {
        val oval = RectF(rect).apply { inset(rect.width() * 0.04f, rect.height() * 0.18f) }
        canvas.drawOval(oval, stroke)
    }

    private fun drawAntiTank(canvas: Canvas, rect: RectF, stroke: Paint) {
        val inset = stroke.strokeWidth * 0.5f
        val path = Path().apply {
            moveTo(rect.left + inset, rect.bottom - inset)
            lineTo(rect.centerX(), rect.top + inset)
            lineTo(rect.right - inset, rect.bottom - inset)
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawSignal(canvas: Canvas, rect: RectF, stroke: Paint) {
        val inset = stroke.strokeWidth * 0.5f
        val waist = rect.width() * 0.08f
        val path = Path().apply {
            moveTo(rect.left + inset, rect.top + inset)
            lineTo(rect.centerX() - waist, rect.bottom - inset)
            lineTo(rect.centerX() + waist, rect.top + inset)
            lineTo(rect.right - inset, rect.bottom - inset)
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawMaintenance(canvas: Canvas, rect: RectF, stroke: Paint) {
        val diameter = rect.height() * 0.78f
        val top = rect.centerY() - diameter / 2f
        val bottom = rect.centerY() + diameter / 2f
        val xInset = stroke.strokeWidth * 0.5f
        val leftArc = RectF(rect.left + xInset, top, rect.left + xInset + diameter, bottom)
        val rightArc = RectF(rect.right - xInset - diameter, top, rect.right - xInset, bottom)
        canvas.drawArc(leftArc, -90f, 180f, false, stroke)
        canvas.drawLine(leftArc.centerX(), rect.centerY(), rightArc.centerX(), rect.centerY(), stroke)
        canvas.drawArc(rightArc, 90f, 180f, false, stroke)
    }

    private fun clipToAffiliationFrame(canvas: Canvas, affiliation: SymbolAffiliation, frame: RectF) {
        when (affiliation) {
            SymbolAffiliation.FRIEND -> canvas.clipRect(frame)
            SymbolAffiliation.HOSTILE, SymbolAffiliation.NEUTRAL -> canvas.clipPath(Path().apply {
                moveTo(frame.centerX(), frame.top)
                lineTo(frame.right, frame.centerY())
                lineTo(frame.centerX(), frame.bottom)
                lineTo(frame.left, frame.centerY())
                close()
            })
            SymbolAffiliation.UNKNOWN -> canvas.clipPath(Path().apply {
                addOval(RectF(frame.left, frame.top, frame.right, frame.centerY()), Path.Direction.CW)
                addOval(RectF(frame.centerX(), frame.top, frame.right, frame.bottom), Path.Direction.CW)
                addOval(RectF(frame.left, frame.centerY(), frame.right, frame.bottom), Path.Direction.CW)
                addOval(RectF(frame.left, frame.top, frame.centerX(), frame.bottom), Path.Direction.CW)
            })
        }
    }

    private fun drawEchelon(
        canvas: Canvas,
        echelon: SymbolEchelon,
        rect: RectF,
        density: Float,
        textPaint: Paint
    ) {
        textPaint.textSize = 13f * density
        val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(echelon.glyph, rect.centerX(), y, textPaint)
    }

    private fun renderControlMeasure(
        context: Context,
        measure: TacticalControlMeasure,
        rotation: Double,
        scaleX: Double,
        scaleY: Double
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val base = 64f * density
        val symbolW = (base * scaleX.coerceIn(0.15, 6.0)).toFloat()
        val symbolH = (base * scaleY.coerceIn(0.15, 6.0)).toFloat()
        val canvasSide = ceil(hypot(symbolW, symbolH)).toInt().coerceAtLeast((base * 0.5f).toInt())
        val bitmap = Bitmap.createBitmap(canvasSide, canvasSide, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = canvasSide / 2f
        val cy = canvasSide / 2f
        val dest = RectF(cx - symbolW / 2f, cy - symbolH / 2f, cx + symbolW / 2f, cy + symbolH / 2f)
        val source = controlMeasureSource(context, measure)
        canvas.save()
        canvas.rotate(rotation.toFloat(), cx, cy)
        canvas.drawBitmap(source, null, dest, null)
        canvas.restore()
        return bitmap
    }

    private fun controlMeasureSource(context: Context, measure: TacticalControlMeasure): Bitmap {
        val sourceSize = (256f * context.resources.displayMetrics.density).toInt().coerceAtLeast(256)
        if (measure == TacticalControlMeasure.LANDING_ZONE) {
            return renderLandingZoneSource(sourceSize)
        }

        val raw = Bitmap.createBitmap(sourceSize, sourceSize, Bitmap.Config.ARGB_8888)
        val rawCanvas = Canvas(raw)
        if (!drawAsset(context, rawCanvas, measure.assetName, RectF(0f, 0f, sourceSize.toFloat(), sourceSize.toFloat()))) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            drawFallbackText(rawCanvas, measure.displayName.initials(), RectF(0f, 0f, sourceSize.toFloat(), sourceSize.toFloat()), paint)
        }
        return cropVisible(raw)
    }

    private fun renderLandingZoneSource(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * 0.42f
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText("LZ", size / 2f, y, paint)
        return cropVisible(bitmap)
    }

    private fun cropVisible(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        val pixels = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if ((pixels[x] ushr 24) > 8) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return bitmap
        val pad = 4
        val crop = Rect(
            (left - pad).coerceAtLeast(0),
            (top - pad).coerceAtLeast(0),
            (right + pad + 1).coerceAtMost(width),
            (bottom + pad + 1).coerceAtMost(height)
        )
        return Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
    }

    private fun drawAssetCentered(context: Context, canvas: Canvas, assetName: String, dest: RectF): Boolean {
        val width = ceil(dest.width()).toInt().coerceAtLeast(1)
        val height = ceil(dest.height()).toInt().coerceAtLeast(1)
        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rawCanvas = Canvas(raw)
        if (!drawAsset(context, rawCanvas, assetName, RectF(0f, 0f, width.toFloat(), height.toFloat()))) {
            return false
        }

        val visible = visibleBounds(raw) ?: return false
        val scale = min(width.toFloat() / visible.width(), height.toFloat() / visible.height())
        val scaledWidth = visible.width() * scale
        val scaledHeight = visible.height() * scale
        val scaledDest = RectF(
            dest.centerX() - scaledWidth / 2f,
            dest.centerY() - scaledHeight / 2f,
            dest.centerX() + scaledWidth / 2f,
            dest.centerY() + scaledHeight / 2f
        )
        canvas.drawBitmap(
            raw,
            visible,
            scaledDest,
            null
        )
        return true
    }

    private fun drawAsset(context: Context, canvas: Canvas, assetName: String, dest: RectF): Boolean {
        val svgPath = "appsymbols/$assetName.svg"
        runCatching {
            val svg = SVG.getFromAsset(context.assets, svgPath)
            svg.renderToCanvas(canvas, dest)
        }.onSuccess { return true }

        val pngPath = "appsymbols/$assetName.png"
        return runCatching {
            context.assets.open(pngPath).use { input ->
                val bitmap = BitmapFactory.decodeStream(input) ?: return@runCatching false
                canvas.drawBitmap(bitmap, null, dest, null)
                true
            }
        }.getOrDefault(false)
    }

    private fun visibleBounds(bitmap: Bitmap): Rect? {
        val width = bitmap.width
        val height = bitmap.height
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        val pixels = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if ((pixels[x] ushr 24) > 8) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < left || bottom < top) {
            null
        } else {
            Rect(left, top, right + 1, bottom + 1)
        }
    }

    private fun drawFallbackText(canvas: Canvas, text: String, rect: RectF, paint: Paint) {
        paint.textSize = min(rect.width(), rect.height()) * 0.34f
        val y = rect.centerY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, rect.centerX(), y, paint)
    }

    private fun String.initials(): String =
        split(' ', '-', '/', '(', ')')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }

    private class FixedSizeBitmapDrawable(
        resources: Resources,
        bitmap: Bitmap,
        private val intrinsicWidthPx: Int,
        private val intrinsicHeightPx: Int
    ) : BitmapDrawable(resources, bitmap) {
        override fun getIntrinsicWidth(): Int = intrinsicWidthPx
        override fun getIntrinsicHeight(): Int = intrinsicHeightPx
    }
}
