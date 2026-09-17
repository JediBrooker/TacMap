import Foundation

/// Resolves app-owned UI copy using the language selected for TacMap by iOS.
/// Keep user content, coordinates, asset names and persisted identifiers out of here.
enum L10n {
    static func text(_ key: String, _ arguments: Any...) -> String {
        let format = Bundle.main.localizedString(forKey: key, value: key, table: nil)
        guard !arguments.isEmpty else { return format }
        return String(format: format, locale: Locale.current,
                      arguments: arguments.map { String(describing: $0) as CVarArg })
    }

    static func quantity(_ noun: String, _ count: Int) -> String {
        let key = "count.\(noun)"
        let format = Bundle.main.localizedString(forKey: key, value: nil, table: nil)
        return String.localizedStringWithFormat(format, count)
    }
}
