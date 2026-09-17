import Foundation

/// In-memory display state. Resolve app copy when shown; keep diagnostic details verbatim.
struct LocalizedMessage: Equatable {
    let id: String?
    let fallback: String
    let arguments: [String]

    static func literal(_ text: String) -> LocalizedMessage {
        LocalizedMessage(id: nil, fallback: text, arguments: [])
    }

    var text: String {
        guard let id else { return fallback }
        return L10n.resolveMessage(id, fallback: fallback, arguments: arguments)
    }
}
