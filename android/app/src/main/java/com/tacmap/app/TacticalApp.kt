package com.tacmap.app

import android.app.Application
import com.tacmap.models.TrackRecorder
import com.tacmap.settings.OpsecSettings
import com.tacmap.util.DataKey

/** App entry point. Installs crash capture as early as possible so
 *  field crashes don't go silent. */
class TacticalApp : Application() {

    /** App-scoped OPSEC settings, shared by Activity + UI + networking layer. */
    lateinit var opsec: OpsecSettings
        private set

    /** Track recorder lives here at app scope so the foreground service
     *  can keep feeding fixes even after the Activity gets destroyed. */
    lateinit var trackRecorder: TrackRecorder
        private set

    override fun onCreate() {
        super.onCreate()
        // Has to come before any store is constructed - they all seal through it.
        DataKey.install(this)
        opsec = OpsecSettings(this)
        trackRecorder = TrackRecorder(this)
        CrashReporter.install(this)
    }
}
