package com.tacmap.map

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.Messages
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val TourAccent = Color(0xFFE99020)

/** A highlighted area of the screen, in the overlay's own pixels. */
private class TourSpot(val rect: Rect, val cornerRadius: Float) {
    companion object {
        fun circle(centre: Offset, radius: Float) = TourSpot(Rect(centre, radius), radius)
    }
}

/**
 * The guided tour drawn over the map. Everything is dimmed except the control
 * the current step is about, which gets a pulsing ring, and a card with an
 * arrow explains it. It draws in the activity window, so night mode turns it
 * red with the map, and it blocks the map while it is open. iOS mirrors this
 * in `MapTourOverlay.swift`.
 */
@Composable
internal fun MapTourOverlay(targets: TourTargets, onFinish: () -> Unit) {
    val steps = FirstRunTips.steps
    var index by rememberSaveable { mutableIntStateOf(0) }
    val current = index.coerceIn(0, steps.lastIndex)
    val step = steps[current]
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    BackHandler { if (current > 0) index = current - 1 else onFinish() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val spot = with(density) {
            when (val target = step.target) {
                null -> null
                // The crosshair is centred on the map, which fills this box.
                TourTarget.CROSSHAIR -> TourSpot.circle(Offset(width / 2, height / 2), 34.dp.toPx())
                // Any empty spot works; this one is clear of the crosshair
                // and of the bars at the bottom.
                TourTarget.MAP_HOLD -> TourSpot.circle(Offset(width / 2, height * 0.66f), 48.dp.toPx())
                else -> targets.bounds[target]?.let { bounds ->
                    val rect = bounds.translate(-origin).inflate(6.dp.toPx())
                    val round = abs(rect.width - rect.height) < 12.dp.toPx()
                    TourSpot(rect, if (round) min(rect.width, rect.height) / 2 else 18.dp.toPx())
                }
            }
        }

        val motion = spring<Rect>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
        val hole by animateRectAsState(
            spot?.rect ?: Rect(Offset(width / 2, height / 2), Size.Zero), motion,
        )
        val radius by animateFloatAsState(
            spot?.cornerRadius ?: 0f, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
        )
        val pulse by rememberInfiniteTransition().animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing)),
        )

        Canvas(Modifier.fillMaxSize()) {
            val corner = radius.coerceIn(0f, min(hole.width, hole.height) / 2)
            val scrim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(hole, CornerRadius(corner)))
            }
            drawPath(scrim, Color.Black.copy(alpha = 0.74f))
            if (spot != null) {
                drawRoundRect(
                    TourAccent, hole.topLeft, hole.size, CornerRadius(corner),
                    style = Stroke(2.5.dp.toPx()),
                )
                val grow = 1f + 0.3f * pulse
                val pulsed = Rect(
                    hole.center.x - hole.width * grow / 2, hole.center.y - hole.height * grow / 2,
                    hole.center.x + hole.width * grow / 2, hole.center.y + hole.height * grow / 2,
                )
                drawRoundRect(
                    TourAccent.copy(alpha = 1f - pulse), pulsed.topLeft, pulsed.size, CornerRadius(corner * grow),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }

        if (step.target == TourTarget.MAP_HOLD && spot != null) {
            val iconSize = with(density) { 36.dp.toPx() }
            Icon(
                Icons.Default.TouchApp,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (hole.center.x - iconSize / 2).roundToInt(),
                            (hole.center.y - iconSize / 2 + iconSize / 3).roundToInt(),
                        )
                    }
                    .size(36.dp),
            )
        }

        val cardWidthPx = with(density) { min(width - 32.dp.toPx(), 380.dp.toPx()) }
        val cardWidth = with(density) { cardWidthPx.toDp() }
        val card: @Composable () -> Unit = {
            TourCard(
                step = step,
                index = current,
                count = steps.size,
                onBack = { index = current - 1 },
                onNext = { if (current == steps.lastIndex) onFinish() else index = current + 1 },
                onSkip = onFinish,
            )
        }

        if (spot == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(cardWidth)) { card() }
            }
        } else {
            val rect = spot.rect
            val topInset = WindowInsets.safeDrawing.getTop(density).toFloat()
            val bottomInset = WindowInsets.safeDrawing.getBottom(density).toFloat()
            val margin = with(density) { 16.dp.toPx() }
            val cardX = (rect.center.x - cardWidthPx / 2).coerceIn(margin, maxOf(margin, width - cardWidthPx - margin))
            val arrowInset = with(density) { 26.dp.toPx() }
            val arrowX = (rect.center.x - cardX).coerceIn(arrowInset, maxOf(arrowInset, cardWidthPx - arrowInset))
            // The card is measured before it is placed, so it goes wherever it
            // fits in full: below the control, above it, or on screen over it.
            Layout(
                content = {
                    TourArrow(pointingUp = true)
                    TourArrow(pointingUp = false)
                    Box { card() }
                },
                modifier = Modifier.fillMaxSize(),
            ) { measurables, constraints ->
                val cardWidthInt = cardWidthPx.roundToInt()
                val available = (height - topInset - bottomInset).roundToInt().coerceAtLeast(0)
                val cardPlaceable = measurables[2].measure(
                    Constraints(minWidth = cardWidthInt, maxWidth = cardWidthInt, maxHeight = available)
                )
                val arrowWidth = 22.dp.roundToPx()
                val arrowHeight = 11.dp.roundToPx()
                val up = measurables[0].measure(Constraints.fixed(arrowWidth, arrowHeight))
                val down = measurables[1].measure(Constraints.fixed(arrowWidth, arrowHeight))
                val placement = calloutPlacement(
                    spotTop = rect.top,
                    spotBottom = rect.bottom,
                    cardHeight = cardPlaceable.height.toFloat(),
                    screenHeight = height,
                    topInset = topInset,
                    bottomInset = bottomInset,
                    gap = 8.dp.toPx(),
                    arrowHeight = arrowHeight.toFloat(),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    cardPlaceable.place(cardX.roundToInt(), placement.cardY.roundToInt())
                    val arrowLeft = (cardX + arrowX - arrowWidth / 2f).roundToInt()
                    when (placement.arrow) {
                        CalloutArrow.UP -> up.place(arrowLeft, placement.arrowY.roundToInt())
                        CalloutArrow.DOWN -> down.place(arrowLeft, placement.arrowY.roundToInt())
                        CalloutArrow.NONE -> Unit
                    }
                }
            }
        }
    }
}

