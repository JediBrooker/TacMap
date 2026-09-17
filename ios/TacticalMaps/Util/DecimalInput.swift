import Foundation

/// A scalar input grammar, not a coordinate parser. Dots always mean decimals;
/// the locale's decimal separator is also accepted. Grouping is never stripped.
enum DecimalInput {
    static func parse(_ text: String, locale: Locale = DisplayFormat.currentLocale) -> Double? {
        var value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let separator = locale.decimalSeparator ?? "."
        if separator != "." { value = value.replacingOccurrences(of: separator, with: ".") }
        let pattern = #"^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$"#
        guard value.range(of: pattern, options: .regularExpression) != nil,
              let parsed = Double(value), parsed.isFinite else { return nil }
        return parsed
    }
}
