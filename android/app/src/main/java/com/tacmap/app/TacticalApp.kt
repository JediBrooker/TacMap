package com.tacmap.app

import android.app.Application
import com.google.android.gms.maps.MapsInitializer
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
        // Maps SDK 18+ "latest" renderer eats way more GL/Java heap on init.
        // Use legacy renderer to keep peak memory under the largeHeap limit
        // (512 MB) on constrained devices and emulators.
        MapsInitializer.initialize(this, MapsInitializer.Renderer.LEGACY, null)
        CrashReporter.install(this)
    }
}
