package com.tacmap.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.ui.Dialog
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

private val TerrainFill = Color(0xFF5C9E5C).copy(alpha = 0.55f)
private val DeadGroundFill = Color(0xFF6B6B6B).copy(alpha = 0.75f)
private val SightClear = Color(0xFFFFA000)
private val SightBlocked = Color(0xFFE53935)

/** Eye and target heights the steppers walk through, metres. */
internal val ProfileHeightSteps = listOf(0.0, 1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 20.0, 30.0, 50.0, 75.0, 100.0, 150.0, 200.0, 300.0, 500.0)

/**
 * Terrain height along a measured line or line drawing. For a straight line
 * between two points it also checks line of sight from an observer at the
 * start to a target at the end and shades the dead ground between them.
 * iOS mirrors this in `ElevationProfileSheet.swift`.
 */
@Composable
internal fun ElevationProfileDialog(
    path: List<ElevationProfile.Coordinate>,
    onDismiss: () -> Unit,
    service: ElevationProfileService = remember { ElevationProfileService() },
) {
    val samples = remember(path) { ElevationProfile.samples(path) }
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(path) { mutableStateOf<ElevationProfileService.Result?>(null) }
    var observerHeight by rememberSaveable { mutableDoubleStateOf(2.0) }
    var targetHeight by rememberSaveable { mutableDoubleStateOf(2.0) }
    var selected by remember(path) { mutableStateOf<Int?>(null) }

    LaunchedEffect(samples, attempt) {
        result = null
        if (samples.size >= 2) result = service.elevations(samples)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 760.dp),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Messages.profileTitle(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(L10n.text("Done")) }
                }
                val current = result
                when {
                    samples.size < 2 -> Text(Messages.profileTooShort())
                    current == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.heightIn(min = 120.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(Messages.profileLoading(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    current is ElevationProfileService.Result.LookupsOff -> Text(Messages.profileLookupsOff())
                    current is ElevationProfileService.Result.NetworkFailed -> {
                        Text(Messages.profileFailed())
                        OutlinedButton(onClick = { attempt++ }) { Text(Messages.profileRetry()) }
                    }
                    current is ElevationProfileService.Result.Heights -> ProfileContent(
                        straightLine = path.size == 2,
                        distances = samples.map { it.distance },
                        elevations = current.metres,
                        observerHeight = observerHeight,
                        targetHeight = targetHeight,
                        onObserverHeight = { observerHeight = it },
                        onTargetHeight = { targetHeight = it },
                        selected = selected,
                        onSelect = { selected = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileContent(
    straightLine: Boolean,
    distances: List<Double>,
    elevations: List<Double>,
    observerHeight: Double,
    targetHeight: Double,
    onObserverHeight: (Double) -> Unit,
    onTargetHeight: (Double) -> Unit,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    val sight = if (straightLine) {
        ElevationProfile.lineOfSight(distances, elevations, observerHeight, targetHeight)
    } else {
        null
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.heightIn(min = 40.dp)) {
        if (selected != null && selected in distances.indices) {
            Text(
                Messages.profileReadout(DisplayFormat.distance(distances[selected]), DisplayFormat.height(elevations[selected])),
                style = MaterialTheme.typography.titleSmall,
            )
            if (sight != null && !sight.visible[selected]) {
                Text(Messages.profileReadoutHidden(), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        } else {
            Text(Messages.profileHint(), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    ProfileChart(distances, elevations, sight, selected, onSelect)
    if (sight != null && sight.visible.contains(false)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(DeadGroundFill))
            Text(Messages.profileDeadGround(), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    ElevationProfile.stats(elevations)?.let { stats ->
        val items = listOf(
            Messages.profileLength() to DisplayFormat.distance(distances.last()),
            Messages.profileClimb() to DisplayFormat.height(stats.ascent),
            Messages.profileStart() to DisplayFormat.height(elevations.first()),
            Messages.profileDescent() to DisplayFormat.height(stats.descent),
            Messages.profileEnd() to DisplayFormat.height(elevations.last()),
            Messages.profileLowest() to DisplayFormat.height(stats.minimum),
            Messages.profileHighest() to DisplayFormat.height(stats.maximum),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(2).forEach { row ->
                Row {
                    row.forEach { (label, value) ->
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = muted)
                            Text(value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    if (straightLine) {
        Text(Messages.profileLosTitle(), style = MaterialTheme.typography.titleMedium)
        HeightStepper(Messages.profileObserverHeight(), observerHeight, onObserverHeight)
        HeightStepper(Messages.profileTargetHeight(), targetHeight, onTargetHeight)
        sight?.let { LineOfSightVerdict(it, distances) }
    } else {
        Text(Messages.profileLosNeedsTwoPoints(), style = MaterialTheme.typography.bodySmall, color = muted)
    }
    Text(Messages.profileSource(), style = MaterialTheme.typography.bodySmall, color = muted)
}

@Composable
private fun HeightStepper(label: String, value: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(
            onClick = { ProfileHeightSteps.lastOrNull { it < value }?.let(onChange) },
            enabled = value > ProfileHeightSteps.first(),
        ) { Icon(Icons.Default.Remove, contentDescription = Messages.profileHeightDown(label)) }
        Text(
            DisplayFormat.height(value),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { stateDescription = DisplayFormat.height(value) },
        )
        IconButton(
            onClick = { ProfileHeightSteps.firstOrNull { it > value }?.let(onChange) },
            enabled = value < ProfileHeightSteps.last(),
        ) { Icon(Icons.Default.Add, contentDescription = Messages.profileHeightUp(label)) }
    }
}

@Composable
private fun LineOfSightVerdict(sight: ElevationProfile.SightLine, distances: List<Double>) {
    val index = sight.worstIndex
    val margin = sight.worstMargin
    val text = when {
        index == null || margin == null -> Messages.profileLosClearShort()
        sight.blocked -> Messages.profileLosBlocked(DisplayFormat.height(margin), DisplayFormat.distance(distances[index]))
        else -> Messages.profileLosClear(DisplayFormat.height(-margin), DisplayFormat.distance(distances[index]))
    }
    val colour = if (sight.blocked) SightBlocked else Color(0xFF43A047)
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (sight.blocked) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = null,
            tint = colour,
        )
        Text(text, color = colour, fontWeight = FontWeight.SemiBold)
    }
}

/** Plot area inside the chart, shared by drawing and touch handling. */
private class ProfilePlot(width: Float, height: Float, density: Density) {
    val left = with(density) { 52.dp.toPx() }
    val top = with(density) { 6.dp.toPx() }
    val right = width - with(density) { 6.dp.toPx() }
    val bottom = height - with(density) { 22.dp.toPx() }
    val rect = Rect(left, top, maxOf(right, left + 1), maxOf(bottom, top + 1))
}

/** Maps distances and heights into the plot, with round-number height lines. */
private class ProfileScale(val distances: List<Double>, heights: List<Double>, val plot: Rect) {
    private val total = max(distances.lastOrNull() ?: 1.0, 1.0)
    val low: Double
    val high: Double
    val gridLevels: List<Double>

    init {
        val minimum = heights.minOrNull() ?: 0.0
        val maximum = heights.maxOrNull() ?: 1.0
        val step = niceStep(max(maximum - minimum, 10.0) / 3)
        low = floor(minimum / step) * step
        high = max(ceil(maximum / step) * step, low + step)
        gridLevels = generateSequence(low) { it + step }.takeWhile { it <= high + step / 2 }.toList()
    }

    fun x(distance: Double): Float = plot.left + (distance / total).toFloat() * plot.width
    fun y(height: Double): Float = plot.bottom - ((height - low) / (high - low)).toFloat() * plot.height

    fun indexAt(x: Float): Int? {
        if (distances.isEmpty()) return null
        val target = ((x - plot.left) / plot.width) * total
        return distances.indices.minByOrNull { abs(distances[it] - target) }
    }

    companion object {
        /** 1, 2 or 5 times a power of ten. */
        fun niceStep(raw: Double): Double {
            val magnitude = 10.0.pow(floor(log10(max(raw, 1.0))))
            return listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
        }
    }
}

/**
 * The profile chart: terrain filled below its line (grey where the observer
 * cannot see it), the sight line dashed over it with the critical point
 * marked, and a marker at the touched point. Drag across it to read heights.
 */
@Composable
private fun ProfileChart(
    distances: List<Double>,
    elevations: List<Double>,
    sight: ElevationProfile.SightLine?,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val lineColour = MaterialTheme.colorScheme.onSurface
    val gridColour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val heights = remember(elevations, sight) { elevations + (sight?.heights ?: emptyList()) }
    val summary = ElevationProfile.stats(elevations)?.let {
        listOf(
            "${Messages.profileLength()} ${DisplayFormat.distance(distances.last())}",
            "${Messages.profileLowest()} ${DisplayFormat.height(it.minimum)}",
            "${Messages.profileHighest()} ${DisplayFormat.height(it.maximum)}",
        ).joinToString(", ")
    }.orEmpty()
    val chartLabel = Messages.profileTitle()

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics {
                contentDescription = chartLabel
                stateDescription = summary
            }
            .pointerInput(distances) {
                val plot = ProfilePlot(size.width.toFloat(), size.height.toFloat(), this)
                val scale = ProfileScale(distances, heights, plot.rect)
                detectTapGestures { offset -> scale.indexAt(offset.x)?.let(onSelect) }
            }
            .pointerInput(distances) {
                val plot = ProfilePlot(size.width.toFloat(), size.height.toFloat(), this)
                val scale = ProfileScale(distances, heights, plot.rect)
                detectHorizontalDragGestures(
                    onDragStart = { offset -> scale.indexAt(offset.x)?.let(onSelect) },
                ) { change, _ -> scale.indexAt(change.position.x)?.let(onSelect) }
            }
    ) {
        val plot = ProfilePlot(size.width, size.height, this).rect
        val scale = ProfileScale(distances, heights, plot)

        for (level in scale.gridLevels) {
            val y = scale.y(level)
            drawLine(gridColour, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
            drawLabel(measurer, DisplayFormat.height(level), labelStyle, Offset(plot.left - 4.dp.toPx(), y), alignEnd = true)
        }
        drawLabel(measurer, DisplayFormat.distance(0.0), labelStyle, Offset(plot.left, plot.bottom + 4.dp.toPx()), centreY = false)
        drawLabel(
            measurer, DisplayFormat.distance(distances.last()), labelStyle,
            Offset(plot.right, plot.bottom + 4.dp.toPx()), alignEnd = true, centreY = false,
        )

        for (i in 0 until distances.size - 1) {
            val column = Path().apply {
                moveTo(scale.x(distances[i]), plot.bottom)
                lineTo(scale.x(distances[i]), scale.y(elevations[i]))
                lineTo(scale.x(distances[i + 1]), scale.y(elevations[i + 1]))
                lineTo(scale.x(distances[i + 1]), plot.bottom)
                close()
            }
            val hidden = sight?.let { !it.visible[i + 1] } ?: false
            drawPath(column, if (hidden) DeadGroundFill else TerrainFill)
        }
        val ridge = Path().apply {
            distances.indices.forEach { i ->
                val x = scale.x(distances[i])
                val y = scale.y(elevations[i])
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(ridge, lineColour, style = Stroke(width = 1.5.dp.toPx()))

        if (sight != null) {
            val colour = if (sight.blocked) SightBlocked else SightClear
            val line = Path().apply {
                distances.indices.forEach { i ->
                    val x = scale.x(distances[i])
                    val y = scale.y(sight.heights[i])
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                line, colour,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                ),
            )
            for (end in listOf(0, distances.lastIndex)) {
                drawCircle(colour, 4.dp.toPx(), Offset(scale.x(distances[end]), scale.y(sight.heights[end])))
            }
            sight.worstIndex?.let { i ->
                drawCircle(
                    colour, 6.dp.toPx(), Offset(scale.x(distances[i]), scale.y(elevations[i])),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        if (selected != null && selected in distances.indices) {
            val x = scale.x(distances[selected])
            drawLine(lineColour.copy(alpha = 0.6f), Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1.dp.toPx())
            drawCircle(lineColour, 5.dp.toPx(), Offset(x, scale.y(elevations[selected])))
        }
    }
}

private fun DrawScope.drawLabel(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    anchor: Offset,
    alignEnd: Boolean = false,
    centreY: Boolean = true,
) {
    val layout = measurer.measure(text, style)
    val x = if (alignEnd) anchor.x - layout.size.width else anchor.x
    val y = if (centreY) anchor.y - layout.size.height / 2f else anchor.y
    drawText(layout, topLeft = Offset(x, y))
}
