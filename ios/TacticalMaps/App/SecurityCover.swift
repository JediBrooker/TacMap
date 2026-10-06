import SwiftUI
import UIKit
import Combine

/// What has to sit over the whole app right now. The lock wins over the
/// privacy cover, both win over the mission data screen, .none means nothing does.
enum SecurityCover: Equatable {
    case none
    case privacy
    case lock
    /// ContentView's "Mission data locked": auth-bound key locked, or a mission
    /// store that couldn't open without the key
    case missionData

    /// privacy arms on .inactive already, by .background it's too late for
    /// the app-switcher snapshot
    static func resolve(locked: Bool, privacyScreen: Bool, phase: ScenePhase) -> SecurityCover {
        if locked { return .lock }
        if privacyScreen && phase != .active { return .privacy }
        return .none
    }

    /// app = what RootGate resolved, the mission data screen only once that's .none
    static func combine(app: SecurityCover, missionDataLocked: Bool) -> SecurityCover {
        app == .none && missionDataLocked ? .missionData : app
    }
}

/// what the mission data screen shows under its title
struct MissionDataLock: Equatable {
    var detail: String?
}

/// Own class so any controller instance (and the tests) can tell a cover
/// window apart from the app's windows.
final class SecurityCoverWindow: UIWindow {}

/// Puts the lock / privacy cover in its own window above everything else in
/// each scene. Sheets, share sheets, file pickers and alerts are all presented
/// above the root view, so a cover drawn inside the root view (how 3.0 did it)
/// sat underneath every one of them, visible in the app switcher and still
/// tappable while "locked". A window above .alert covers all of that without
/// dismissing anything, so whatever was open is still there after unlock.
@MainActor
final class SecurityCoverWindows {
    static let shared = SecurityCoverWindows()

    /// above alerts, still under the keyboard (the lock view needs it for the PIN)
    static let windowLevel = UIWindow.Level(rawValue: UIWindow.Level.alert.rawValue + 1)

    private let model = SecurityCoverModel()
    /// what RootGate asked for (App Lock, privacy). ContentView's mission data
    /// screen sits in model.missionData and only shows when this is .none
    private var appCover: SecurityCover = .none
    private var covers: [SecurityCoverWindow] = []
    /// app windows we hid from VoiceOver plus what they had before
    private var muted: [(window: WeakWindow, wasHidden: Bool)] = []
    /// whoever was key before the cover went up gets it back on unlock
    private var previousKey: [WeakWindow] = []
    private var subscriptions: Set<AnyCancellable> = []
    /// A5: what a cover that only ever was .privacy took away (the text field
    /// we resigned, VoiceOver's focus). Handed back once it's down, so pulling
    /// down Control Center doesn't cost the keyboard. Never while covered: the
    /// keyboard still goes before the switcher snapshot
    private var takenEditors: [WeakObject] = []
    private var takenFocus: WeakObject?
    private var privacyOnly = false
    /// test seams, VoiceOver isn't running under XCTest
    var voiceOverFocus: () -> Any? = {
        UIAccessibility.isVoiceOverRunning ? UIAccessibility.focusedElement(using: .notificationVoiceOver) : nil
    }
    var postAccessibility: (UIAccessibility.Notification, Any?) -> Void = { UIAccessibility.post(notification: $0, argument: $1) }

    nonisolated init() {}

    var current: SecurityCover { model.cover }

    func apply(_ cover: SecurityCover, onUnlock: @escaping () -> Void) {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        apply(cover, onUnlock: onUnlock, scenes: scenes)
    }

    func apply(_ cover: SecurityCover, onUnlock: @escaping () -> Void, scenes: [UIWindowScene]) {
        model.onUnlock = onUnlock
        appCover = cover
        refresh(scenes)
    }

    /// ContentView's "Mission data locked" screen, nil once its unlocked. It was
    /// an overlay in the root view, so with the auth-bound key locked and App
    /// Lock off a sheet left open (waypoints, export) stayed on top of it,
    /// decrypted and usable. Same window as the lock now.
    func setMissionData(_ lock: MissionDataLock?, unlock: @escaping () -> Void) {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        setMissionData(lock, unlock: unlock, scenes: scenes)
    }

    func setMissionData(_ lock: MissionDataLock?, unlock: @escaping () -> Void, scenes: [UIWindowScene]) {
        model.missionData = lock
        model.onMissionUnlock = unlock
        refresh(scenes)
    }

    private func refresh(_ scenes: [UIWindowScene]) {
        let cover = SecurityCover.combine(app: appCover, missionDataLocked: model.missionData != nil)
        model.cover = cover
        covers.removeAll { $0.windowScene == nil }
        guard cover != .none else {
            tearDown()
            return
        }
        if covers.isEmpty {
            privacyOnly = true
            takenFocus = voiceOverFocus().map { WeakObject($0 as AnyObject) }
        }
        // a lock or the mission data screen on top means the user did more than
        // glance away, they get a clean slate after it
        if cover != .privacy { privacyOnly = false }
        watchWindows()
        for scene in scenes { raise(in: scene) }
    }

    func coverWindow(in scene: UIWindowScene) -> UIWindow? {
        covers.first { $0.windowScene === scene }
    }

