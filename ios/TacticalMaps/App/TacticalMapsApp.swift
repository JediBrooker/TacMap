import SwiftUI

@main
struct TacticalMapsApp: App {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @StateObject private var store = StoreManager()
    private let trial = TrialManager()

    init() {
#if DEBUG && targetEnvironment(simulator)
        // A UI test opts into resetting only its sealed Unit Sync identity.
        // Keeping this ahead of DataKey installation lets the first join create
        // clean signing material while leaving the simulator and mission DEK intact.
        _ = SyncManager.resetSigningIdentityForSimulatorUITestIfRequested()
#endif
        // Has to come before any store is constructed - they all seal through it.
        DataKey.install()
        // Share-sheet completion cannot run after process death. Remove expired
        // plaintext exports before presenting any mission UI.
        _ = ExportFileSecurity.purgeStaleArtifactsOnLaunch()
        // Local-only crash capture (no telemetry). Field crashes shouldn't be silent.
        CrashReporter.install()
        // Start the trial clock on first launch.
        TrialManager().startIfNeeded()
    }

    var body: some Scene {
        WindowGroup {
            RootGate(store: store, trial: trial)
                .environment(\.locale, appLanguage.locale)
                .preferredColorScheme(.dark)
                .statusBar(hidden: false)
        }
    }
}

/// Decides between full app and the paywall. App is available while
/// unlock is purchased or the free trial is still running. Re-checks
/// on foreground so a trial that lapsed while backgrounded gates on resume.
private struct RootGate: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
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
        .task {
            await store.start()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                now = Date()
                Task { await store.appDidBecomeActive() }
            }
            if phase != .active && DataKey.isAuthBound {
                // TrackRecorder owns a deliberately scoped copy only while an
                // explicitly started recording is active. Mission stores must
                // re-authenticate after every background/app-switcher trip.
                DataKey.lockKey()
            }
            if phase == .background {
                if AppLock.isEnabled { locked = true }
            }
        }
        .onAppear {
            if locked && DataKey.isAuthBound { DataKey.lockKey() }
            NotificationCenter.default.post(
                name: AppLock.stateChanged,
                object: NSNumber(value: locked)
            )
        }
        .onChange(of: locked) { isLocked in
            if isLocked && DataKey.isAuthBound { DataKey.lockKey() }
            NotificationCenter.default.post(
                name: AppLock.stateChanged,
                object: NSNumber(value: isLocked)
            )
        }
        .alert("App Store",
               isPresented: Binding(get: { store.storeIssue != nil },
                                    set: { if !$0 { store.storeIssue = nil } }),
               presenting: store.storeIssue) { issue in
            if issue.retryable {
                Button(L10n.text("Check Again")) {
                    Task { await store.checkEntitlementAgain() }
                }
            }
            Button(L10n.text("Dismiss"), role: .cancel) { store.storeIssue = nil }
        } message: { Text($0.message) }
    }
}

/// Opaque branded cover for the privacy screen (app-switcher snapshot).
/// Map and live position are never captured in the thumbnail.
private struct PrivacyCoverView: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
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
