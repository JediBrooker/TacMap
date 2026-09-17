import Foundation

/// Keep user content, coordinates, asset names and persisted identifiers out of here.
enum L10n {
    private static let bundles: [String: Bundle] = SupportedLanguage.resourceFolders.reduce(into: [:]) { result, entry in
        let (code, folder) = entry
        if let path = Bundle.main.path(forResource: folder, ofType: "lproj"),
           let bundle = Bundle(path: path) { result[code] = bundle }
    }

    static func bundle(for choice: AppLanguage.Choice) -> Bundle {
        bundles[choice.rawValue] ?? .main
    }

    static func text(_ key: String, _ arguments: Any...) -> String {
        let language = AppLanguage.shared
        let format = bundle(for: language.selection).localizedString(forKey: key, value: key, table: nil)
        guard !arguments.isEmpty else { return format }
        return String(format: format, locale: language.locale,
                      arguments: arguments.map { String(describing: $0) as CVarArg })
    }

    /// Stable IDs for generated, typed messages. The legacy text API remains a bridge.
    static func message(_ id: String, fallback: String, _ arguments: String...) -> String {
        resolveMessage(id, fallback: fallback, arguments: arguments)
    }

    static func resolveMessage(_ id: String, fallback: String, arguments: [String]) -> String {
        let language = AppLanguage.shared
        let format = bundle(for: language.selection).localizedString(forKey: id, value: fallback, table: nil)
        guard !arguments.isEmpty else { return format }
        return String(format: format, locale: language.locale, arguments: arguments)
    }

    static func quantity(_ noun: String, _ count: Int) -> String {
        let language = AppLanguage.shared
        let key = "count.\(noun)"
        let format = bundle(for: language.selection).localizedString(forKey: key, value: nil, table: nil)
        return String(format: format, locale: language.locale, arguments: [count])
    }
}
