package com.tacmap.map.render

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Compose slippy-map tile layer: draws raster tiles for a [MapCamera] on a
 * Canvas and drives that camera from pan/pinch/rotate gestures. No Google Maps
 * SDK. This is the piece that lets Android drop the SDK; overlays sit on top of
 * it (siblings in a Box) and read the same [MapCamera] projection. Mirrors the
 * iOS TileMapView.
 *
 * Units: the camera works in density-independent points (dp), matching Google's
 * zoom convention (world = 256*2^zoom dp). Only here, at the Canvas boundary, do
 * we scale by [density] to device pixels.
 *
 * State is hoisted: [camera] in, [onCameraChange] out. Gestures compute a new
 * camera and call back; the parent holds it. [onGestureStart] flips the app into
 * browse mode on the first drag.
 */
@Composable
fun TileMapView(
    camera: MapCamera,
    onCameraChange: (MapCamera) -> Unit,
    source: TileSource?,
    modifier: Modifier = Modifier,
    /** False when an ancestor/sibling owns the complete map interaction stream. */
    gesturesEnabled: Boolean = true,
    onGestureStart: () -> Unit = {},
    /// Fires on a tap that reached the basemap (no overlay claimed it) - the app
    /// uses it to dismiss a selection, like the old onMapClick did.
    onTap: () -> Unit = {}
) {
    val density = LocalDensity.current.density
    val cameraState = rememberUpdatedState(camera)
    val onChange = rememberUpdatedState(onCameraChange)
    val onStart = rememberUpdatedState(onGestureStart)
    val onTapState = rememberUpdatedState(onTap)
    val loadScope = rememberCoroutineScope()

    // Decoded tiles, keyed by address. `version` bumps to force a redraw as
    // tiles arrive. The load coordinator owns source/generation-scoped jobs.
    val cache = remember {
        object : LruCache<TileIndex, CachedTile>(48 * 1024) {
            override fun sizeOf(key: TileIndex, value: CachedTile): Int =
                ((value.bitmap.allocationByteCount.toLong() + 1023L) / 1024L)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

            override fun entryRemoved(
                evicted: Boolean,
                key: TileIndex,
                oldValue: CachedTile,
                newValue: CachedTile?,
            ) {
                if (oldValue !== newValue && !oldValue.bitmap.isRecycled) oldValue.bitmap.recycle()
            }
        }
    }
    var version by remember { mutableIntStateOf(0) }
    val loadCoordinator = remember(loadScope, cache) {
        ScopedTileLoadCoordinator<TileSource, TileIndex, Bitmap>(
            scope = loadScope,
            load = { tileSource, tile ->
                loadTileOrNullPreservingCancellation { tileSource.loadTile(tile) }
            },
            publish = { _, tile, bitmap ->
                cache.put(tile, CachedTile(bitmap))
                version++
            },
            discard = { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            },
        )
    }
    DisposableEffect(loadCoordinator) {
        onDispose {
            loadCoordinator.dispose()
            cache.evictAll()
        }
    }

    // Which tiles the current camera shows, and their integer zoom.
    val tiles = remember(camera, source) {
        val s = source ?: return@remember emptyList<TileIndex>()
        if (camera.viewportWidth <= 0.0 || camera.viewportHeight <= 0.0) return@remember emptyList()
        val tz = TileMath.tileZoom(camera.zoom, s.minZoom, s.maxZoom)
        TileMath.visibleTiles(camera, tz)
    }

    // Fetch any visible tile we don't already have.
    LaunchedEffect(tiles, source) {
        loadCoordinator.reconcile(
            source = source,
            wanted = tiles.toSet(),
            isLoaded = { cache.get(it) != null },
            onSourceChanged = {
                cache.evictAll()
                version++
            },
        )
    }

    val inputModifier = if (gesturesEnabled) {
        Modifier
            .pointerInput(source) {
                detectTransformGestures(panZoomLock = false) { centroidPx, panPx, zoom, rotation ->
                    onStart.value()
                    var next = cameraState.value
                    val cx = next.viewportWidth / 2
                    val cy = next.viewportHeight / 2
                    // Everything the gesture reports is device px; the camera is dp.
                    val panX = panPx.x / density.toDouble()
                    val panY = panPx.y / density.toDouble()
                    val focalX = centroidPx.x / density.toDouble()
                    val focalY = centroidPx.y / density.toDouble()

                    // 1) Pan: the coord now under (centre - delta) becomes the centre.
                    val (plat, plon) = next.coordinate(cx - panX, cy - panY)
                    next = next.copy(centerLat = plat, centerLon = plon)

                    // 2) Focal zoom: keep the coord under the fingers fixed. A PDF/
                    // blank map has no tile source, but the overlay still draws at
                    // any zoom, so fall back to a sane global range rather than
                    // refusing to zoom. Guarding on source != null here is what
                    // broke pinch over an imported PDF.
                    if (zoom != 1f) {
                        val minZ = source?.minZoom?.toDouble() ?: 2.0
                        val maxZ = source?.maxZoom?.toDouble() ?: 22.0
                        val (alat, alon) = next.coordinate(focalX, focalY)
                        val nz = (next.zoom + log2(zoom.toDouble())).coerceIn(minZ, maxZ)
                        next = next.copy(zoom = nz)
                        val landed = next.screenPoint(alat, alon)
                        val (clat, clon) = next.coordinate(cx + (landed.x - focalX), cy + (landed.y - focalY))
                        next = next.copy(centerLat = clat, centerLon = clon)
                    }

                    // 3) Rotate: gesture rotation is CCW-positive; map heading is CW.
                    if (rotation != 0f) {
                        var h = (next.headingDegrees - rotation) % 360
                        if (h < 0) h += 360
                        next = next.copy(headingDegrees = h)
                    }

                    onChange.value(next)
                }
            }
            .pointerInput(Unit) {
                // Taps that reach the basemap (no overlay claimed them) dismiss
                // the current selection, replacing the old GoogleMap onMapClick.
                detectTapGestures { onTapState.value() }
            }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { sz ->
                val wDp = sz.width / density.toDouble()
                val hDp = sz.height / density.toDouble()
                val cam = cameraState.value
                if (cam.viewportWidth != wDp || cam.viewportHeight != hDp) {
                    onChange.value(cam.copy(viewportWidth = wDp, viewportHeight = hDp))
                }
            }
            .then(inputModifier)
    ) {
        @Suppress("UNUSED_EXPRESSION") // snapshot read invalidates the draw phase when a tile arrives
        version // read so newly-loaded tiles trigger a redraw
        drawRect(BACKGROUND, size = Size(size.width, size.height))
        val cam = cameraState.value
        // Tiles are laid out heading-flat, then the whole layer rotates by the
        // camera heading around the viewport centre (see TileMath.tileFrame).
        rotate(-cam.headingDegrees.toFloat(), pivot = Offset(size.width / 2, size.height / 2)) {
            tiles.forEach { t ->
                val cached = cache.get(t) ?: return@forEach
                val img = cached.image
                val f = TileMath.tileFrame(t, cam)
                // dp -> px, and grow 0.5dp to hide hairline seams between tiles.
                val x = ((f.x - 0.5) * density).roundToInt()
                val y = ((f.y - 0.5) * density).roundToInt()
                val edge = ((f.edge + 1.0) * density).roundToInt()
                drawImage(
                    image = img,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(x, y),
                    dstSize = IntSize(edge, edge)
                )
            }
        }
    }
}

private class CachedTile(val bitmap: Bitmap) {
    val image = bitmap.asImageBitmap()
}

private val BACKGROUND = Color(0xFF121212) // dark, so tile gaps aren't white
