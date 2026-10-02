package com.tacmap.map.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.log2

/** dark, so a missing tile reads as "nothing yet" rather than white paper. also the hidden imported map look */
const val TILE_VIEW_BACKGROUND_ARGB = 0xFF121212

/**
 * Compose slippy-map tile layer: draws raster tiles for a [MapCamera] on a
 * Canvas and drives that camera from pan/pinch/rotate gestures. No Google Maps
 * SDK. Overlays sit on top of it (siblings in a Box) and read the same
 * [MapCamera] projection. Mirrors the iOS TileMapView.
 *
 * Units: the camera works in density-independent points (dp), matching Google's
 * zoom convention (world = 256*2^zoom dp). Only here, at the Canvas boundary, do
 * we scale by density to device pixels.
 *
 * Fallback (WP2 contract B): a missing tile shows its nearest loaded ancestor
 * (or loaded children) instead of a hole, for every source. Tiles are drawn at
 * exact float frames from one grid origin with AA off, so shared edges are
 * identical and there's no seam to hide, no inflate, no rounding (D4-16).
 *
 * The [cache] lives in MapViewModel so a rotation keeps what's been rendered.
 */
@Composable
fun TileMapView(
    camera: MapCamera,
    onCameraChange: (MapCamera) -> Unit,
    source: TileSource?,
    cache: TileBitmapCache,
    modifier: Modifier = Modifier,
    /** imported map switched off: background only, cache kept */
    hidden: Boolean = false,
    /** what TalkBack hears for the map surface, e.g. which PDF is drawn */
    contentDescription: String? = null,
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

    // bumps as tiles land so the plan + canvas pick them up
    var version by remember { mutableLongStateOf(0L) }
    val loadCoordinator = remember(loadScope, cache) {
        ScopedTileLoadCoordinator<TileSource, TileIndex, TileCacheEntry>(
            scope = loadScope,
            load = { tileSource, tile ->
                loadTileOrNullPreservingCancellation { tileSource.loadTile(tile) }?.let { bmp ->
                    if (bmp === TileSource.EMPTY) TileCacheEntry.Empty else TileCacheEntry.Image(bmp)
                }
            },
            publish = { tileSource, tile, entry ->
                // a late answer for a source we already left must not land in the new one's cache
                if (cache.key == tileSource.cacheKey) {
                    cache.put(tile, entry)
                    version++
                }
            },
            // no recycling: the entry may be the shared EMPTY sentinel or still referenced by a frame
            discard = { },
        )
    }
    DisposableEffect(loadCoordinator) {
        onDispose { loadCoordinator.dispose() }
    }

    // the source can ask for a fresh plan on its own (PDF base raster landed)
    val noTicks = remember { kotlinx.coroutines.flow.MutableStateFlow(0) }
    val replanTick by (source?.replanTicks ?: noTicks).collectAsState()

    // which tiles the camera wants and what to draw for them meanwhile
    val frame = remember(camera, source, version, hidden, replanTick) {
        planFrame(camera, source, cache, hidden)
    }
    // the viewport centre too, so a pan with the same tiles still reaches setViewport (E3)
    val centre = remember(frame.camera) { TileMath.viewportCentreUnit(frame.camera) }

    // tileZoom too: a zoom change with everything already cached still has to reach the scheduler
    LaunchedEffect(frame.requests, frame.tileZoom, source, centre) {
        val s = source
        // hidden or underzoomed frames plan no grid. the source isn't told anything then:
        // no viewport, no settle restart, no base raster kick (E3). cancelling still happens below
        if (s != null && frame.grid != null) {
            s.onWanted(frame.requests, frame.tileZoom, centre.first, centre.second)
        }
        loadCoordinator.reconcile(
            source = s,
            wanted = LinkedHashSet(frame.requests),
            isLoaded = { cache.state(it) != TileCacheState.MISSING },
            onSourceChanged = { version++ },
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

                    // 2) Focal zoom: keep the coord under the fingers fixed. Only the camera's
                    // own limits apply, sources overzoom past their max (D3-08)
                    if (zoom != 1f) {
                        val (alat, alon) = next.coordinate(focalX, focalY)
                        val nz = MapCamera.clampZoom(next.zoom + log2(zoom.toDouble()))
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

    val paints = remember { TilePaints() }

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
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else Modifier
            )
    ) {
        drawRect(BACKGROUND, size = Size(size.width, size.height))
        if (hidden || frame.items.isEmpty()) return@Canvas
        val grid = frame.grid ?: return@Canvas
        val cam = frame.camera
        cache.markInUse(frame.inUse)
        val auditSource = if (com.tacmap.BuildConfig.DEBUG && com.tacmap.app.DebugLaunchHooks.deviceAudit) source as? com.tacmap.map.render.pdf.PdfTileSource else null
        val auditDraws = auditSource?.let { ArrayList<kotlinx.serialization.json.JsonObject>() }
        var omittedAuditDraws = 0
        // Tiles are laid out heading-flat, then the whole layer rotates by the
        // camera heading around the viewport centre.
        rotate(-cam.headingDegrees.toFloat(), pivot = Offset(size.width / 2, size.height / 2)) {
            val nc = drawContext.canvas.nativeCanvas
            for (item in frame.items) {
                val entry = cache.entry(item.source) as? TileCacheEntry.Image ?: continue
                val bmp = entry.bitmap
                if (bmp.isRecycled) continue
                val f = grid.frame(item.dest)
                val w = f[2] - f[0]
                val h = f[3] - f[1]
                val d = item.destRect
                // the dest sub rect is built from the same expressions on both sides of a shared edge
                val l = ((f[0] + d.x * w) * density).toFloat()
                val t = ((f[1] + d.y * h) * density).toFloat()
                val r = ((f[0] + (d.x + d.w) * w) * density).toFloat()
                val b = ((f[1] + (d.y + d.h) * h) * density).toFloat()
                paints.dst.set(l, t, r, b)
                if (auditDraws != null) {
                    if (auditDraws.size < com.tacmap.map.render.pdf.PdfDeviceAudit.MAX_FRAME_DRAWS) {
                        runCatching { auditDraws.add(com.tacmap.map.render.pdf.PdfDeviceAudit.draw(item, bmp.width, bmp.height, l, t, r, b)) }
                            .onFailure { omittedAuditDraws++ }
                    } else omittedAuditDraws++
                }
                if (item.unitRect == UnitRect.FULL) {
                    nc.drawBitmap(bmp, null, paints.dst, paints.tile)
                } else {
                    // ancestor sub rect: src coords aren't whole pixels (1/64 of a 672 tile is 10.5 px),
                    // so map it with a float matrix through a shader instead of an int src Rect
                    val u = item.unitRect
                    val sx = (r - l) / (u.w * bmp.width)
                    val sy = (b - t) / (u.h * bmp.height)
                    paints.matrix.setScale(sx.toFloat(), sy.toFloat())
                    paints.matrix.postTranslate((l - u.x * bmp.width * sx).toFloat(), (t - u.y * bmp.height * sy).toFloat())
                    val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    shader.setLocalMatrix(paints.matrix)
                    paints.shaded.shader = shader
                    nc.drawRect(paints.dst, paints.shaded)
                    paints.shaded.shader = null
                }
            }
        }
        if (auditSource != null && auditDraws != null) {
            com.tacmap.map.render.pdf.PdfDeviceAudit.frame(auditSource.auditSourceId, cam, frame.tileZoom, density, auditDraws, omittedAuditDraws, auditSource.auditInstanceId)
        }
    }
}

/** one frame's worth of planning, recomputed when the camera moves or a tile lands */
private class TileFrame(
    val camera: MapCamera,
    val tileZoom: Int,
    val grid: TileGrid?,
    val items: List<TileDrawItem>,
    val requests: List<TileIndex>,
    val inUse: Set<TileIndex>,
)

private fun planFrame(camera: MapCamera, source: TileSource?, cache: TileBitmapCache, hidden: Boolean): TileFrame {
    cache.bind(source?.cacheKey)
    // hidden keeps the cache warm but asks for nothing new
    if (hidden || source == null || camera.viewportWidth <= 0.0 || camera.viewportHeight <= 0.0) {
        return TileFrame(camera, 0, null, emptyList(), emptyList(), emptySet())
    }
    val tz = TileMath.tileZoom(camera.zoom, source.minZoom, source.maxZoom)
    if (TileMath.underzoomHidden(tz, camera.zoom)) {
        return TileFrame(camera, tz, null, emptyList(), emptyList(), emptySet())
    }
    val visible = TileMath.visibleTiles(camera, tz)
    val plan = TileDrawPlanner.plan(
        visible,
        { t -> if (t.z == tz && !source.hasContent(t)) TileCacheState.EMPTY else cache.state(t) },
        source.fallbackZoom(tz),
    )
    val inUse = HashSet<TileIndex>(plan.items.size * 2)
    plan.items.forEach { inUse += it.source }
    visible.forEach { inUse += it.index }
    return TileFrame(camera, tz, TileGrid(camera, tz), plan.items, plan.requests, inUse)
}

private class TilePaints {
    /** bilinear, no AA: AA would blend the shared edge with the background and leave a seam */
    val tile = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = false }
    val shaded = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = false }
    val dst = RectF()
    val matrix = Matrix()
}

private val BACKGROUND = Color(TILE_VIEW_BACKGROUND_ARGB)
