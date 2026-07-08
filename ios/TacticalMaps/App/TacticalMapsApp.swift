import SwiftUI

@main
struct TacticalMapsApp: App {
    @StateObject private var store = StoreManager()
    private let trial = TrialManager()

    init() {
        // Local-only crash capture (no telemetry) so field crashes aren't silent.
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

/// Decides between the full app and the paywall: the app is available while
/// the unlock is purchased *or* the free trial is still running. Re-checks
/// when the app returns to the foreground (so a trial that lapsed while
/// backgrounded gates on resume).
private struct RootGate: View {
    @ObservedObject var store: StoreManager
    let trial: TrialManager
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var opsec = OpsecSettings.shared
    @State private var now = Date()
    /// Locked when an App Lock PIN is set; cleared after a successful unlock,
    /// re-armed when the app backgrounds.
    @State private var locked = AppLock.isEnabled

    var body: some View {
        ZStack {
            if store.isPurchased || trial.isTrialActive(now: now) {
                // ContentView owns the LocationService / TrackRecorder session
                // state. It stays mounted underneath the lock so backgrounding
                // (which arms the lock) does NOT deinit it — a track that is
                // recording keeps recording, and background location stays on.
                ContentView(store: store)
            } else {
                PaywallView(
                    store: store,
                    trialDaysRemaining: trial.daysRemaining(now: now),
                    onRestore: { Task { await store.restore() } }
                )
            }

            // Privacy screen: an opaque cover whenever the app isn't active
            // (armed on .inactive, before the app-switcher snapshot is taken) so
            // the map with the live position is never captured in the thumbnail.
            if opsec.privacyScreen && scenePhase != .active && !locked {
                PrivacyCoverView()
            }

            // Opaque lock overlay: covers the map (and its live position) while
            // locked without tearing down the view below it.
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

/// Opaque branded cover used as the privacy screen (app-switcher snapshot) so
/// the map and live position are never captured in the thumbnail.
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
