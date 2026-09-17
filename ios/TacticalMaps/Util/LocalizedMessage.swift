import Foundation

/// In-memory display state. Resolve app copy when shown; keep diagnostic details verbatim.
struct LocalizedMessage: Equatable {
    let id: String?
    let fallback: String
    let arguments: [String]
    private struct Plural: Equatable { let noun: String; let count: Int }
    private var plural: Plural?
    private var nestedArguments: [Int: LocalizedMessage] = [:]

    init(id: String?, fallback: String, arguments: [String]) {
        self.id = id
        self.fallback = fallback
        self.arguments = arguments
    }

    func withArgument(_ index: Int, _ message: LocalizedMessage) -> LocalizedMessage {
        precondition(arguments.indices.contains(index))
        var result = self
        result.nestedArguments[index] = message
        return result
    }

    static func literal(_ text: String) -> LocalizedMessage {
        LocalizedMessage(id: nil, fallback: text, arguments: [])
    }

    static func quantity(_ noun: String, _ count: Int) -> LocalizedMessage {
        var result = LocalizedMessage(id: "count." + noun, fallback: "", arguments: [])
        result.plural = Plural(noun: noun, count: count)
        return result
    }

    var text: String {
        if let plural { return L10n.quantity(plural.noun, plural.count) }
        guard let id else { return fallback }
        return L10n.resolveMessage(id, fallback: fallback, arguments: arguments.enumerated().map { nestedArguments[$0.offset]?.text ?? $0.element })
    }
}

/// App-owned errors preserve semantic copy; platform diagnostics stay verbatim.
protocol LocalizedMessageError: Error {
    var localizedMessage: LocalizedMessage { get }
}

extension Error {
    var displayMessage: LocalizedMessage {
        (self as? LocalizedMessageError)?.localizedMessage ?? .literal(localizedDescription)
    }
}
