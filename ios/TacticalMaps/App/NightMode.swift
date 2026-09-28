import UIKit
import Combine

/// The UIKit side of night mode. The red conversion itself is SwiftUI
/// (`NightModeContent`); system alerts and pop-up menus are drawn outside the
/// app's SwiftUI views where it cannot reach, so their buttons turn white
/// instead of blue while night mode is on. Android converts every window with
/// a colour-matrix layer instead.
@MainActor
final class NightModeController {
    static let shared = NightModeController()

    private var subscriptions: Set<AnyCancellable> = []
    private var enabled = false

    nonisolated init() {}

    func start(settings: OpsecSettings = .shared) {
        guard subscriptions.isEmpty else { return }
        settings.$nightMode
            .sink { [weak self] enabled in
                self?.enabled = enabled
                self?.refresh()
            }
            .store(in: &subscriptions)
        NotificationCenter.default.publisher(for: UIWindow.didBecomeVisibleNotification)
            .sink { [weak self] _ in self?.refresh() }
            .store(in: &subscriptions)
    }

    func refresh() {
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                window.tintColor = enabled ? .white : nil
            }
        }
    }
}