internal enum class CalloutArrow { UP, DOWN, NONE }

internal data class CalloutPlacement(val cardY: Float, val arrowY: Float, val arrow: CalloutArrow)

/**
 * Where the tour card goes for a highlighted area between [spotTop] and
 * [spotBottom]: below it if the whole card fits there, otherwise above it,
 * preferring the roomier side when both fit. When neither side has room
 * (a small screen or large text) the card stays fully on screen on the
 * roomier side, without an arrow. iOS uses the same rule.
 */
internal fun calloutPlacement(
    spotTop: Float,
    spotBottom: Float,
    cardHeight: Float,
    screenHeight: Float,
    topInset: Float,
    bottomInset: Float,
    gap: Float,
    arrowHeight: Float,
): CalloutPlacement {
    val belowY = spotBottom + gap + arrowHeight
    val aboveY = spotTop - gap - arrowHeight - cardHeight
    val fitsBelow = belowY + cardHeight <= screenHeight - bottomInset
    val fitsAbove = aboveY >= topInset
    val preferBelow = screenHeight - bottomInset - spotBottom >= spotTop - topInset
    return when {
        fitsBelow && (preferBelow || !fitsAbove) -> CalloutPlacement(belowY, spotBottom + gap, CalloutArrow.UP)
        fitsAbove -> CalloutPlacement(aboveY, spotTop - gap - arrowHeight, CalloutArrow.DOWN)
        preferBelow -> CalloutPlacement(
            maxOf(topInset, screenHeight - bottomInset - cardHeight), 0f, CalloutArrow.NONE,
        )
        else -> CalloutPlacement(topInset, 0f, CalloutArrow.NONE)
    }
}

@Composable
private fun TourCard(
    step: FirstRunTips.Step,
    index: Int,
    count: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val isLast = index >= count - 1
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, TourAccent.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val progress = Messages.tourProgress(
                    DisplayFormat.number((index + 1).toDouble(), 0),
                    DisplayFormat.number(count.toDouble(), 0),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = progress },
                ) {
                    repeat(count) { dot ->
                        Box(
                            Modifier
                                .size(width = if (dot == index) 16.dp else 6.dp, height = 6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (dot == index) TourAccent
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                ),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!isLast) {
                    TextButton(onClick = onSkip) { Text(Messages.tipsSkip()) }
                }
            }
            Text(
                step.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Text(step.body, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    TextButton(onClick = onBack) { Text(Messages.tourBack()) }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(containerColor = TourAccent, contentColor = Color.Black),
                ) {
                    Text(if (isLast) Messages.tipsDone() else Messages.tipsNext(), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The small triangle joining the card to the highlighted control. */
@Composable
private fun TourArrow(pointingUp: Boolean) {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(Modifier.fillMaxSize()) {
        val tip = if (pointingUp) 0f else size.height
        val base = if (pointingUp) size.height else 0f
        val arrow = Path().apply {
            moveTo(size.width / 2, tip)
            lineTo(size.width, base)
            lineTo(0f, base)
            close()
        }
        drawPath(arrow, color)
    }
}
