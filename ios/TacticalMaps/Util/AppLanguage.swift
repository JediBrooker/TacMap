import Foundation
import Combine

/// Changes display copy without replacing the view tree or mission/recording state.
final class AppLanguage: ObservableObject {
    enum Choice: String, CaseIterable, Identifiable {
        case system, en, de
        var id: String { rawValue }
        var label: String {
            switch self {
            case .system: return L10n.text("Device language")
            case .en: return "English"
            case .de: return "Deutsch"
            }
        }
    }

    static let shared = AppLanguage()
    static let preferenceKey = "app.displayLanguage"
    private let defaults: UserDefaults
    @Published private(set) var selection: Choice

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        selection = Choice(rawValue: defaults.string(forKey: Self.preferenceKey) ?? "") ?? .system
    }

    func select(_ choice: Choice) {
        guard choice != selection else { return }
        defaults.set(choice.rawValue, forKey: Self.preferenceKey)
        selection = choice
    }

    var locale: Locale {
        selection == .system ? .current : Locale(identifier: selection.rawValue)
    }
}
