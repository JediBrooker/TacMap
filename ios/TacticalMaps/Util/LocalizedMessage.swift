import Foundation

/// In-memory display state. Resolve app copy when shown; keep diagnostic details verbatim.
struct LocalizedMessage: Equatable {
    let id: String?
    let fallback: String
    let arguments: [String]
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

    var text: String {
        guard let id else { return fallback }
        return L10n.resolveMessage(id, fallback: fallback, arguments: arguments.enumerated().map { nestedArguments[$0.offset]?.text ?? $0.element })
    }
}
