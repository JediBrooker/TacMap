package com.tacmap.app

import android.app.Application
import com.google.android.gms.maps.MapsInitializer
import com.tacmap.models.TrackRecorder
import com.tacmap.settings.OpsecSettings

/** Application entry point — installs local-only crash capture as early as
 *  possible so a field crash isn't silent. */
class TacticalApp : Application() {

    /** App-scoped OPSEC/privacy settings shared by the Activity, UI, and network. */
    lateinit var opsec: OpsecSettings
        private set

    /** App-scoped track recorder so the foreground service can feed fixes
     *  even when the Activity (and its ViewModel) is destroyed. */
    lateinit var trackRecorder: TrackRecorder
        private set

    override fun onCreate() {
        super.onCreate()
        opsec = OpsecSettings(this)
        trackRecorder = TrackRecorder(this)
        // The Maps SDK 18+ "latest" renderer uses substantially more GL/Java
        // heap during initialisation. Prefer the legacy renderer to keep peak
        // memory below the largeHeap limit (512 MB) on constrained devices and
        // emulators.
        MapsInitializer.initialize(this, MapsInitializer.Renderer.LEGACY, null)
        CrashReporter.install(this)
    }
}
