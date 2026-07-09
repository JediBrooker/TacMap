import SwiftUI

@main
struct TacticalMapsApp: App {
    @StateObject private var store = StoreManager()
    private let trial = TrialManager()

    init() {
        // Local-only crash capture (no telemetry). Field crashes shouldn't be silent.
        CrashReporter.install()
        // Start the trial clock on first launch.
        TrialManager().startIfNeeded()
    }

    var body: some Scene {
        WindowGroup {
            RootGate(store: store, trial: trial)
                .preferredColorScheme(.dark)
                .statusBar(hidden: false)
        }
    }
}

/// Decides between full app and the paywall. App is available while
/// unlock is purchased or the free trial is still running. Re-checks
/// on foreground so a trial that lapsed while backgrounded gates on resume.
private struct RootGate: View {
    @ObservedObject var store: StoreManager
    let trial: TrialManager
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var opsec = OpsecSettings.shared
    @State private var now = Date()
    /// Locked when App Lock PIN is set. Cleared after successful unlock,
    /// re-armed when app backgrounds.
    @State private var locked = AppLock.isEnabled

    var body: some View {
        ZStack {
            if store.isPurchased || trial.isTrialActive(now: now) {
                // ContentView owns LocationService / TrackRecorder session
                // state. Stays mounted underneath the lock so backgrounding
                // (which arms the lock) does NOT deinit it - a track that's
                // recording keeps recording and background location stays on.
                ContentView(store: store)
            } else {
                PaywallView(
                    store: store,
                    trialDaysRemaining: trial.daysRemaining(now: now),
                    onRestore: { Task { await store.restore() } }
                )
            }

            // Privacy screen: opaque cover whenever app isn't active
            // (armed on .inactive, before app-switcher snapshot) so
            // the map with live position is never in the thumbnail.
            if opsec.privacyScreen && scenePhase != .active && !locked {
                PrivacyCoverView()
            }

            // Opaque lock overlay. Covers the map (and live position) while
            // locked without tearing down the view underneath.
            if locked {
                LockView { locked = false }
                    .transition(.opacity)
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                now = Date()
                Task { await store.refreshEntitlement() }
            }
            if phase == .background && AppLock.isEnabled {
                locked = true
            }
        }
    }
}

/// Opaque branded cover for the privacy screen (app-switcher snapshot).
/// Map and live position are never captured in the thumbnail.
private struct PrivacyCoverView: View {
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 14) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 48))
                    .foregroundStyle(Color(red: 0.55, green: 0.95, blue: 0.55))
                Text("TacMap").font(.title2.bold()).foregroundStyle(.white)
            }
        }
    }
}
