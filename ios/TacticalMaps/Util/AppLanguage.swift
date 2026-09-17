import Foundation
import Combine

/// Changes display copy without replacing the view tree or mission/recording state.
final class AppLanguage: ObservableObject {
    typealias Choice = SupportedLanguage

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
