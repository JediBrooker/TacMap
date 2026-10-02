package com.tacmap.map.render.pdf

import com.tacmap.BuildConfig
import com.tacmap.app.DebugLaunchHooks
import com.tacmap.map.render.MapCamera
import com.tacmap.map.render.TileDrawItem
import kotlinx.serialization.json.*

// Opt-in debug observations only. The renderer never reads this buffer.
internal object PdfDeviceAudit {
    private val buffer = PdfDeviceAuditBuffer()
    private var frame: JsonObject? = null
    private var frameErrors = 0

    fun completed(sourceId: String?, plan: PdfWarpPlan, detailZoom: Int, baseMaxZoom: Int, path: String, renderSourceId: String? = null) {
        if (!BuildConfig.DEBUG || !DebugLaunchHooks.deviceAudit || sourceId == null || renderSourceId == null) return
        runCatching { buffer.completed(sourceId, plan, detailZoom, baseMaxZoom, path, renderSourceId) }.onFailure { buffer.failedObservation() }
    }

    @Synchronized fun frame(sourceId: String?, camera: MapCamera, tileZoom: Int, density: Float, draws: List<JsonObject>, omittedDraws: Int, renderSourceId: String?) {
        if (!BuildConfig.DEBUG || !DebugLaunchHooks.deviceAudit || sourceId == null || renderSourceId == null) return
        runCatching { frame = buildJsonObject {
            put("sourceId", sourceId); put("renderSourceId", renderSourceId); put("tileZoom", tileZoom); put("density", density)
            put("latitude", camera.centerLat); put("longitude", camera.centerLon)
            put("zoom", camera.zoom); put("heading", camera.headingDegrees)
            put("viewportWidth", camera.viewportWidth); put("viewportHeight", camera.viewportHeight)
            put("draws", JsonArray(draws.take(MAX_FRAME_DRAWS)))
            put("omittedDraws", omittedDraws + (draws.size - MAX_FRAME_DRAWS).coerceAtLeast(0))
        } }.onFailure { frame = null; frameErrors++ }
    }

    fun draw(item: TileDrawItem, width: Int, height: Int, l: Float, t: Float, r: Float, b: Float) = buildJsonObject {
        put("source", buildJsonArray { add(item.source.z); add(item.source.x); add(item.source.y) })
        put("kind", item.kind.toString()); put("bitmapWidth", width); put("bitmapHeight", height)
        put("unitRect", buildJsonArray { add(item.unitRect.x); add(item.unitRect.y); add(item.unitRect.w); add(item.unitRect.h) })
        put("physicalRect", buildJsonArray { add(l); add(t); add(r); add(b) })
    }

    @Synchronized fun snapshot(): JsonObject = buildJsonObject {
        put("enabled", BuildConfig.DEBUG && DebugLaunchHooks.deviceAudit)
        put("completed", buffer.snapshot())
        put("frame", frame ?: JsonNull)
        put("frameObservationErrors", frameErrors)
    }

    const val MAX_FRAME_DRAWS = 64
}

internal class PdfDeviceAuditBuffer(private val maxRecords: Int = 64, private val maxCells: Int = 8192) {
    private data class Record(val cells: Int, val value: JsonObject)
    private val records = ArrayDeque<Record>()
    private var cells = 0
    private var evicted = 0
    private var omitted = 0
    private var failed = 0

    @Synchronized fun failedObservation() { failed++ }

    @Synchronized fun completed(sourceId: String, plan: PdfWarpPlan, detailZoom: Int, baseMaxZoom: Int, path: String, renderSourceId: String? = null) {
        if (plan.leaves.size > maxCells || maxRecords < 1) { omitted++; return }
        while (records.isNotEmpty() && (records.size >= maxRecords || cells + plan.leaves.size > maxCells)) {
            cells -= records.removeFirst().cells
            evicted++
        }
        val value = buildJsonObject {
            put("sourceId", sourceId); put("renderSourceId", renderSourceId); put("path", path); put("detailZoom", detailZoom); put("baseMaxZoom", baseMaxZoom)
            put("job", buildJsonObject {
                put("z", plan.job.z); put("x", plan.job.x0); put("y", plan.job.y0)
                put("cols", plan.job.cols); put("rows", plan.job.rows)
            })
            put("tilePx", plan.tilePx); put("dropped", plan.dropped); put("maxDepth", plan.maxDepth)
            put("root", plan.root?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
            put("cells", JsonArray(plan.leaves.map { c -> buildJsonObject {
                put("rect", buildJsonArray { add(c.left); add(c.top); add(c.right); add(c.bottom) })
                put("pageToPx", buildJsonArray {
                    add(c.pageToPx.a); add(c.pageToPx.b); add(c.pageToPx.c)
                    add(c.pageToPx.d); add(c.pageToPx.e); add(c.pageToPx.f)
                })
                put("errorPx", c.errorPx); put("coverage", c.coverage.toString()); put("depth", c.depth)
                put("quad", JsonArray(c.quad.map { p -> buildJsonArray { add(p.x); add(p.y) } }))
            } }))
        }
        records.addLast(Record(plan.leaves.size, value))
        cells += plan.leaves.size
    }

    @Synchronized fun snapshot(): JsonObject = buildJsonObject {
        put("records", JsonArray(records.map { it.value })); put("retainedCells", cells)
        put("evictedRecords", evicted); put("omittedRecords", omitted); put("failedRecords", failed)
        put("maxRecords", maxRecords); put("maxCells", maxCells)
    }
}
