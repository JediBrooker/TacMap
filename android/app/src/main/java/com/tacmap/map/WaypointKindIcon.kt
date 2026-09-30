package com.tacmap.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import com.tacmap.waypoints.TaskColor
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The rendered symbol for lists, cards and editor previews, drawn by
 * [SymbolIconFactory] exactly as on the map and fitted to [size]. Mirrors iOS
 * `WaypointKindIcon`: units, tasks and markers show their real artwork and a
 * plain point shows the map pin. [rotation] and [taskColor] apply to tasks
 * only. Decorative: callers label the row or card it sits in.
 *
 * Task graphics are black line art, so callers put the icon on a light tile.
 */
@Composable
internal fun WaypointKindIcon(
    kind: WaypointKind,
    size: Dp,
    modifier: Modifier = Modifier,
    rotation: Double = 0.0,
    taskColor: TaskColor = TaskColor.BLACK,
) {
    val context = LocalContext.current
    if (kind is WaypointKind.ControlMeasure) {
        // Drawn straight from the artwork: a rotation slider must not bake
        // and cache a bitmap for every angle it passes through.
        val artwork = remember(kind.measure) { SymbolIconFactory.controlMeasureArtwork(context, kind.measure) }
        val paint = remember(taskColor) { SymbolIconFactory.controlMeasurePaint(taskColor) }
        Canvas(modifier.size(size)) {
            val unit = TaskGraphicSizing.screenBounds(
                TaskGraphicSize(1.0, 1.0),
                SymbolIconFactory.artworkAspect(artwork),
                rotation,
            )
            val side = min(this.size.width / unit.width, this.size.height / unit.height).toFloat()
            drawIntoCanvas { canvas ->
                SymbolIconFactory.drawControlMeasure(
                    canvas = canvas.nativeCanvas,
                    artwork = artwork,
                    cx = center.x,
                    cy = center.y,
                    boxWidth = side,
                    boxHeight = side,
                    rotation = rotation,
                    paint = paint,
                )
            }
        }
        return
    }

    val symbol = remember(kind) {
        val placeholder = Waypoint(name = "", latitude = 0.0, longitude = 0.0, kind = kind)
        val drawable = SymbolIconFactory.drawableFor(context, placeholder)
        val bitmap = drawable.toBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
        )
        // Crop transparent padding (echelon strip, HQ staff reserve) so the
        // visible symbol fills the icon instead of huddling in a corner.
        val visible = SymbolIconFactory.visibleBoundsFor(context, placeholder)
        val left = visible.left.coerceIn(0, bitmap.width - 1)
        val top = visible.top.coerceIn(0, bitmap.height - 1)
        val width = visible.width().coerceIn(1, bitmap.width - left)
        val height = visible.height().coerceIn(1, bitmap.height - top)
        Triple(bitmap.asImageBitmap(), IntOffset(left, top), IntSize(width, height))
    }
    val (image, srcOffset, srcSize) = symbol
    Canvas(modifier.size(size)) {
        val scale = min(this.size.width / srcSize.width, this.size.height / srcSize.height)
        val dstWidth = (srcSize.width * scale).roundToInt().coerceAtLeast(1)
        val dstHeight = (srcSize.height * scale).roundToInt().coerceAtLeast(1)
        drawImage(
            image = image,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = IntOffset(
                ((this.size.width - dstWidth) / 2f).roundToInt(),
                ((this.size.height - dstHeight) / 2f).roundToInt(),
            ),
            dstSize = IntSize(dstWidth, dstHeight),
        )
    }
}
