import SwiftUI

@main
struct TacticalMapsApp: App {
    init() {
        // Local-only crash capture (no telemetry) so field crashes aren't silent.
        CrashReporter.install()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .statusBar(hidden: false)
        }
    }
}
