package com.tacmap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.caverock.androidsvg.SVG
import com.tacmap.R
import com.tacmap.waypoints.MarkerSymbol
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.SymbolFunction
import com.tacmap.waypoints.TacticalControlMeasure
import com.tacmap.waypoints.TaskColor
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Renders map marker drawables. Military units = SVGs from milsymbol,
 * tactical control measures = shared AppSymbols assets under appsymbols/.
 */
object SymbolIconFactory {
    private const val MILSYMBOL_MARKER_SCALE = 1.0f
    /** Longest side of a baked task icon. The map scales task artwork itself,
     *  so this only bounds hit-test, preview and KMZ bitmaps of stretched tasks. */
    private const val MAX_TASK_ICON_PX = 1024.0
    /** iOS draws echelon marks in points on its 56 pt reference symbol. */
    private const val ECHELON_REFERENCE_SYMBOL_POINTS = 56f
    private val cache = mutableMapOf<String, Bitmap>()
    private val taskArtworkCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val visibleBoundsCache = mutableMapOf<String, Rect>()
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
                    scaleY = waypoint.scaleY,
                    color = waypoint.taskColor
                )
                is WaypointKind.Marker -> renderMarker(context, kind.marker)
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

    /// Visible (non-transparent) bounds of the icon bitmap. Used to
    /// anchor labels below the visible bottom regardless of padding.
    /// Cached so the per-pixel scan only runs once per kind.
    fun visibleBoundsFor(context: Context, waypoint: Waypoint): Rect {
        val key = cacheKey(context, waypoint)
        visibleBoundsCache[key]?.let { return it }
        val drawable = drawableFor(context, waypoint)
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bmp = if (drawable is BitmapDrawable) drawable.bitmap else {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(b))
            b
        }
        val bounds = visibleBounds(bmp) ?: Rect(0, 0, w, h)
        visibleBoundsCache[key] = bounds
        return bounds
    }

    fun anchorFor(context: Context, waypoint: Waypoint): Pair<Float, Float> {
        return when (val kind = waypoint.kind) {
            WaypointKind.Generic -> markerAnchorBottom
            is WaypointKind.ControlMeasure -> markerAnchorCenter
            is WaypointKind.Marker -> markerAnchorCenter
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
                "ctrl|$density|${kind.measure.assetName}|$rot|$sx|$sy|${waypoint.taskColor.name}"
            }
            is WaypointKind.Marker ->
                "mk|$density|${kind.marker.set}|${kind.marker.symbolId}|${kind.marker.colorHex}|${kind.marker.custom != null}"
        }
    }

    private fun Double.roundKey(decimals: Int): String = "%.${decimals}f".format(this)

    /// Marker badge: a filled coloured disc + white ring + short white code, for
    /// the airsoft/SAR/POI symbol sets. Simple + recognizable; mirrors the iOS
    /// MarkerSymbolRenderer badge.
    private fun renderMarker(context: Context, marker: MarkerSymbol): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (34f * density).toInt().coerceAtLeast(24)
        if (marker.set == com.tacmap.waypoints.MarkerSet.CUSTOM) marker.custom?.image()?.let { source ->
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val scale = min(size.toFloat() / source.width, size.toFloat() / source.height)
            val w = source.width * scale; val h = source.height * scale
            Canvas(bitmap).drawBitmap(source, null, RectF((size-w)/2, (size-h)/2, (size+w)/2, (size+h)/2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return bitmap
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f - 1.5f * density

        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = parseHexColor(marker.colorHex)
            setShadowLayer(2f * density, 0f, 1f * density, 0x70000000)
        }
        canvas.drawCircle(cx, cy, r, fill)
        val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f * density
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawCircle(cx, cy, r, ring)

        val code = marker.entry.code
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
            // Shrink for longer codes so 3-char labels still fit the badge.
            textSize = when {
                code.length <= 1 -> size * 0.5f
                code.length == 2 -> size * 0.38f
                else -> size * 0.28f
            }
        }
        val fm = text.fontMetrics
        canvas.drawText(code, cx, cy - (fm.ascent + fm.descent) / 2f, text)
        return bmp
    }

    private fun parseHexColor(hex: String): Int = try {
        android.graphics.Color.parseColor(hex)
    } catch (_: Throwable) {
        0xFF3B7BE0.toInt()
    }

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
        drawEchelon(canvas, spec.echelon, RectF(0f, 0f, width.toFloat(), echelonH),
            unit = width / ECHELON_REFERENCE_SYMBOL_POINTS)

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

    /// APP-6 echelon marks as vector shapes with the geometry of iOS
    /// `MilitarySymbolView.drawEchelon`, so units match across platforms. iOS
    /// gives dot, bar and stroke sizes in points on its 56 pt symbol; [unit]
    /// is one of those points at this bitmap's size.
    private fun drawEchelon(canvas: Canvas, echelon: SymbolEchelon, rect: RectF, unit: Float) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.BLACK
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = 2f * unit
        }
        when (echelon) {
            SymbolEchelon.TEAM -> {
                // Open ring with a diagonal slash through it.
                val r = rect.height() * 0.42f
                canvas.drawCircle(cx, cy, r, stroke)
                val s = r * 1.2f
                canvas.drawLine(cx - s, cy + s, cx + s, cy - s, stroke)
            }
            SymbolEchelon.SECTION ->
                drawEchelonDots(canvas, 1, cx, cy, radius = 3.2f * unit, spacing = 0f, paint = fill)
            SymbolEchelon.PLATOON ->
                drawEchelonDots(canvas, 3, cx, cy, radius = 3.2f * unit, spacing = 9f * unit, paint = fill)
            SymbolEchelon.COMPANY ->
                drawEchelonBars(canvas, 1, cx, top = rect.top + unit, height = rect.height() - 2f * unit,
                    barWidth = 3.2f * unit, spacing = 0f, paint = fill)
            SymbolEchelon.BATTALION_REGIMENT ->
                drawEchelonBars(canvas, 2, cx, top = rect.top + unit, height = rect.height() - 2f * unit,
                    barWidth = 3.2f * unit, spacing = 8f * unit, paint = fill)
            SymbolEchelon.BRIGADE ->
                drawEchelonXs(canvas, 1, cx, top = rect.top + 2f * unit,
                    size = (rect.height() - 4f * unit) * 0.85f, spacing = 0f, paint = stroke)
            SymbolEchelon.DIVISION ->
                drawEchelonXs(canvas, 2, cx, top = rect.top + 2f * unit,
                    size = (rect.height() - 4f * unit) * 0.85f, spacing = 9f * unit, paint = stroke)
        }
    }

    private fun drawEchelonDots(
        canvas: Canvas, count: Int, cx: Float, cy: Float, radius: Float, spacing: Float, paint: Paint
    ) {
        val totalWidth = (count - 1) * spacing
        for (i in 0 until count) {
            canvas.drawCircle(cx - totalWidth / 2f + i * spacing, cy, radius, paint)
        }
    }

    private fun drawEchelonBars(
        canvas: Canvas, count: Int, cx: Float, top: Float, height: Float,
        barWidth: Float, spacing: Float, paint: Paint
    ) {
        val totalWidth = (count - 1) * spacing
        for (i in 0 until count) {
            val x = cx - totalWidth / 2f + i * spacing
            canvas.drawRect(x - barWidth / 2f, top, x + barWidth / 2f, top + height, paint)
        }
    }

    private fun drawEchelonXs(
        canvas: Canvas, count: Int, cx: Float, top: Float, size: Float, spacing: Float, paint: Paint
    ) {
        val totalWidth = (count - 1) * spacing
        for (i in 0 until count) {
            val x = cx - totalWidth / 2f + i * spacing
            canvas.drawLine(x - size / 2f, top, x + size / 2f, top + size, paint)
            canvas.drawLine(x + size / 2f, top, x - size / 2f, top + size, paint)
        }
    }

    /**
     * Task artwork at its own aspect ratio, uncoloured and unrotated, like the
     * iOS asset. The map draws it through [drawControlMeasure] at the task's
     * zoom-dependent size, so one bitmap per measure serves every zoom.
     */
    fun controlMeasureArtwork(context: Context, measure: TacticalControlMeasure): Bitmap {
        val key = "${context.resources.displayMetrics.densityDpi}|${measure.assetName}"
        taskArtworkCache.get(key)?.let { return it }
        return loadControlMeasureArtwork(context, measure).also { taskArtworkCache.put(key, it) }
    }

    /** Anti-aliased, filtered paint that recolours task line art to [color]. */
    fun controlMeasurePaint(color: TaskColor): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            // Black = asset's native colour, skip filter. Other colours recolour
            // opaque pixels via SRC_IN, preserving alpha on the edges.
            if (color != TaskColor.BLACK) {
                colorFilter = PorterDuffColorFilter(color.argb, PorterDuff.Mode.SRC_IN)
            }
        }

    /** Width over height of task [artwork], for [TaskGraphicSizing]. */
    fun artworkAspect(artwork: Bitmap): Double =
        artwork.width.toDouble() / artwork.height.coerceAtLeast(1)

    /**
     * Draws task [artwork] centred on ([cx], [cy]) for a [boxWidth] × [boxHeight]
     * px task box, as iOS does: the artwork is fitted into the square symbol,
     * rotated inside it, and the square is then stretched to the box.
     */
    fun drawControlMeasure(
        canvas: Canvas,
        artwork: Bitmap,
        cx: Float,
        cy: Float,
        boxWidth: Float,
        boxHeight: Float,
        rotation: Double,
        paint: Paint,
    ) {
        val unit = TaskGraphicSizing.artworkSize(TaskGraphicSize(1.0, 1.0), artworkAspect(artwork))
        val halfW = (unit.width / 2).toFloat()
        val halfH = (unit.height / 2).toFloat()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(boxWidth, boxHeight)
        canvas.rotate(rotation.toFloat())
        canvas.drawBitmap(artwork, null, RectF(-halfW, -halfH, halfW, halfH), paint)
        canvas.restore()
    }

    /** The task at the reference zoom (one dp per metre), for hit targets,
     *  previews and KMZ images. The map itself draws the artwork directly. */
    private fun renderControlMeasure(
        context: Context,
        measure: TacticalControlMeasure,
        rotation: Double,
        scaleX: Double,
        scaleY: Double,
        color: TaskColor = TaskColor.BLACK
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val artwork = controlMeasureArtwork(context, measure)
        val base = TaskGraphicSizing.BASE_SIZE_POINTS * density
        val boxW = base * scaleX.coerceIn(MIN_SYMBOL_SCALE, MAX_SYMBOL_SCALE)
        val boxH = base * scaleY.coerceIn(MIN_SYMBOL_SCALE, MAX_SYMBOL_SCALE)
        val bounds = TaskGraphicSizing.screenBounds(TaskGraphicSize(boxW, boxH), artworkAspect(artwork), rotation)
        // A 20× task would be thousands of px wide; keep the bitmap bounded.
        val shrink = min(1.0, MAX_TASK_ICON_PX / max(bounds.width, bounds.height))
        val bitmapW = ceil(bounds.width * shrink).toInt().coerceAtLeast(1)
        val bitmapH = ceil(bounds.height * shrink).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        drawControlMeasure(
            canvas = Canvas(bitmap),
            artwork = artwork,
            cx = bitmapW / 2f,
            cy = bitmapH / 2f,
            boxWidth = (boxW * shrink).toFloat(),
            boxHeight = (boxH * shrink).toFloat(),
            rotation = rotation,
            paint = controlMeasurePaint(color),
        )
        return bitmap
    }

    /// iOS fits the whole asset, transparent margin included, into the symbol
    /// square, so the artwork is kept uncropped at its own aspect ratio.
    private fun loadControlMeasureArtwork(context: Context, measure: TacticalControlMeasure): Bitmap {
        val longSide = (256f * context.resources.displayMetrics.density).coerceAtLeast(256f)
        runCatching {
            val svg = SVG.getFromAsset(context.assets, "appsymbols/${measure.assetName}.svg")
            val viewBox = svg.documentViewBox
            val aspect = when {
                viewBox != null && viewBox.height() > 0f -> viewBox.width() / viewBox.height()
                svg.documentHeight > 0f -> svg.documentWidth / svg.documentHeight
                else -> 1f
            }.takeIf { it.isFinite() && it > 0f } ?: 1f
            val width = ceil(if (aspect >= 1f) longSide else longSide * aspect).toInt().coerceAtLeast(1)
            val height = ceil(if (aspect >= 1f) longSide / aspect else longSide).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()))
            }
        }.getOrNull()?.let { return it }

        // PNG line art is used at its native resolution.
        runCatching {
            context.assets.open("appsymbols/${measure.assetName}.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.let { return it }

        val size = longSide.toInt()
        val fallback = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        drawFallbackText(Canvas(fallback), measure.displayName.initials(), rect, paint)
        return fallback
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