    private func raise(in scene: UIWindowScene) {
        if coverWindow(in: scene) == nil {
            if let key = scene.windows.first(where: { $0.isKeyWindow && Self.isUnderneath($0) }) {
                previousKey.append(WeakWindow(key))
            }
            // drop first responders underneath, otherwise the keyboard keeps
            // typing into a sheet behind the cover
            for window in scene.windows where Self.isUnderneath(window) {
                if let editor = Self.firstResponder(in: window) { takenEditors.append(WeakObject(editor)) }
                window.endEditing(true)
            }
            let window = SecurityCoverWindow(windowScene: scene)
            window.windowLevel = Self.windowLevel
            window.backgroundColor = .black
            window.overrideUserInterfaceStyle = .dark
            window.accessibilityViewIsModal = true
            let host = SecurityCoverHostingController(rootView: SecurityCoverRoot(model: model))
            host.view.backgroundColor = .black
            host.view.accessibilityViewIsModal = true
            window.rootViewController = host
            covers.append(window)
            window.makeKeyAndVisible()
            postAccessibility(.screenChanged, host.view)
        }
        mute(scene)
    }

    private func mute(_ scene: UIWindowScene) {
        for window in scene.windows where Self.isUnderneath(window) {
            if !muted.contains(where: { $0.window.value === window }) {
                muted.append((WeakWindow(window), window.accessibilityElementsHidden))
            }
            window.accessibilityElementsHidden = true
        }
    }

    private func tearDown() {
        subscriptions.removeAll()
        for entry in muted {
            entry.window.value?.accessibilityElementsHidden = entry.wasHidden
        }
        muted.removeAll()
        for window in covers {
            let scene = window.windowScene
            window.isHidden = true
            window.rootViewController = nil
            let back = previousKey.compactMap(\.value).first { $0.windowScene === scene && !$0.isHidden }
                ?? scene?.windows.first { Self.isUnderneath($0) && !$0.isHidden && $0.windowLevel == .normal }
            back?.makeKey()
        }
        var focus: Any?
        if privacyOnly {
            for case let editor as UIView in takenEditors.compactMap(\.value) where Self.isShowing(editor) {
                editor.becomeFirstResponder()
            }
            if let view = takenFocus?.value as? UIView {
                if Self.isShowing(view) { focus = view }
            } else {
                focus = takenFocus?.value
            }
        }
        // VoiceOver was left on the cover that just went away. back where it was
        // after a privacy glance, else the screen's first element
        if !covers.isEmpty { postAccessibility(.screenChanged, focus) }
        covers.removeAll()
        previousKey.removeAll()
        takenEditors.removeAll()
        takenFocus = nil
        privacyOnly = false
    }

    private static func firstResponder(in view: UIView) -> UIView? {
        if view.isFirstResponder { return view }
        for sub in view.subviews {
            if let found = firstResponder(in: sub) { return found }
        }
        return nil
    }

    /// still on screen in an app window, not in a dismissed sheet
    private static func isShowing(_ view: UIView) -> Bool {
        guard let window = view.window, !window.isHidden, isUnderneath(window) else { return false }
        return true
    }

    /// a late alert, a new window or a text field grabbing focus while covered
    /// gets pushed straight back under the cover
    private func watchWindows() {
        guard subscriptions.isEmpty else { return }
        let center = NotificationCenter.default
        center.publisher(for: UIWindow.didBecomeVisibleNotification)
            .merge(with: center.publisher(for: UIWindow.didBecomeKeyNotification))
            .compactMap { $0.object as? UIWindow }
            .sink { [weak self] window in self?.windowChanged(window) }
            .store(in: &subscriptions)
    }

    private func windowChanged(_ window: UIWindow) {
        guard model.cover != .none, Self.isUnderneath(window),
              let scene = window.windowScene, let cover = coverWindow(in: scene) else { return }
        mute(scene)
        if window.isKeyWindow {
            window.endEditing(true)
            cover.makeKey()
        }
    }

    private static func isUnderneath(_ window: UIWindow) -> Bool {
        !(window is SecurityCoverWindow) && window.windowLevel < windowLevel
    }
}

private final class WeakWindow {
    weak var value: UIWindow?
    init(_ value: UIWindow) { self.value = value }
}

private final class WeakObject {
    weak var value: AnyObject?
    init(_ value: AnyObject) { self.value = value }
}

private final class SecurityCoverModel: ObservableObject {
    @Published var cover: SecurityCover = .none
    @Published var missionData: MissionDataLock?
    var onUnlock: () -> Void = {}
    var onMissionUnlock: () -> Void = {}
}

private struct SecurityCoverRoot: View {
    @ObservedObject var model: SecurityCoverModel
    @ObservedObject private var appLanguage = AppLanguage.shared

    var body: some View {
        Group {
            switch model.cover {
            case .lock: LockView { model.onUnlock() }
            case .privacy: PrivacyCoverView()
            case .missionData:
                MissionDataUnlockView(detail: model.missionData?.detail) { model.onMissionUnlock() }
            case .none: Color.black.ignoresSafeArea()
            }
        }
        // same environment the app root gets in TacticalMapsApp
        .environment(\.locale, appLanguage.locale)
        .preferredColorScheme(.dark)
        .nightModeContent()
    }
}

private final class SecurityCoverHostingController: UIHostingController<SecurityCoverRoot> {
    override var prefersStatusBarHidden: Bool { OpsecSettings.shared.nightMode }
    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }
}
