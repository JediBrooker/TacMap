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
    @State private var now = Date()

    var body: some View {
        Group {
            if store.isPurchased || trial.isTrialActive(now: now) {
                ContentView(store: store)
            } else {
                PaywallView(
                    store: store,
                    trialDaysRemaining: trial.daysRemaining(now: now),
                    onRestore: { Task { await store.restore() } }
                )
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                now = Date()
                Task { await store.refreshEntitlement() }
            }
        }
    }
}
