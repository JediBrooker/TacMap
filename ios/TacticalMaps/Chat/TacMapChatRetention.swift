import Foundation

// Chat replay fence + history size rules (contract section 15).

enum ChatReplayPruner {
    static let maxFences = 256

    struct FenceIdentity: Hashable {
        let actorId: String
        let sessionDomain: String
        let keyId: String
    }

    /// A fence is dead once the durable replay state has moved that actor to
    /// a different session domain: commitActorHello never lets an older sd
    /// come back, so the fence can never be consulted again.
    static func isSuperseded(_ fence: FenceIdentity, durableSessionDomain: (String) -> String?) -> Bool {
        guard let current = durableSessionDomain(fence.actorId) else { return false }
        return current != fence.sessionDomain
    }

    static func retained(
        _ fences: [FenceIdentity],
        durableSessionDomain: (String) -> String?
    ) -> [FenceIdentity] {
        fences.filter { !isSuperseded($0, durableSessionDomain: durableSessionDomain) }
    }

    enum Admission: Equatable {
        case accepted
        case rejectedTableFull
    }

    /// For a brand new identity: room left after pruning superseded fences?
    static func admitNewIdentity(currentCount: Int, supersededCount: Int) -> Admission {
        currentCount - supersededCount < maxFences ? .accepted : .rejectedTableFull
    }
}

enum ChatHistoryBudget {
    static let maxMessages = 500
    static let maxEncodedBytes = 2_097_152
    static let pruneTargetBytes = 1_572_864

    /// How many of the oldest messages to drop. Nothing happens until the
    /// document would go over maxEncodedBytes, then it shrinks to the target
    /// so we dont prune again on the very next message.
    static func dropCount(fixedOverheadBytes: Int, separatorBytes: Int, messageBytes: [Int]) -> Int {
        func total(_ kept: ArraySlice<Int>) -> Int {
            fixedOverheadBytes + kept.reduce(0, +) + separatorBytes * max(0, kept.count - 1)
        }
        guard total(messageBytes[...]) > maxEncodedBytes else { return 0 }
        var drop = 0
        var running = total(messageBytes[...])
        while drop < messageBytes.count, running > pruneTargetBytes {
            running -= messageBytes[drop]
            if messageBytes.count - drop > 1 { running -= separatorBytes }
            drop += 1
        }
        return drop
    }
}
