package com.tacmap.map.render

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
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
    onGestureStart: () -> Unit = {},
    /// Fires on a tap that reached the basemap (no overlay claimed it) - the app
    /// uses it to dismiss a selection, like the old onMapClick did.
    onTap: () -> Unit = {}
) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val cameraState = rememberUpdatedState(camera)
    val onChange = rememberUpdatedState(onCameraChange)
    val onStart = rememberUpdatedState(onGestureStart)
    val onTapState = rememberUpdatedState(onTap)

    // Decoded tiles, keyed by address. `version` bumps to force a redraw as
    // tiles arrive; `inFlight` dedupes concurrent loads of the same tile.
    val cache = remember { LruCache<TileIndex, ImageBitmap>(400) }
    val inFlight = remember { HashSet<TileIndex>() }
    var version by remember { mutableIntStateOf(0) }

    // Swapping the source clears the cache (stale style/pack tiles must go).
    LaunchedEffect(source) {
        cache.evictAll()
        inFlight.clear()
        version++
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
        val s = source ?: return@LaunchedEffect
        tiles.forEach { t ->
            if (cache.get(t) == null && inFlight.add(t)) {
                scope.launch {
                    val bmp = s.loadTile(t)
                    inFlight.remove(t)
                    if (bmp != null) {
                        cache.put(t, bmp.asImageBitmap())
                        version++
                    }
                }
            }
        }
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
    ) {
        version // read so newly-loaded tiles trigger a redraw
        drawRect(BACKGROUND, size = Size(size.width, size.height))
        val cam = cameraState.value
        // Tiles are laid out heading-flat, then the whole layer rotates by the
        // camera heading around the viewport centre (see TileMath.tileFrame).
        rotate(-cam.headingDegrees.toFloat(), pivot = Offset(size.width / 2, size.height / 2)) {
            tiles.forEach { t ->
                val img = cache.get(t) ?: return@forEach
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

private val BACKGROUND = Color(0xFF121212) // dark, so tile gaps aren't white
