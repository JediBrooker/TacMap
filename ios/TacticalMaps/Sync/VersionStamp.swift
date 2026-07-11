import Foundation

/// v3 vector clock: 63-bit counter + room-scoped actorId, transmitted as
/// "counterHex16:actorId". Higher counter wins; equal counter -> higher
/// actorId (lexicographic) wins. Never a JSON number.
struct VersionStamp: Comparable, Equatable, Codable {
    let counter: Int64
    let actorId: String

    static let maxCounter: Int64 = 0x7FFF_FFFF_FFFF_FFFF

    init(counter: Int64, actorId: String) {
        precondition(counter >= 0, "counter must be non-negative")
        self.counter = counter
        self.actorId = actorId
    }

    func encode() -> String {
        Self.counterHex16(counter) + ":" + actorId
    }

    static func parse(_ vs: String) -> VersionStamp? {
        guard let colonIdx = vs.firstIndex(of: ":"),
              vs.distance(from: vs.startIndex, to: colonIdx) == 16 else {
            return nil
        }
        let hexPart = String(vs[vs.startIndex..<colonIdx])
        let actor = String(vs[vs.index(after: colonIdx)...])
        guard !actor.isEmpty else { return nil }
        guard let counter = UInt64(hexPart, radix: 16),
              counter <= UInt64(maxCounter) else { return nil }
        return VersionStamp(counter: Int64(counter), actorId: actor)
    }

    static func counterHex16(_ counter: Int64) -> String {
        let raw = String(UInt64(bitPattern: counter), radix: 16)
        return String(repeating: "0", count: max(0, 16 - raw.count)) + raw
    }

    static func isNewer(_ incoming: VersionStamp, than existing: VersionStamp) -> Bool {
        incoming > existing
    }

    static func < (lhs: VersionStamp, rhs: VersionStamp) -> Bool {
        if lhs.counter != rhs.counter { return lhs.counter < rhs.counter }
        return lhs.actorId < rhs.actorId
    }
}
