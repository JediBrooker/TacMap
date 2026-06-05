package com.tacticalmaps.app

import android.app.Application
import com.google.android.gms.maps.MapsInitializer

/** Application entry point — installs local-only crash capture as early as
 *  possible so a field crash isn't silent. */
class TacticalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The Maps SDK 18+ "latest" renderer uses substantially more GL/Java
        // heap during initialisation. Prefer the legacy renderer to keep peak
        // memory below the largeHeap limit (512 MB) on constrained devices and
        // emulators.
        MapsInitializer.initialize(this, MapsInitializer.Renderer.LEGACY, null)
        CrashReporter.install(this)
    }
}
