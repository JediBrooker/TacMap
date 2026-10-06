package com.tacmap.app

import android.app.Application
import com.tacmap.localization.L10n
import com.tacmap.models.TrackRecorder
import com.tacmap.settings.OpsecSettings
import com.tacmap.sync.UnitSyncRuntime
import com.tacmap.util.DataKey
import com.tacmap.map.cleanupExportArtifacts

/** App entry point. Installs crash capture as early as possible so
 *  field crashes don't go silent. */
class TacticalApp : Application() {

    /** One process-wide lock state shared by the lifecycle gate and settings UI. */
    lateinit var appLock: AppLock
        private set

    /** App-scoped OPSEC settings, shared by Activity + UI + networking layer. */
    lateinit var opsec: OpsecSettings
        private set

    /** Track recorder lives here at app scope so the foreground service
     *  can keep feeding fixes even after the Activity gets destroyed. */
    lateinit var trackRecorder: TrackRecorder
        private set

    /** Owns the one v3 Unit Sync client across a screen-off key lock. */
    lateinit var unitSyncRuntime: UnitSyncRuntime
        private set

    /** PDF render crash loop breaker (WP2 contract I), plaintext uuids only, no backup */
    lateinit var pdfRenderGuard: com.tacmap.map.render.pdf.PdfRenderGuard
        private set

    /** MBTiles open crash loop breaker (s14.1), entry ids only, no backup. tests swap in a fresh one as a relaunch */
    lateinit var mbtilesOpenGuard: com.tacmap.map.render.MbtilesOpenGuard
        @androidx.annotation.VisibleForTesting internal set

    /** Generate Offline Tiles lives here so it outlives the Layers sheet and the Activity */
    lateinit var pdfBakeManager: com.tacmap.calibration.PdfBakeManager
        private set

    /** forwarded to whoever holds PDF memory (the map's runtime registers itself) */
    val memoryPressure = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    override fun onCreate() {
        super.onCreate()
        L10n.install(this)
        // Has to come before any store is constructed - they all seal through it.
        DataKey.install(this)
        com.tacmap.waypoints.CustomSymbolStore.initialize(this)
        appLock = AppLock(this)
        cleanupExportArtifacts(this)
        // S5: PDFBox spills plaintext stream bytes into cacheDir/pdfbox (PdfInspector's scratch).
        // nothing can be parsing yet, so whatever's there is from a parse that died mid way
        runCatching { java.io.File(cacheDir, "pdfbox").deleteRecursively() }
        opsec = OpsecSettings(this)
        unitSyncRuntime = UnitSyncRuntime(this, opsec)
        trackRecorder = TrackRecorder(this)
        com.tacmap.map.render.pdf.PdfRenderSessions.init(this)
        pdfRenderGuard = com.tacmap.map.render.pdf.PdfRenderGuard(
            java.io.File(noBackupFilesDir, com.tacmap.map.render.pdf.PdfRenderGuard.FILE_NAME)
        )
        mbtilesOpenGuard = com.tacmap.map.render.MbtilesOpenGuard(
            java.io.File(noBackupFilesDir, com.tacmap.map.render.MbtilesOpenGuard.FILE_NAME)
        )
        pdfBakeManager = com.tacmap.calibration.PdfBakeManager(this, pdfRenderGuard)
        // no bake can be running at process start, anything in the work dir is dead
        pdfBakeManager.cleanWorkDirectory()
        registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                memoryPressure.forEach { it(level) }
            }

            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                memoryPressure.forEach { it(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
            }
        })
        CrashReporter.install(this)
    }
}
