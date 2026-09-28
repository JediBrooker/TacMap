import UIKit
import Combine

/// Red night mode for preserving night vision. Every window of the app gets
/// an overlay above all of its content: one layer removes colour (saturation
/// blend with grey) and the next multiplies by red at the chosen brightness,
/// so each pixel becomes (brightness × luminance, 0, 0). Because the overlay
/// lives inside each window, sheets, alerts, menus and share sheets presented
/// there turn red as well. Android mirrors this with a colour-matrix layer.
@MainActor
final class NightModeController {
    static let shared = NightModeController()

    private var subscriptions: Set<AnyCancellable> = []
    private var enabled = false
    private var brightness = OpsecSettings.defaultNightModeBrightness

    nonisolated init() {}

    func start(settings: OpsecSettings = .shared) {
        guard subscriptions.isEmpty else { return }
        settings.$nightMode
            .combineLatest(settings.$nightModeBrightness)
            .sink { [weak self] enabled, brightness in
                self?.enabled = enabled
                self?.brightness = brightness
                self?.refresh()
            }
            .store(in: &subscriptions)
        // New windows (keyboard, alerts on some systems) need their own overlay.
        let center = NotificationCenter.default
        for name in [UIWindow.didBecomeVisibleNotification,
                     UIResponder.keyboardWillShowNotification,
                     UIScene.didActivateNotification] {
            center.publisher(for: name)
                .sink { [weak self] _ in self?.refresh() }
                .store(in: &subscriptions)
        }
    }

    func refresh() {
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows { update(window) }
        }
    }

    private func update(_ window: UIWindow) {
        let existing = window.subviews.compactMap { $0 as? NightModeOverlayView }
        guard enabled else {
            existing.forEach { $0.removeFromSuperview() }
            return
        }
        let overlay = existing.first ?? {
            let view = NightModeOverlayView(frame: window.bounds)
            window.addSubview(view)
            return view
        }()
        overlay.frame = window.bounds
        overlay.brightness = brightness
    }
}

/// The two blend layers. Never takes touches or accessibility focus.
final class NightModeOverlayView: UIView {
    /// Above every presentation container the window adds later.
    static let zPosition: CGFloat = 1_000_000

    private let desaturate = UIView()
    private let tint = UIView()

    var brightness: Double = OpsecSettings.defaultNightModeBrightness {
        didSet { tint.backgroundColor = UIColor(red: brightness, green: 0, blue: 0, alpha: 1) }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        layer.zPosition = Self.zPosition
        for (view, filter) in [(desaturate, "saturationBlendMode"), (tint, "multiplyBlendMode")] {
            view.frame = bounds
            view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.isUserInteractionEnabled = false
            view.layer.compositingFilter = filter
            addSubview(view)
        }
        desaturate.backgroundColor = .gray
        tint.backgroundColor = UIColor(red: brightness, green: 0, blue: 0, alpha: 1)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
}
