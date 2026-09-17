import Foundation

/// Presentation only. Never use for coordinates, storage, signatures or exports.
enum DisplayFormat {
    static func locale(languageTag: String?, device: Locale = .current) -> Locale {
        guard let languageTag else { return device }
        var components = Locale.Components(identifier: languageTag)
        if let region = device.region { components.languageComponents.region = region }
        return Locale(components: components)
    }

    static var currentLocale: Locale {
        let choice = AppLanguage.shared.selection
        return locale(languageTag: choice == .system ? nil : choice.rawValue)
    }

    static func number(_ value: Double, decimals: Int, locale: Locale = currentLocale) -> String {
        guard value.isFinite else { return "—" }
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.usesGroupingSeparator = false
        formatter.minimumFractionDigits = decimals
        formatter.maximumFractionDigits = decimals
        formatter.roundingMode = .halfEven
        return formatter.string(from: NSNumber(value: value)) ?? "—"
    }

    static func distance(_ metres: Double, locale: Locale = currentLocale) -> String {
        if metres < 1000 { return number(metres, decimals: 0, locale: locale) + " m" }
        return number(metres / 1000, decimals: metres < 100_000 ? 2 : 0, locale: locale) + " km"
    }

    static func area(_ squareMetres: Double, locale: Locale = currentLocale) -> String {
        if squareMetres < 10_000 { return number(squareMetres, decimals: 0, locale: locale) + " m²" }
        if squareMetres < 1_000_000 { return number(squareMetres / 10_000, decimals: 2, locale: locale) + " ha" }
        return number(squareMetres / 1_000_000, decimals: 2, locale: locale) + " km²"
    }

    static func time(_ date: Date, locale: Locale = currentLocale, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
