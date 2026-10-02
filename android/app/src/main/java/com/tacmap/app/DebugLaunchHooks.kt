package com.tacmap.app

/**
 * Launch intent extras the on-device verification scripts use, e.g.
 *
 *   adb shell am start -n com.tacmap/.app.MainActivity \
 *     --es TACMAP_DEBUG_IMPORT_PDF /sdcard/Android/data/com.tacmap/files/sheet.pdf \
 *     --es TACMAP_DEBUG_CAMERA 37.765,-122.443,16,0 --es TACMAP_DEBUG_GRID 1
 *
 * DEBUG BUILDS ONLY. MainActivity only reads them when BuildConfig.DEBUG, and that's a
 * compile time constant so R8 strips every call site out of release. See docs/DEBUG_HOOKS.md.
 * Parsing is pure so it's unit tested on the JVM.
 */
internal object DebugLaunchHooks {
    const val EXTRA_IMPORT_PDF = "TACMAP_DEBUG_IMPORT_PDF"
    const val EXTRA_CAMERA = "TACMAP_DEBUG_CAMERA"
    const val EXTRA_GRID = "TACMAP_DEBUG_GRID"
    const val EXTRA_DEVICE_AUDIT = "TACMAP_DEBUG_DEVICE_AUDIT"
    const val EXTRA_ALLOW_SCREENSHOTS = "TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS"

    data class Camera(val latitude: Double, val longitude: Double, val zoom: Double, val heading: Double?)

    data class Request(
        val importPdfPath: String?,
        val camera: Camera?,
        val grid: Boolean,
        val allowScreenshots: Boolean,
        val deviceAudit: Boolean = false,
    ) {
        val isEmpty: Boolean get() = importPdfPath == null && camera == null && !grid && !allowScreenshots && !deviceAudit
    }

    /** [extra] reads a string extra by name. null when there's nothing debug in there */
    fun parse(extra: (String) -> String?): Request? {
        val path = extra(EXTRA_IMPORT_PDF)?.trim()?.takeIf { it.startsWith("/") && it.endsWith(".pdf", ignoreCase = true) }
        val r = Request(
            importPdfPath = path,
            camera = extra(EXTRA_CAMERA)?.let(::parseCamera),
            grid = isOn(extra(EXTRA_GRID)),
            allowScreenshots = isOn(extra(EXTRA_ALLOW_SCREENSHOTS)),
            deviceAudit = isOn(extra(EXTRA_DEVICE_AUDIT)),
        )
        return r.takeUnless { it.isEmpty }
    }

    /** "lat,lon,zoom[,heading]" */
    fun parseCamera(s: String): Camera? {
        val parts = s.split(',').map { it.trim().toDoubleOrNull() }
        if (parts.size !in 3..4 || parts.any { it == null || !it.isFinite() }) return null
        val lat = parts[0]!!
        val lon = parts[1]!!
        val zoom = parts[2]!!
        if (lat !in -85.0..85.0 || lon !in -180.0..180.0 || zoom !in 0.0..24.0) return null
        return Camera(lat, lon, zoom, parts.getOrNull(3))
    }

    private fun isOn(v: String?): Boolean = v?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

    /** handed from MainActivity to MapScreen, taken once */
    @Volatile private var pendingCamera: Camera? = null

    /** lifts FLAG_SECURE for this process only, never written to the OPSEC prefs */
    @Volatile var allowScreenshots: Boolean = false
        private set

    @Volatile var deviceAudit: Boolean = false
        private set

    fun apply(request: Request) {
        request.camera?.let { pendingCamera = it }
        if (request.allowScreenshots) allowScreenshots = true
        if (request.deviceAudit) deviceAudit = true
    }

    fun takeCamera(): Camera? {
        val c = pendingCamera
        pendingCamera = null
        return c
    }
}
