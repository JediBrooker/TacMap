package com.tacmap.app

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()
        // Has to come before any store is constructed - they all seal through it.
        DataKey.install(this)
        appLock = AppLock(this)
        cleanupExportArtifacts(this)
        opsec = OpsecSettings(this)
        unitSyncRuntime = UnitSyncRuntime(this, opsec)
        trackRecorder = TrackRecorder(this)
        CrashReporter.install(this)
    }
}
